use crate::{
    config::origin,
    error::{ApiError, Result},
    AppState,
};
use axum::{extract::State, Json};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Server {
    pub id: String,
    pub name: String,
    pub url: String,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServerConfig {
    pub revision: i64,
    pub servers: Vec<Server>,
    pub default_server_id: String,
}
pub fn read(state: &AppState) -> Result<ServerConfig> {
    let (revision, default_server_id, raw): (i64, String, String) = state.db()?.query_row(
        "SELECT revision, default_server_id, servers_json FROM config WHERE id=1",
        [],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    let servers = serde_json::from_str(&raw).map_err(|e| ApiError::internal(e.to_string()))?;
    Ok(ServerConfig {
        revision,
        servers,
        default_server_id,
    })
}
pub fn validate(config: &mut ServerConfig) -> Result<()> {
    if config.servers.is_empty() || config.servers.len() > 10 {
        return Err(ApiError::bad("Configure between 1 and 10 servers"));
    }
    let mut ids = HashSet::new();
    let mut urls = HashSet::new();
    for server in &mut config.servers {
        if server.id.is_empty()
            || server.id.len() > 64
            || !server
                .id
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
            || !ids.insert(server.id.clone())
        {
            return Err(ApiError::bad("Server IDs must be unique and contain 1–64 letters, numbers, underscores or hyphens"));
        }
        server.name = server.name.trim().to_string();
        if server.name.is_empty()
            || server.name.chars().count() > 80
            || server.name.chars().any(|c| c.is_control())
        {
            return Err(ApiError::bad("Server names must contain 1–80 characters"));
        }
        server.url = origin(&server.url, true)?;
        if !urls.insert(server.url.clone()) {
            return Err(ApiError::bad(
                "Server URLs must be unique after normalization",
            ));
        }
    }
    if !ids.contains(&config.default_server_id) {
        return Err(ApiError::bad("The default server must exist in the list"));
    }
    Ok(())
}
pub async fn get_config(State(state): State<AppState>) -> Result<Json<ServerConfig>> {
    Ok(Json(read(&state)?))
}
pub async fn update_config(
    State(state): State<AppState>,
    Json(mut config): Json<ServerConfig>,
) -> Result<Json<ServerConfig>> {
    validate(&mut config)?;
    let raw =
        serde_json::to_string(&config.servers).map_err(|e| ApiError::internal(e.to_string()))?;
    let mut conn = state.db()?;
    let tx = conn.transaction()?;
    let current: i64 = tx.query_row("SELECT revision FROM config WHERE id=1", [], |r| r.get(0))?;
    if config.revision != current {
        return Err(ApiError::conflict(
            "Server configuration changed. Reload before saving.",
        ));
    }
    config.revision = current
        .checked_add(1)
        .ok_or_else(|| ApiError::internal("Revision overflow"))?;
    tx.execute(
        "UPDATE config SET revision=?1, default_server_id=?2, servers_json=?3 WHERE id=1",
        params![config.revision, config.default_server_id, raw],
    )?;
    tx.commit()?;
    Ok(Json(config))
}
