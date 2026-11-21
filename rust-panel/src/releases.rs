use crate::{
    apk,
    auth::{now, random_token},
    error::{ApiError, Result},
    AppState,
};
use axum::{
    body::Body,
    extract::{Multipart, Path, State},
    http::{header, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;
use url::Url;
pub const MAX_APK_BYTES: usize = 256 * 1024 * 1024;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseInput {
    version_code: i64,
    version_name: String,
    notes: String,
    source_url: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Release {
    version_code: i64,
    version_name: String,
    sha256: String,
    notes: String,
    source_url: String,
    package_name: String,
    size_bytes: i64,
    status: String,
    created_at: i64,
    published_at: Option<i64>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicRelease {
    version_code: i64,
    version_name: String,
    apk_url: String,
    sha256: String,
    notes: String,
}
struct Cleanup {
    path: PathBuf,
    armed: bool,
}
impl Drop for Cleanup {
    fn drop(&mut self) {
        if self.armed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
fn validate_input(input: &ReleaseInput) -> Result<()> {
    if !(1..=2_100_000_000).contains(&input.version_code)
        || input.version_name.trim().is_empty()
        || input.version_name.chars().count() > 100
        || input.version_name.chars().any(|c| c.is_control())
        || input.notes.chars().count() > 12000
    {
        return Err(ApiError::bad("Version code must be 1–2100000000, version name 1–100 characters, and notes at most 12000 characters"));
    }
    let source = Url::parse(&input.source_url)
        .map_err(|_| ApiError::bad("Provide an HTTPS URL for GPL corresponding source"))?;
    if input.source_url.len() > 2048
        || source.scheme() != "https"
        || source.host_str().is_none()
        || !source.username().is_empty()
        || source.password().is_some()
        || input.source_url.chars().any(|c| c.is_control())
    {
        return Err(ApiError::bad(
            "Corresponding source must be an HTTPS URL without credentials",
        ));
    }
    Ok(())
}
fn increasing(conn: &rusqlite::Connection, code: i64) -> Result<()> {
    let max: i64 = conn.query_row(
        "SELECT COALESCE(MAX(version_code),0) FROM releases",
        [],
        |r| r.get(0),
    )?;
    if code <= max {
        return Err(ApiError::conflict(format!(
            "Version code must be greater than {max}; used version codes cannot be reused"
        )));
    }
    Ok(())
}
pub async fn upload(
    State(state): State<AppState>,
    multipart: Multipart,
) -> Result<(StatusCode, Json<Value>)> {
    let _permit = state.uploads.clone().try_acquire_owned().map_err(|_| {
        ApiError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "Two uploads are already in progress; try again later",
        )
    })?;
    tokio::time::timeout(
        std::time::Duration::from_secs(600),
        upload_inner(state, multipart),
    )
    .await
    .map_err(|_| {
        ApiError::new(
            StatusCode::REQUEST_TIMEOUT,
            "Upload exceeded the 10 minute time limit",
        )
    })?
}
async fn upload_inner(
    state: AppState,
    mut multipart: Multipart,
) -> Result<(StatusCode, Json<Value>)> {
    let multipart_error = |err: axum::extract::multipart::MultipartError| {
        let status = err.status();
        let status = if status.is_client_error() {
            status
        } else {
            StatusCode::BAD_REQUEST
        };
        ApiError::new(
            status,
            "Invalid multipart upload or request exceeds 256 MiB plus metadata allowance",
        )
    };
    let mut input_field = multipart
        .next_field()
        .await
        .map_err(multipart_error)?
        .ok_or_else(|| ApiError::bad("Send metadata first, then apk"))?;
    if input_field.name() != Some("metadata") {
        return Err(ApiError::bad("First multipart field must be metadata JSON"));
    }
    let mut raw = Vec::new();
    while let Some(chunk) = input_field.chunk().await.map_err(multipart_error)? {
        if raw.len() + chunk.len() > 49152 {
            return Err(ApiError::bad("Release metadata exceeds 48 KiB"));
        }
        raw.extend_from_slice(&chunk);
    }
    drop(input_field);
    let input: ReleaseInput =
        serde_json::from_slice(&raw).map_err(|_| ApiError::bad("Invalid release metadata JSON"))?;
    validate_input(&input)?;
    {
        let conn = state.db()?;
        increasing(&conn, input.version_code)?;
    }
    let mut field = multipart
        .next_field()
        .await
        .map_err(multipart_error)?
        .ok_or_else(|| ApiError::bad("APK is required"))?;
    if field.name() != Some("apk") {
        return Err(ApiError::bad("Second multipart field must be apk"));
    }
    let filename = format!("{}.apk", random_token());
    let tmp = state
        .settings
        .data_dir
        .join("uploads")
        .join(format!("{}.part", random_token()));
    let mut cleanup = Cleanup {
        path: tmp.clone(),
        armed: true,
    };
    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&tmp)
        .await?;
    let mut hasher = Sha256::new();
    let mut size = 0usize;
    while let Some(chunk) = field.chunk().await.map_err(multipart_error)? {
        size = size
            .checked_add(chunk.len())
            .ok_or_else(|| ApiError::bad("APK too large"))?;
        if size > MAX_APK_BYTES {
            return Err(ApiError::new(
                StatusCode::PAYLOAD_TOO_LARGE,
                "APK must not exceed 256 MiB",
            ));
        }
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
    }
    drop(field);
    file.flush().await?;
    file.sync_all().await?;
    drop(file);
    if size == 0 {
        return Err(ApiError::bad("APK cannot be empty"));
    }
    if multipart
        .next_field()
        .await
        .map_err(multipart_error)?
        .is_some()
    {
        return Err(ApiError::bad(
            "Only metadata and apk multipart fields are accepted",
        ));
    }
    let code = input.version_code;
    let name = input.version_name.clone();
    let manifest = tokio::task::spawn_blocking(move || apk::validate(&tmp, code, &name))
        .await
        .map_err(|e| ApiError::internal(e.to_string()))??;
    let sha256 = hex::encode(hasher.finalize());
    let destination = state.settings.data_dir.join("apks").join(&filename);
    {
        let mut conn = state.db()?;
        let tx = conn.transaction()?;
        increasing(&tx, input.version_code)?;
        // Random opaque names, never client filenames. Hard-link creation fails if
        // the destination already exists and therefore cannot replace an artifact.
        std::fs::hard_link(&cleanup.path, &destination)?;
        let mut final_cleanup = Cleanup {
            path: destination,
            armed: true,
        };
        tx.execute("INSERT INTO releases(version_code,version_name,filename,sha256,notes,source_url,package_name,size_bytes,status,created_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,'draft',?9)", params![input.version_code,input.version_name,filename,sha256,input.notes,input.source_url,manifest.package,size as i64,now()])?;
        tx.commit()?;
        final_cleanup.armed = false;
    }
    std::fs::remove_file(&cleanup.path)?;
    cleanup.armed = false;
    Ok((
        StatusCode::CREATED,
        Json(json!({"versionCode":input.version_code,"sha256":sha256,"status":"draft"})),
    ))
}
pub async fn list(State(state): State<AppState>) -> Result<Json<Value>> {
    let conn = state.db()?;
    let mut stmt = conn.prepare("SELECT version_code,version_name,sha256,notes,source_url,package_name,size_bytes,status,created_at,published_at FROM releases ORDER BY version_code DESC LIMIT 500")?;
    let releases: Vec<Release> = stmt
        .query_map([], |r| {
            Ok(Release {
                version_code: r.get(0)?,
                version_name: r.get(1)?,
                sha256: r.get(2)?,
                notes: r.get(3)?,
                source_url: r.get(4)?,
                package_name: r.get(5)?,
                size_bytes: r.get(6)?,
                status: r.get(7)?,
                created_at: r.get(8)?,
                published_at: r.get(9)?,
            })
        })?
        .collect::<std::result::Result<_, _>>()?;
    Ok(Json(json!({"releases":releases})))
}
pub async fn publish(
    State(state): State<AppState>,
    Path(version): Path<i64>,
) -> Result<StatusCode> {
    let mut conn = state.db()?;
    let tx = conn.transaction()?;
    let status: Option<String> = tx
        .query_row(
            "SELECT status FROM releases WHERE version_code=?1",
            [version],
            |r| r.get(0),
        )
        .optional()?;
    match status.as_deref() {
        None => return Err(ApiError::not_found("Release not found")),
        Some("draft") => {}
        _ => {
            return Err(ApiError::conflict(
                "Only a draft can be published; withdrawn releases cannot be republished",
            ))
        }
    }
    let max: i64 = tx.query_row(
        "SELECT COALESCE(MAX(version_code),0) FROM releases WHERE published_at IS NOT NULL",
        [],
        |r| r.get(0),
    )?;
    if version <= max {
        return Err(ApiError::conflict(
            "Publishing must increase the highest ever published version code",
        ));
    }
    tx.execute(
        "UPDATE releases SET status='published',published_at=?1 WHERE version_code=?2",
        params![now(), version],
    )?;
    tx.commit()?;
    Ok(StatusCode::NO_CONTENT)
}
pub async fn withdraw(
    State(state): State<AppState>,
    Path(version): Path<i64>,
) -> Result<StatusCode> {
    let affected = state.db()?.execute("UPDATE releases SET status='withdrawn' WHERE version_code=?1 AND status IN ('draft','published')", [version])?;
    if affected == 0 {
        return Err(ApiError::conflict(
            "Release does not exist or is already withdrawn",
        ));
    }
    Ok(StatusCode::NO_CONTENT)
}
fn public_release(state: &AppState, version: Option<i64>) -> Result<Option<PublicRelease>> {
    let conn = state.db()?;
    Ok(conn.query_row("SELECT version_code,version_name,filename,sha256,notes FROM releases WHERE status='published' AND (?1 IS NULL OR version_code=?1) ORDER BY version_code DESC LIMIT 1", [version], |r| Ok(PublicRelease {version_code:r.get(0)?,version_name:r.get(1)?,apk_url:format!("{}/api/app/apks/{}",state.settings.public_base_url,r.get::<_,String>(2)?),sha256:r.get(3)?,notes:r.get(4)?})).optional()?)
}
pub async fn latest(State(state): State<AppState>) -> Result<Response> {
    Ok(match public_release(&state, None)? {
        Some(release) => Json(release).into_response(),
        None => StatusCode::NO_CONTENT.into_response(),
    })
}
pub async fn by_version(
    State(state): State<AppState>,
    Path(version): Path<i64>,
) -> Result<Json<PublicRelease>> {
    Ok(Json(public_release(&state, Some(version))?.ok_or_else(
        || ApiError::not_found("Published release not found"),
    )?))
}
pub async fn source(
    State(state): State<AppState>,
    Path(version): Path<i64>,
) -> Result<Json<Value>> {
    let source_url: Option<String> = state
        .db()?
        .query_row(
            "SELECT source_url FROM releases WHERE version_code=?1 AND status='published'",
            [version],
            |r| r.get(0),
        )
        .optional()?;
    Ok(Json(
        json!({"sourceUrl":source_url.ok_or_else(|| ApiError::not_found("Published release not found"))?}),
    ))
}
pub async fn download(
    State(state): State<AppState>,
    Path(filename): Path<String>,
) -> Result<Response> {
    if filename.len() != 68
        || !filename.ends_with(".apk")
        || !filename.as_bytes()[..64]
            .iter()
            .all(|c| c.is_ascii_hexdigit())
    {
        return Err(ApiError::not_found("APK not found"));
    }
    let artifact: Option<(i64, String)> = state
        .db()?
        .query_row(
            "SELECT size_bytes,sha256 FROM releases WHERE filename=?1 AND status='published'",
            [&filename],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let (size, hash) = artifact.ok_or_else(|| ApiError::not_found("APK not found"))?;
    let file = tokio::fs::File::open(state.settings.data_dir.join("apks").join(&filename))
        .await
        .map_err(|_| ApiError::not_found("APK file not found"))?;
    let mut response = Body::from_stream(ReaderStream::new(file)).into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/vnd.android.package-archive"),
    );
    response.headers_mut().insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&size.to_string()).map_err(|e| ApiError::internal(e.to_string()))?,
    );
    response.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_str(&format!("attachment; filename=\"{filename}\""))
            .map_err(|e| ApiError::internal(e.to_string()))?,
    );
    response.headers_mut().insert(
        header::ETAG,
        HeaderValue::from_str(&format!("\"{hash}\""))
            .map_err(|e| ApiError::internal(e.to_string()))?,
    );
    Ok(response)
}
