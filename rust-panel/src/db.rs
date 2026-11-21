use crate::error::Result;
use rusqlite::Connection;
use std::path::Path;
pub fn open(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS admins (
            id INTEGER PRIMARY KEY CHECK(id=1), username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions (
            token_hash TEXT PRIMARY KEY, csrf TEXT NOT NULL, expires_at INTEGER NOT NULL, admin_id INTEGER NOT NULL REFERENCES admins(id)
        );
        CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
        CREATE TABLE IF NOT EXISTS releases (
            version_code INTEGER PRIMARY KEY, version_name TEXT NOT NULL, filename TEXT UNIQUE NOT NULL,
            sha256 TEXT NOT NULL, notes TEXT NOT NULL, source_url TEXT NOT NULL,
            package_name TEXT NOT NULL, size_bytes INTEGER NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('draft','published','withdrawn')),
            created_at INTEGER NOT NULL, published_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, active INTEGER NOT NULL CHECK(active IN (0,1)),
            created_at INTEGER NOT NULL, expires_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS config (
            id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, default_server_id TEXT NOT NULL, servers_json TEXT NOT NULL
        );
        INSERT OR IGNORE INTO config(id, revision, default_server_id, servers_json)
        VALUES(1, 1, 'primary', '[{\"id\":\"primary\",\"name\":\"Primary server\",\"url\":\"https://protect.securemy.network:443\"}]');")?;
    Ok(conn)
}
