mod apk;
mod auth;
mod config;
mod db;
mod error;
mod messages;
mod releases;
mod servers;

use axum::{
    extract::{ConnectInfo, DefaultBodyLimit, State},
    http::{HeaderValue, Request},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post, put},
    Router,
};
use config::Settings;
use error::{ApiError, Result};
use rusqlite::Connection;
use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    time::Instant,
};
use tokio::sync::Semaphore;

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Mutex<Connection>>,
    pub settings: Arc<Settings>,
    pub limits: Arc<Mutex<HashMap<(IpAddr, &'static str), (Instant, u32)>>>,
    pub uploads: Arc<Semaphore>,
    pub logins: Arc<Semaphore>,
    pub dummy_hash: Arc<String>,
}
impl AppState {
    pub fn open(settings: Settings) -> Result<Self> {
        std::fs::create_dir_all(settings.data_dir.join("apks"))?;
        std::fs::create_dir_all(settings.data_dir.join("uploads"))?;
        let conn = db::open(&settings.data_dir.join("panel.sqlite3"))?;
        Ok(Self {
            db: Arc::new(Mutex::new(conn)),
            settings: Arc::new(settings),
            limits: Arc::new(Mutex::new(HashMap::new())),
            uploads: Arc::new(Semaphore::new(2)),
            logins: Arc::new(Semaphore::new(4)),
            dummy_hash: Arc::new(auth::hash_password(&auth::random_token())?),
        })
    }
    pub fn db(&self) -> Result<std::sync::MutexGuard<'_, Connection>> {
        self.db
            .lock()
            .map_err(|_| ApiError::internal("Database lock unavailable"))
    }
}

pub fn app(state: AppState) -> Router {
    let admin = Router::new()
        .route("/session", get(auth::session))
        .route("/logout", post(auth::logout))
        .route(
            "/releases",
            get(releases::list)
                .post(releases::upload)
                .layer(DefaultBodyLimit::max(releases::MAX_APK_BYTES + 65536)),
        )
        .route("/releases/{version}/publish", post(releases::publish))
        .route("/releases/{version}/withdraw", post(releases::withdraw))
        .route("/messages", get(messages::list).post(messages::create))
        .route("/messages/{id}", put(messages::update))
        .route(
            "/config",
            get(servers::get_config).put(servers::update_config),
        )
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_admin,
        ));
    Router::new()
        .route(
            "/",
            get(|| async { Html(include_str!("../static/index.html")) }),
        )
        .route(
            "/app.js",
            get(|| async {
                (
                    [("content-type", "text/javascript; charset=utf-8")],
                    include_str!("../static/app.js"),
                )
            }),
        )
        .route(
            "/style.css",
            get(|| async {
                (
                    [("content-type", "text/css; charset=utf-8")],
                    include_str!("../static/style.css"),
                )
            }),
        )
        .route("/health", get(|| async { "ok" }))
        .route("/api/admin/login", post(auth::login))
        .nest("/api/admin", admin)
        .route("/api/app/releases/latest", get(releases::latest))
        .route("/api/app/releases/{version}", get(releases::by_version))
        .route("/api/app/releases/{version}/source", get(releases::source))
        .route("/api/app/apks/{filename}", get(releases::download))
        .route("/api/app/messages", get(messages::active))
        .route("/api/app/config", get(servers::get_config))
        .fallback(|| async { ApiError::not_found("Route not found") })
        .layer(DefaultBodyLimit::max(65536))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            security_headers,
        ))
        .layer(tower_http::trace::TraceLayer::new_for_http())
        .with_state(state)
}

async fn security_headers(
    State(state): State<AppState>,
    request: Request<axum::body::Body>,
    next: Next,
) -> Response {
    // The socket peer, never user-supplied X-Forwarded-For, identifies clients.
    if let Some(ConnectInfo(peer)) = request.extensions().get::<ConnectInfo<SocketAddr>>() {
        if let Err(err) = auth::rate_limit(&state, peer.ip(), "requests", 1200, 60) {
            return err.into_response();
        }
    }
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    headers.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    headers.insert("x-frame-options", HeaderValue::from_static("DENY"));
    headers.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
    headers.insert("cache-control", HeaderValue::from_static("no-store"));
    headers.insert("content-security-policy", HeaderValue::from_static("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"));
    if state.settings.production {
        headers.insert(
            "strict-transport-security",
            HeaderValue::from_static("max-age=31536000"),
        );
    }
    response
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "managed_tv_panel=info,tower_http=info".into()),
        )
        .init();
    let settings = Settings::from_env()?;
    let state = AppState::open(settings)?;
    match std::env::args().nth(1).as_deref() {
        Some("bootstrap-admin") => {
            let username = std::env::var("ADMIN_USERNAME")
                .map_err(|_| "Set ADMIN_USERNAME before running bootstrap-admin")?;
            let password = match std::env::var("ADMIN_PASSWORD") {
                Ok(value) => value,
                Err(_) => {
                    rpassword::prompt_password("New admin password (at least 12 characters): ")?
                }
            };
            auth::bootstrap(&state, &username, &password)?;
            println!("Administrator created. Remove ADMIN_PASSWORD from your environment and run with 'serve'.");
            return Ok(());
        }
        Some("serve") | None => {}
        _ => return Err("Usage: managed-tv-panel [bootstrap-admin|serve]".into()),
    }
    let count: i64 = state
        .db()?
        .query_row("SELECT COUNT(*) FROM admins", [], |r| r.get(0))?;
    if count == 0 {
        return Err("No administrator configured. Run bootstrap-admin first; no default credentials are provided.".into());
    }
    let address = SocketAddr::new(state.settings.host, state.settings.port);
    let listener = tokio::net::TcpListener::bind(address).await?;
    tracing::info!(%address, public_base_url = %state.settings.public_base_url, "Management panel started");
    axum::serve(
        listener,
        app(state).into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async {
        let _ = tokio::signal::ctrl_c().await;
    })
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests;
