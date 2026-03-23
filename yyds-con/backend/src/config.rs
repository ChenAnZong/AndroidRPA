use std::net::SocketAddr;

pub const SERVER_PORT: u16 = 8818;
pub const DATA_DIR: &str = "data";
pub const DB_FILE: &str = "data/yyds.db";
pub const HEARTBEAT_INTERVAL_SECS: u64 = 30;
pub const HEARTBEAT_TIMEOUT_SECS: u64 = 60;
#[allow(dead_code)]
pub const THUMBNAIL_INTERVAL_SECS: u64 = 3;
#[allow(dead_code)]
pub const THUMBNAIL_QUALITY: u32 = 25;
#[allow(dead_code)]
pub const P2P_TIMEOUT_SECS: u64 = 5;
#[allow(dead_code)]
pub const WS_FALLBACK_STREAM_QUALITY: u32 = 70;
#[allow(dead_code)]
pub const WS_FALLBACK_STREAM_INTERVAL_MS: u64 = 80;
pub const SCHEDULE_FILE: &str = "data/schedules.json";
pub const TASK_RUNS_FILE: &str = "data/task_runs.json";
pub const ALERT_CONFIG_FILE: &str = "data/alert_config.json";
pub const MAX_TASK_RUNS: usize = 200;
pub const MAX_CONCURRENT_DEVICES: usize = 20;

/// JWT token expiration: 7 days
pub const JWT_EXPIRATION_SECS: u64 = 7 * 24 * 3600;
/// Device token expiration: 365 days
pub const DEVICE_TOKEN_EXPIRATION_SECS: u64 = 365 * 24 * 3600;

pub fn server_addr() -> SocketAddr {
    SocketAddr::from(([0, 0, 0, 0], SERVER_PORT))
}

/// JWT secret key. Falls back to a random key if not set (tokens won't survive restart).
pub fn jwt_secret() -> String {
    std::env::var("YYDS_JWT_SECRET").unwrap_or_else(|_| "yyds-con-default-secret-change-me".to_string())
}

/// Get the API key from YYDS_API_KEY env var. If not set, auth is disabled (dev mode).
#[allow(dead_code)]
pub fn api_key() -> Option<String> {
    std::env::var("YYDS_API_KEY").ok().filter(|k| !k.is_empty())
}
