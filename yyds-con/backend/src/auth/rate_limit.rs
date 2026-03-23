use std::sync::Arc;

use rusqlite::params;

use super::db::Db;

/// Max login attempts per IP within the time window
const MAX_ATTEMPTS: i64 = 5;
/// Lockout duration in seconds (15 minutes)
const LOCKOUT_SECS: i64 = 900;

pub struct RateLimiter {
    db: Db,
}

impl RateLimiter {
    pub fn new(db: Db) -> Arc<Self> {
        Arc::new(Self { db })
    }

    /// Record a failed login attempt from the given IP.
    pub async fn record_attempt(&self, ip: &str) {
        let db = self.db.lock().await;
        let _ = db.execute(
            "INSERT INTO login_attempts (ip, attempted_at) VALUES (?1, strftime('%s','now'))",
            params![ip],
        );
    }

    /// Check if the IP is currently rate-limited.
    /// Returns Ok(()) if allowed, Err(remaining_seconds) if blocked.
    pub async fn check(&self, ip: &str) -> Result<(), i64> {
        let db = self.db.lock().await;
        let count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM login_attempts
                 WHERE ip = ?1 AND attempted_at > (strftime('%s','now') - ?2)",
                params![ip, LOCKOUT_SECS],
                |row| row.get(0),
            )
            .unwrap_or(0);

        if count >= MAX_ATTEMPTS {
            let oldest: i64 = db
                .query_row(
                    "SELECT MIN(attempted_at) FROM (
                        SELECT attempted_at FROM login_attempts
                        WHERE ip = ?1 AND attempted_at > (strftime('%s','now') - ?2)
                        ORDER BY attempted_at DESC LIMIT ?3
                    )",
                    params![ip, LOCKOUT_SECS, MAX_ATTEMPTS],
                    |row| row.get(0),
                )
                .unwrap_or(0);

            let now: i64 = db
                .query_row("SELECT strftime('%s','now')", [], |row| row.get(0))
                .unwrap_or(0);

            let remaining = LOCKOUT_SECS - (now - oldest);
            if remaining > 0 {
                return Err(remaining);
            }
        }
        Ok(())
    }

    /// Clear attempts for an IP after successful login.
    pub async fn clear(&self, ip: &str) {
        let db = self.db.lock().await;
        let _ = db.execute("DELETE FROM login_attempts WHERE ip = ?1", params![ip]);
    }

    /// Periodic cleanup of old records (call from a background task).
    pub async fn cleanup(&self) {
        let db = self.db.lock().await;
        let _ = db.execute(
            "DELETE FROM login_attempts WHERE attempted_at < (strftime('%s','now') - ?1)",
            params![LOCKOUT_SECS * 2],
        );
    }
}
