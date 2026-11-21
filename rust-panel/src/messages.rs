use crate::{
    auth::{now, random_token},
    error::{ApiError, Result},
    AppState,
};
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Message {
    id: String,
    title: String,
    body: String,
    active: bool,
    created_at: i64,
    expires_at: Option<i64>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewMessage {
    title: String,
    body: String,
    expires_at: Option<i64>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChangeMessage {
    active: bool,
    expires_at: Option<i64>,
}
fn valid_expiry(expiry: Option<i64>) -> Result<()> {
    if expiry.is_some_and(|v| v <= now() || v > now() + 366 * 86400) {
        return Err(ApiError::bad(
            "Expiration must be in the future and within one year",
        ));
    }
    Ok(())
}
fn available(conn: &rusqlite::Connection, exclude: &str) -> Result<()> {
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM messages WHERE active=1 AND (expires_at IS NULL OR expires_at>?1) AND id<>?2", params![now(), exclude], |r| r.get(0))?;
    if count >= 20 {
        return Err(ApiError::conflict(
            "At most 20 messages may be active; deactivate an existing message first",
        ));
    }
    Ok(())
}
pub async fn active(State(state): State<AppState>) -> Result<Json<Value>> {
    let conn = state.db()?;
    let mut stmt = conn.prepare("SELECT id,title,body FROM messages WHERE active=1 AND (expires_at IS NULL OR expires_at>?1) ORDER BY created_at DESC, id DESC LIMIT 20")?;
    let messages: Vec<Value> = stmt.query_map([now()], |r| Ok(json!({"id":r.get::<_,String>(0)?,"title":r.get::<_,String>(1)?,"body":r.get::<_,String>(2)?})))?.collect::<std::result::Result<_,_>>()?;
    Ok(Json(json!({"messages":messages})))
}
pub async fn list(State(state): State<AppState>) -> Result<Json<Value>> {
    let conn = state.db()?;
    let mut stmt = conn.prepare("SELECT id,title,body,active,created_at,expires_at FROM messages ORDER BY created_at DESC, id DESC LIMIT 500")?;
    let messages: Vec<Message> = stmt
        .query_map([], |r| {
            Ok(Message {
                id: r.get(0)?,
                title: r.get(1)?,
                body: r.get(2)?,
                active: r.get(3)?,
                created_at: r.get(4)?,
                expires_at: r.get(5)?,
            })
        })?
        .collect::<std::result::Result<_, _>>()?;
    Ok(Json(json!({"messages":messages})))
}
pub async fn create(
    State(state): State<AppState>,
    Json(body): Json<NewMessage>,
) -> Result<(StatusCode, Json<Value>)> {
    let title = body.title.trim();
    let text = body.body.trim();
    if title.is_empty()
        || title.chars().count() > 120
        || text.is_empty()
        || text.chars().count() > 4000
    {
        return Err(ApiError::bad(
            "Title must contain 1–120 characters; body must contain 1–4000 characters",
        ));
    }
    valid_expiry(body.expires_at)?;
    let id = random_token();
    let conn = state.db()?;
    available(&conn, "")?;
    conn.execute(
        "INSERT INTO messages(id,title,body,active,created_at,expires_at) VALUES(?1,?2,?3,1,?4,?5)",
        params![id, title, text, now(), body.expires_at],
    )?;
    Ok((StatusCode::CREATED, Json(json!({"id":id}))))
}
pub async fn update(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(body): Json<ChangeMessage>,
) -> Result<StatusCode> {
    if body.active {
        valid_expiry(body.expires_at)?;
    }
    let conn = state.db()?;
    if body.active {
        available(&conn, &id)?;
    }
    let affected = conn.execute(
        "UPDATE messages SET active=?1,expires_at=?2 WHERE id=?3",
        params![body.active, body.expires_at, id],
    )?;
    if affected == 0 {
        return Err(ApiError::not_found("Message not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}
