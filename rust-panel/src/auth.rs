use crate::{
    error::{ApiError, Result},
    AppState,
};
use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    extract::{ConnectInfo, State},
    http::{header, HeaderMap, HeaderValue, Method, Request, StatusCode},
    middleware::Next,
    response::{IntoResponse, Response},
    Extension, Json,
};
use rand::{rngs::OsRng, RngCore};
use rusqlite::{params, OptionalExtension};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;

pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}
pub fn random_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}
pub fn token_hash(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}
pub fn hash_password(password: &str) -> Result<String> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|v| v.to_string())
        .map_err(|e| ApiError::internal(e.to_string()))
}
pub fn bootstrap(state: &AppState, username: &str, password: &str) -> Result<()> {
    if username.trim() != username
        || username.is_empty()
        || username.len() > 80
        || username.chars().any(|c| c.is_control())
    {
        return Err(ApiError::bad(
            "Administrator username must have 1–80 bytes without surrounding whitespace",
        ));
    }
    if password.chars().count() < 12 || password.len() > 1024 {
        return Err(ApiError::bad(
            "Password must have at least 12 characters and at most 1024 bytes",
        ));
    }
    let hash = hash_password(password)?;
    let conn = state.db()?;
    if conn.query_row("SELECT COUNT(*) FROM admins", [], |r| r.get::<_, i64>(0))? != 0 {
        return Err(ApiError::conflict(
            "An administrator already exists; bootstrap cannot overwrite credentials",
        ));
    }
    conn.execute(
        "INSERT INTO admins(id, username, password_hash) VALUES(1, ?1, ?2)",
        params![username, hash],
    )?;
    Ok(())
}
pub fn rate_limit(
    state: &AppState,
    ip: IpAddr,
    kind: &'static str,
    max: u32,
    seconds: u64,
) -> Result<()> {
    let mut limits = state
        .limits
        .lock()
        .map_err(|_| ApiError::internal("Rate limit lock unavailable"))?;
    if limits.len() >= 10000 {
        limits.retain(|_, (start, _)| start.elapsed() < Duration::from_secs(600));
        if !limits.contains_key(&(ip, kind)) && limits.len() >= 10000 {
            return Err(ApiError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "Too many requests; try again later",
            ));
        }
    }
    let entry = limits.entry((ip, kind)).or_insert((Instant::now(), 0));
    if entry.0.elapsed() >= Duration::from_secs(seconds) {
        *entry = (Instant::now(), 0);
    }
    if entry.1 >= max {
        return Err(ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many requests; try again later",
        ));
    }
    entry.1 += 1;
    Ok(())
}
pub fn check_origin(state: &AppState, headers: &HeaderMap) -> Result<()> {
    let mut values = headers.get_all(header::ORIGIN).iter();
    let value = values.next().and_then(|v| v.to_str().ok());
    if value != Some(state.settings.admin_origin.as_str()) || values.next().is_some() {
        return Err(ApiError::forbidden());
    }
    if headers.get("sec-fetch-site").and_then(|h| h.to_str().ok()) == Some("cross-site") {
        return Err(ApiError::forbidden());
    }
    Ok(())
}
fn cookie_name(state: &AppState) -> &'static str {
    if state.settings.production {
        "__Host-panel_session"
    } else {
        "panel_session"
    }
}
fn cookie(state: &AppState, token: &str, max_age: u32) -> Result<HeaderValue> {
    let secure = if state.settings.production {
        "; Secure"
    } else {
        ""
    };
    HeaderValue::from_str(&format!(
        "{}={}; Path=/; HttpOnly; SameSite=Strict; Max-Age={}{}",
        cookie_name(state),
        token,
        max_age,
        secure
    ))
    .map_err(|e| ApiError::internal(e.to_string()))
}
#[derive(Clone)]
pub struct AuthSession {
    pub token_hash: String,
    pub csrf: String,
    pub username: String,
}
fn authenticate(state: &AppState, headers: &HeaderMap) -> Result<AuthSession> {
    let name = cookie_name(state);
    let token = headers
        .get(header::COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|cookies| {
            cookies.split(';').find_map(|item| {
                let (key, value) = item.trim().split_once('=')?;
                (key == name).then_some(value)
            })
        })
        .ok_or_else(ApiError::unauthorized)?;
    if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(ApiError::unauthorized());
    }
    let hash = token_hash(token);
    let session = state.db()?.query_row("SELECT s.csrf, a.username FROM sessions s JOIN admins a ON a.id=s.admin_id WHERE s.token_hash=?1 AND s.expires_at>?2", params![hash, now()], |r| Ok(AuthSession { token_hash: hash.clone(), csrf: r.get(0)?, username: r.get(1)? })).optional()?;
    session.ok_or_else(ApiError::unauthorized)
}
pub async fn require_admin(
    State(state): State<AppState>,
    mut request: Request<axum::body::Body>,
    next: Next,
) -> Response {
    let result = (|| -> Result<AuthSession> {
        let session = authenticate(&state, request.headers())?;
        if !matches!(
            *request.method(),
            Method::GET | Method::HEAD | Method::OPTIONS
        ) {
            check_origin(&state, request.headers())?;
            let csrf = request
                .headers()
                .get("x-csrf-token")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("");
            if !bool::from(csrf.as_bytes().ct_eq(session.csrf.as_bytes())) {
                return Err(ApiError::forbidden());
            }
        }
        Ok(session)
    })();
    match result {
        Ok(session) => {
            request.extensions_mut().insert(session);
            next.run(request).await
        }
        Err(err) => err.into_response(),
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Login {
    username: String,
    password: String,
}
pub async fn login(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<Login>,
) -> Result<Response> {
    check_origin(&state, &headers)?;
    rate_limit(&state, peer.ip(), "login", 10, 300)?;
    rate_limit(
        &state,
        IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        "login-global",
        60,
        300,
    )?;
    if body.username.len() > 80 || body.password.len() > 1024 {
        return Err(ApiError::new(
            StatusCode::UNAUTHORIZED,
            "Invalid username or password",
        ));
    }
    let _permit = state.logins.clone().try_acquire_owned().map_err(|_| {
        ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "Sign-in is busy; try again shortly",
        )
    })?;
    let stored: Option<String> = state
        .db()?
        .query_row(
            "SELECT password_hash FROM admins WHERE username=?1",
            [&body.username],
            |r| r.get(0),
        )
        .optional()?;
    let known = stored.is_some();
    let hash = stored.unwrap_or_else(|| state.dummy_hash.as_ref().clone());
    let valid = tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash)
            .map(|parsed| {
                Argon2::default()
                    .verify_password(body.password.as_bytes(), &parsed)
                    .is_ok()
            })
            .unwrap_or(false)
    })
    .await
    .map_err(|e| ApiError::internal(e.to_string()))?;
    if !known || !valid {
        return Err(ApiError::new(
            StatusCode::UNAUTHORIZED,
            "Invalid username or password",
        ));
    }
    let token = random_token();
    let csrf = random_token();
    {
        let conn = state.db()?;
        conn.execute("DELETE FROM sessions WHERE expires_at<=?1", [now()])?;
        conn.execute("DELETE FROM sessions WHERE token_hash NOT IN (SELECT token_hash FROM sessions ORDER BY rowid DESC LIMIT 9)", [])?;
        conn.execute(
            "INSERT INTO sessions(token_hash, csrf, expires_at, admin_id) VALUES(?1, ?2, ?3, 1)",
            params![token_hash(&token), csrf, now() + 28800],
        )?;
    }
    let mut response = Json(json!({"csrfToken": csrf})).into_response();
    response
        .headers_mut()
        .insert(header::SET_COOKIE, cookie(&state, &token, 28800)?);
    Ok(response)
}
pub async fn session(Extension(session): Extension<AuthSession>) -> Json<serde_json::Value> {
    Json(json!({"username": session.username, "csrfToken": session.csrf}))
}
pub async fn logout(
    State(state): State<AppState>,
    Extension(session): Extension<AuthSession>,
) -> Result<Response> {
    state.db()?.execute(
        "DELETE FROM sessions WHERE token_hash=?1",
        [session.token_hash],
    )?;
    let mut response = StatusCode::NO_CONTENT.into_response();
    response
        .headers_mut()
        .insert(header::SET_COOKIE, cookie(&state, "", 0)?);
    Ok(response)
}
