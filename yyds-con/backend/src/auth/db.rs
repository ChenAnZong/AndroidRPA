use std::sync::Arc;

use rusqlite::Connection;
use tokio::sync::Mutex;

use crate::config;

pub type Db = Arc<Mutex<Connection>>;

/// Initialize SQLite database and create tables
pub fn init_db() -> Db {
    // Ensure data dir exists
    std::fs::create_dir_all(config::DATA_DIR).ok();

    let conn = Connection::open(config::DB_FILE).expect("Failed to open database");

    conn.execute_batch(
        "
        PRAGMA journal_mode = WAL;
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS users (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            username    TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password    TEXT NOT NULL,
            role        TEXT NOT NULL DEFAULT 'user',
            enabled     INTEGER NOT NULL DEFAULT 1,
            max_devices INTEGER NOT NULL DEFAULT 30,
            created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now')),
            last_login  INTEGER
        );

        CREATE TABLE IF NOT EXISTS device_bindings (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     INTEGER NOT NULL,
            imei        TEXT NOT NULL,
            alias       TEXT NOT NULL DEFAULT '',
            bound_at    INTEGER NOT NULL DEFAULT (strftime('%s','now')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            UNIQUE(user_id, imei)
        );

        CREATE TABLE IF NOT EXISTS device_tokens (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     INTEGER NOT NULL,
            imei        TEXT NOT NULL,
            token       TEXT NOT NULL UNIQUE,
            created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now')),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS login_attempts (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            ip          TEXT NOT NULL,
            attempted_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
        );
        CREATE INDEX IF NOT EXISTS idx_login_attempts_ip_time ON login_attempts(ip, attempted_at);
        ",
    )
    .expect("Failed to create tables");

    // Migrate: add enabled/max_devices columns if missing
    if conn.prepare("SELECT enabled FROM users LIMIT 0").is_err() {
        conn.execute_batch(
            "ALTER TABLE users ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;
             ALTER TABLE users ADD COLUMN max_devices INTEGER NOT NULL DEFAULT 30;",
        ).ok();
    }

    // Create default admin if no users exist
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))
        .unwrap_or(0);

    if count == 0 {
        let hashed = bcrypt::hash("admin123", 10).expect("Failed to hash password");
        conn.execute(
            "INSERT INTO users (username, password, role) VALUES (?1, ?2, 'admin')",
            rusqlite::params![&"admin", &hashed],
        )
        .expect("Failed to create default admin");
        tracing::info!("Created default admin user: admin / admin123");
    }

    Arc::new(Mutex::new(conn))
}
