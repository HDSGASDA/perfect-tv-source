use super::*;
use axum::{
    body::{to_bytes, Body},
    extract::ConnectInfo,
    http::{header, Method, Request, StatusCode},
};
use rusqlite::params;
use serde_json::{json, Value};
use std::{
    io::{Cursor, Write},
    net::SocketAddr,
};
use tempfile::TempDir;
use tower::ServiceExt;

fn fixture() -> (TempDir, AppState) {
    let dir = tempfile::tempdir().unwrap();
    let state = AppState::open(Settings {
        host: "127.0.0.1".parse().unwrap(),
        port: 3000,
        public_base_url: "http://localhost:3000".into(),
        admin_origin: "http://localhost:3000".into(),
        production: false,
        data_dir: dir.path().into(),
    })
    .unwrap();
    (dir, state)
}
fn seeded_session(state: &AppState) -> (String, String) {
    let token = auth::random_token();
    let csrf = auth::random_token();
    let conn = state.db().unwrap();
    conn.execute("INSERT OR IGNORE INTO admins(id,username,password_hash) VALUES(1,'operator','unused-in-this-fixture')", []).unwrap();
    conn.execute(
        "INSERT INTO sessions(token_hash,csrf,expires_at,admin_id) VALUES(?1,?2,?3,1)",
        params![auth::token_hash(&token), csrf, auth::now() + 3600],
    )
    .unwrap();
    (format!("panel_session={token}"), csrf)
}
async fn request(
    state: &AppState,
    method: Method,
    uri: &str,
    body: Body,
    cookie: Option<&str>,
    csrf: Option<&str>,
    origin: Option<&str>,
    content_type: Option<&str>,
) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
    let mut req = Request::builder()
        .method(method)
        .uri(uri)
        .header("host", "attacker.invalid");
    if let Some(v) = cookie {
        req = req.header(header::COOKIE, v);
    }
    if let Some(v) = csrf {
        req = req.header("x-csrf-token", v);
    }
    if let Some(v) = origin {
        req = req.header(header::ORIGIN, v);
    }
    if let Some(v) = content_type {
        req = req.header(header::CONTENT_TYPE, v);
    }
    let mut req = req.body(body).unwrap();
    req.extensions_mut().insert(ConnectInfo(
        "127.0.0.1:12345".parse::<SocketAddr>().unwrap(),
    ));
    let response = app(state.clone()).oneshot(req).await.unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let bytes = to_bytes(response.into_body(), 2 * 1024 * 1024)
        .await
        .unwrap()
        .to_vec();
    (status, headers, bytes)
}
async fn get(state: &AppState, uri: &str) -> (StatusCode, Value) {
    let (status, _, bytes) = request(
        state,
        Method::GET,
        uri,
        Body::empty(),
        None,
        None,
        None,
        None,
    )
    .await;
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}
async fn write_json(
    state: &AppState,
    method: Method,
    uri: &str,
    value: Value,
    session: &(String, String),
) -> (StatusCode, Value) {
    let (status, _, bytes) = request(
        state,
        method,
        uri,
        Body::from(value.to_string()),
        Some(&session.0),
        Some(&session.1),
        Some("http://localhost:3000"),
        Some("application/json"),
    )
    .await;
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

#[tokio::test]
async fn authentication_origin_csrf_logout_and_persistence() {
    let (_dir, state) = fixture();
    assert_eq!(
        get(&state, "/api/admin/session").await.0,
        StatusCode::UNAUTHORIZED
    );
    assert!(auth::bootstrap(&state, "operator", "short").is_err());
    auth::bootstrap(&state, "operator", "long-test-password-123!").unwrap();
    assert!(auth::bootstrap(&state, "other", "long-test-password-123!").is_err());
    let hash: String = state
        .db()
        .unwrap()
        .query_row("SELECT password_hash FROM admins", [], |r| r.get(0))
        .unwrap();
    assert!(hash.starts_with("$argon2id$"));
    assert!(!hash.contains("long-test-password"));
    let login = json!({"username":"operator","password":"long-test-password-123!"}).to_string();
    for origin in [None, Some("https://attacker.invalid"), Some("null")] {
        let result = request(
            &state,
            Method::POST,
            "/api/admin/login",
            Body::from(login.clone()),
            None,
            None,
            origin,
            Some("application/json"),
        )
        .await;
        assert_eq!(result.0, StatusCode::FORBIDDEN);
    }
    let failed = request(
        &state,
        Method::POST,
        "/api/admin/login",
        Body::from(json!({"username":"operator","password":"wrong"}).to_string()),
        None,
        None,
        Some("http://localhost:3000"),
        Some("application/json"),
    )
    .await;
    assert_eq!(failed.0, StatusCode::UNAUTHORIZED);
    let (status, headers, bytes) = request(
        &state,
        Method::POST,
        "/api/admin/login",
        Body::from(login),
        None,
        None,
        Some("http://localhost:3000"),
        Some("application/json"),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let cookie = headers[header::SET_COOKIE].to_str().unwrap();
    assert!(cookie.contains("HttpOnly"));
    assert!(cookie.contains("SameSite=Strict"));
    assert!(!cookie.contains("; Secure"));
    let cookie = cookie.split(';').next().unwrap().to_string();
    let csrf = serde_json::from_slice::<Value>(&bytes).unwrap()["csrfToken"]
        .as_str()
        .unwrap()
        .to_string();
    for (token, origin) in [
        (None, Some("http://localhost:3000")),
        (Some("bad"), Some("http://localhost:3000")),
        (Some(csrf.as_str()), None),
        (Some(csrf.as_str()), Some("https://attacker.invalid")),
    ] {
        assert_eq!(
            request(
                &state,
                Method::POST,
                "/api/admin/logout",
                Body::empty(),
                Some(&cookie),
                token,
                origin,
                None
            )
            .await
            .0,
            StatusCode::FORBIDDEN
        );
    }
    let reopened = AppState::open(state.settings.as_ref().clone()).unwrap();
    assert_eq!(
        request(
            &reopened,
            Method::GET,
            "/api/admin/session",
            Body::empty(),
            Some(&cookie),
            None,
            None,
            None
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        write_json(
            &state,
            Method::POST,
            "/api/admin/logout",
            json!({}),
            &(cookie.clone(), csrf)
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        request(
            &state,
            Method::GET,
            "/api/admin/session",
            Body::empty(),
            Some(&cookie),
            None,
            None,
            None
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn production_cookie_and_login_throttling() {
    let (_dir, mut state) = fixture();
    let mut settings = state.settings.as_ref().clone();
    settings.production = true;
    settings.admin_origin = "https://panel.example".into();
    settings.public_base_url = settings.admin_origin.clone();
    state.settings = Arc::new(settings);
    auth::bootstrap(&state, "operator", "long-test-password-123!").unwrap();
    let data = json!({"username":"operator","password":"long-test-password-123!"}).to_string();
    let result = request(
        &state,
        Method::POST,
        "/api/admin/login",
        Body::from(data),
        None,
        None,
        Some("https://panel.example"),
        Some("application/json"),
    )
    .await;
    assert_eq!(result.0, StatusCode::OK);
    assert!(result.1[header::SET_COOKIE]
        .to_str()
        .unwrap()
        .starts_with("__Host-panel_session="));
    assert!(result.1[header::SET_COOKIE]
        .to_str()
        .unwrap()
        .contains("; Secure"));
    assert!(result.1.contains_key("strict-transport-security"));
    for _ in 0..9 {
        auth::rate_limit(&state, "127.0.0.1".parse().unwrap(), "login", 10, 300).unwrap();
    }
    let blocked = request(
        &state,
        Method::POST,
        "/api/admin/login",
        Body::from(json!({"username":"operator","password":"wrong"}).to_string()),
        None,
        None,
        Some("https://panel.example"),
        Some("application/json"),
    )
    .await;
    assert_eq!(blocked.0, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn config_atomic_revision_validation_and_persistence() {
    let (_dir, state) = fixture();
    let session = seeded_session(&state);
    let (status, initial) = get(&state, "/api/app/config").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        initial,
        json!({"revision":1,"servers":[{"id":"primary","name":"Primary server","url":"https://protect.securemy.network:443"}],"defaultServerId":"primary"})
    );
    let valid = json!({"revision":1,"servers":[{"id":"one","name":" First ","url":"https://EXAMPLE.com:443/"},{"id":"two","name":"Second","url":"https://second.example:8443"}],"defaultServerId":"two"});
    let (status, saved) = write_json(
        &state,
        Method::PUT,
        "/api/admin/config",
        valid.clone(),
        &session,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(saved["revision"], 2);
    assert_eq!(saved["servers"][0]["url"], "https://example.com");
    assert_eq!(saved["servers"][0]["name"], "First");
    assert_eq!(
        write_json(&state, Method::PUT, "/api/admin/config", valid, &session)
            .await
            .0,
        StatusCode::CONFLICT
    );
    for bad_url in [
        "http://example.com",
        "https://user:pass@example.com",
        "https://example.com/path",
        "https://example.com/?q=x",
        "https://example.com/#x",
        "https://example.com/a/..",
        "javascript:alert(1)",
    ] {
        let bad = json!({"revision":2,"servers":[{"id":"one","name":"One","url":bad_url}],"defaultServerId":"one"});
        assert_eq!(
            write_json(&state, Method::PUT, "/api/admin/config", bad, &session)
                .await
                .0,
            StatusCode::BAD_REQUEST
        );
        assert_eq!(get(&state, "/api/app/config").await.1, saved);
    }
    for bad in [
        json!({"revision":2,"servers":[],"defaultServerId":"one"}),
        json!({"revision":2,"servers":[{"id":"unsafe/id","name":"One","url":"https://a.example"}],"defaultServerId":"unsafe/id"}),
        json!({"revision":2,"servers":[{"id":"one","name":"One","url":"https://a.example"}],"defaultServerId":"missing"}),
        json!({"revision":2,"servers":[{"id":"one","name":"One","url":"https://a.example"},{"id":"two","name":"Two","url":"https://A.example:443/"}],"defaultServerId":"one"}),
    ] {
        assert_eq!(
            write_json(&state, Method::PUT, "/api/admin/config", bad, &session)
                .await
                .0,
            StatusCode::BAD_REQUEST
        );
    }
    let reopened = AppState::open(state.settings.as_ref().clone()).unwrap();
    assert_eq!(get(&reopened, "/api/app/config").await.1, saved);
}

#[tokio::test]
async fn messages_limits_expiration_deactivation_and_persistence() {
    let (_dir, state) = fixture();
    let session = seeded_session(&state);
    assert_eq!(
        get(&state, "/api/app/messages").await.1,
        json!({"messages":[]})
    );
    let (status,created)=write_json(&state,Method::POST,"/api/admin/messages",json!({"title":"<script>not HTML</script>","body":"Public notice","expiresAt":auth::now()+3600}),&session).await;
    assert_eq!(status, StatusCode::CREATED);
    let id = created["id"].as_str().unwrap();
    let messages = get(&state, "/api/app/messages").await.1;
    assert_eq!(
        messages["messages"][0]["title"],
        "<script>not HTML</script>"
    );
    assert_eq!(messages["messages"][0].as_object().unwrap().len(), 3);
    let reopened = AppState::open(state.settings.as_ref().clone()).unwrap();
    assert_eq!(get(&reopened, "/api/app/messages").await.1, messages);
    assert_eq!(
        write_json(
            &state,
            Method::PUT,
            &format!("/api/admin/messages/{id}"),
            json!({"active":false,"expiresAt":null}),
            &session
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        get(&state, "/api/app/messages").await.1,
        json!({"messages":[]})
    );
    for bad in [
        json!({"title":"x".repeat(121),"body":"OK"}),
        json!({"title":"OK","body":"x".repeat(4001)}),
        json!({"title":"OK","body":"OK","expiresAt":auth::now()-1}),
    ] {
        assert_eq!(
            write_json(&state, Method::POST, "/api/admin/messages", bad, &session)
                .await
                .0,
            StatusCode::BAD_REQUEST
        );
    }
    for i in 0..20 {
        assert_eq!(
            write_json(
                &state,
                Method::POST,
                "/api/admin/messages",
                json!({"title":format!("Notice {i}"),"body":"Public"}),
                &session
            )
            .await
            .0,
            StatusCode::CREATED
        );
    }
    assert_eq!(
        write_json(
            &state,
            Method::POST,
            "/api/admin/messages",
            json!({"title":"Overflow","body":"Public"}),
            &session
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        get(&state, "/api/app/messages").await.1["messages"]
            .as_array()
            .unwrap()
            .len(),
        20
    );
    state
        .db()
        .unwrap()
        .execute(
            "UPDATE messages SET expires_at=?1 WHERE active=1",
            [auth::now() - 1],
        )
        .unwrap();
    assert_eq!(
        get(&state, "/api/app/messages").await.1,
        json!({"messages":[]})
    );
}

// Deliberately fake signing artifacts: these fixtures test structure, NOT real
// cryptographic signing. The server must never claim to verify a signer.
fn binary_manifest(package: &str, code: u32, name: &str) -> Vec<u8> {
    let strings = [
        "manifest",
        "package",
        package,
        "http://schemas.android.com/apk/res/android",
        "versionCode",
        "versionName",
        name,
    ];
    let mut values = Vec::new();
    let mut offsets = Vec::new();
    for value in strings {
        assert!(value.len() < 128);
        offsets.push(values.len() as u32);
        values.push(value.encode_utf16().count() as u8);
        values.push(value.len() as u8);
        values.extend_from_slice(value.as_bytes());
        values.push(0);
    }
    let start = 28 + offsets.len() * 4;
    let mut pool = Vec::new();
    pool.extend_from_slice(&1u16.to_le_bytes());
    pool.extend_from_slice(&28u16.to_le_bytes());
    pool.extend_from_slice(&((start + values.len()) as u32).to_le_bytes());
    for value in [offsets.len() as u32, 0, 0x100, start as u32, 0] {
        pool.extend_from_slice(&value.to_le_bytes());
    }
    for offset in offsets {
        pool.extend_from_slice(&offset.to_le_bytes());
    }
    pool.extend(values);
    let mut node = Vec::new();
    node.extend_from_slice(&0x0102u16.to_le_bytes());
    node.extend_from_slice(&16u16.to_le_bytes());
    node.extend_from_slice(&96u32.to_le_bytes());
    for value in [1u32, u32::MAX, u32::MAX, 0] {
        node.extend_from_slice(&value.to_le_bytes());
    }
    for value in [20u16, 20, 3, 0, 0, 0] {
        node.extend_from_slice(&value.to_le_bytes());
    }
    for (ns, key, raw, kind, value) in [
        (u32::MAX, 1u32, 2u32, 3u8, 2u32),
        (3, 4, u32::MAX, 0x10, code),
        (3, 5, 6, 3, 6),
    ] {
        for value in [ns, key, raw] {
            node.extend_from_slice(&value.to_le_bytes());
        }
        node.extend_from_slice(&8u16.to_le_bytes());
        node.push(0);
        node.push(kind);
        node.extend_from_slice(&value.to_le_bytes());
    }
    let mut xml = vec![3, 0, 8, 0];
    xml.extend_from_slice(&((8 + pool.len() + node.len()) as u32).to_le_bytes());
    xml.extend(pool);
    xml.extend(node);
    xml
}
fn test_apk(package: &str, code: u32, name: &str, signing: bool) -> Vec<u8> {
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    zip.start_file("AndroidManifest.xml", options).unwrap();
    zip.write_all(&binary_manifest(package, code, name))
        .unwrap();
    zip.start_file("classes.dex", options).unwrap();
    zip.write_all(b"dex\n035\0").unwrap();
    if signing {
        for path in ["META-INF/MANIFEST.MF", "META-INF/TEST.RSA"] {
            zip.start_file(path, options).unwrap();
            zip.write_all(b"TEST ONLY, not a signature").unwrap();
        }
    }
    zip.finish().unwrap().into_inner()
}
async fn upload(
    state: &AppState,
    session: &(String, String),
    code: i64,
    apk: Vec<u8>,
) -> (StatusCode, Value) {
    let metadata = json!({"versionCode":code,"versionName":"0.6.0-managed.1","notes":"Test release","sourceUrl":"https://source.example/release"});
    let mut body=format!("--test-boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{metadata}\r\n--test-boundary\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"ignored.apk\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n").into_bytes();
    body.extend(apk);
    body.extend_from_slice(b"\r\n--test-boundary--\r\n");
    let (status, _, bytes) = request(
        state,
        Method::POST,
        "/api/admin/releases",
        Body::from(body),
        Some(&session.0),
        Some(&session.1),
        Some("http://localhost:3000"),
        Some("multipart/form-data; boundary=test-boundary"),
    )
    .await;
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}
#[tokio::test]
async fn apk_upload_public_contract_publication_withdrawal_and_hash() {
    use sha2::{Digest, Sha256};
    let (_dir, state) = fixture();
    let session = seeded_session(&state);
    assert_eq!(
        get(&state, "/api/app/releases/latest").await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        get(&state, "/api/app/releases/11").await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        upload(&state, &session, 11, b"not a ZIP".to_vec()).await.0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        upload(
            &state,
            &session,
            11,
            test_apk("wrong.package", 11, "0.6.0-managed.1", true)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        upload(
            &state,
            &session,
            11,
            test_apk(apk::EXPECTED_PACKAGE, 12, "0.6.0-managed.1", true)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        upload(
            &state,
            &session,
            11,
            test_apk(apk::EXPECTED_PACKAGE, 11, "0.6.0-managed.1", false)
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let bytes = test_apk(apk::EXPECTED_PACKAGE, 11, "0.6.0-managed.1", true);
    let hash = hex::encode(Sha256::digest(&bytes));
    let (status, result) = upload(&state, &session, 11, bytes.clone()).await;
    assert_eq!(status, StatusCode::CREATED, "{result}");
    assert_eq!(result["sha256"], hash);
    assert_eq!(
        get(&state, "/api/app/releases/latest").await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        upload(&state, &session, 11, bytes.clone()).await.0,
        StatusCode::CONFLICT
    );
    let filename: String = state
        .db()
        .unwrap()
        .query_row(
            "SELECT filename FROM releases WHERE version_code=11",
            [],
            |r| r.get(0),
        )
        .unwrap();
    let path = format!("/api/app/apks/{filename}");
    assert_eq!(get(&state, &path).await.0, StatusCode::NOT_FOUND);
    assert_eq!(
        write_json(
            &state,
            Method::POST,
            "/api/admin/releases/11/publish",
            json!({}),
            &session
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let (_, release) = get(&state, "/api/app/releases/latest").await;
    assert_eq!(release.as_object().unwrap().len(), 5);
    assert_eq!(release["versionCode"], 11);
    assert_eq!(release["apkUrl"], format!("http://localhost:3000{path}"));
    assert_eq!(release["sha256"], hash);
    assert_eq!(get(&state, "/api/app/releases/11").await.1, release);
    assert_eq!(
        get(&state, "/api/app/releases/11/source").await.1["sourceUrl"],
        "https://source.example/release"
    );
    let (status, _, download) = request(
        &state,
        Method::GET,
        &path,
        Body::empty(),
        None,
        None,
        None,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(download, bytes);
    let reopened = AppState::open(state.settings.as_ref().clone()).unwrap();
    assert_eq!(get(&reopened, "/api/app/releases/latest").await.1, release);
    assert_eq!(
        write_json(
            &state,
            Method::POST,
            "/api/admin/releases/11/withdraw",
            json!({}),
            &session
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        get(&state, "/api/app/releases/latest").await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(get(&state, &path).await.0, StatusCode::NOT_FOUND);
    assert_eq!(
        write_json(
            &state,
            Method::POST,
            "/api/admin/releases/11/publish",
            json!({}),
            &session
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert!(std::fs::read_dir(state.settings.data_dir.join("uploads"))
        .unwrap()
        .next()
        .is_none());
}
#[test]
fn manifest_parser_rejects_truncated_and_malformed_input() {
    let manifest = binary_manifest(apk::EXPECTED_PACKAGE, 11, "0.6.0-managed.1");
    for length in 0..manifest.len() {
        assert!(apk::parse_manifest(&manifest[..length]).is_err());
    }
    let parsed = apk::parse_manifest(&manifest).unwrap();
    assert_eq!(parsed.package, apk::EXPECTED_PACKAGE);
    assert_eq!(parsed.version_code, 11);
    let mut invalid = manifest.clone();
    invalid[8 + 8..8 + 12].copy_from_slice(&u32::MAX.to_le_bytes());
    assert!(apk::parse_manifest(&invalid).is_err());
}
#[test]
fn origin_validation_and_normalization() {
    assert_eq!(
        config::origin("https://EXAMPLE.com:443/", true).unwrap(),
        "https://example.com"
    );
    assert!(config::origin("http://localhost:3000", true).is_err());
    assert_eq!(
        config::origin("http://localhost:3000", false).unwrap(),
        "http://localhost:3000"
    );
    for value in [
        "https://example.com\\evil",
        "https://a.example/path/..",
        "https://u:p@a.example",
        "https://a.example?q=x",
        "https://a.example#x",
        "https://a.example/\n",
    ] {
        assert!(config::origin(value, true).is_err(), "{value}");
    }
}
