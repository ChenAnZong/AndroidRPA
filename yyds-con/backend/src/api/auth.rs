use std::sync::Arc;

use axum::extract::{ConnectInfo, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::{Deserialize, Serialize};

use crate::auth::db::Db;
use crate::auth::jwt;
use crate::auth::rate_limit::RateLimiter;

#[derive(Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
}

#[derive(Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub user: UserInfo,
}

#[derive(Serialize, Clone)]
pub struct UserInfo {
    pub id: i64,
    pub username: String,
    pub role: String,
    pub created_at: i64,
}

/// Validate username: 2-32 chars, alphanumeric + underscore only
fn validate_username(username: &str) -> Result<(), (StatusCode, Json<serde_json::Value>)> {
    if username.len() < 2 || username.len() > 32 {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "用户名长度需要2-32个字符"})),
        ));
    }
    if !username
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_')
    {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "用户名只能包含字母、数字和下划线"})),
        ));
    }
    Ok(())
}

pub async fn login(
    State(db): State<Db>,
    State(limiter): State<Arc<RateLimiter>>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    Json(req): Json<LoginRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    let ip = addr.ip().to_string();

    // Rate limit check
    if let Err(remaining) = limiter.check(&ip).await {
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({
                "error": format!("登录尝试过于频繁，请{}秒后重试", remaining),
                "retry_after": remaining,
            })),
        ));
    }

    // Phase 1: query user (hold lock briefly)
    let (id, username, hashed, role, enabled, created_at) = {
        let db = db.lock().await;
        db.query_row(
            "SELECT id, username, password, role, enabled, created_at FROM users WHERE username = ?1",
            rusqlite::params![&req.username],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, bool>(4)?,
                    row.get::<_, i64>(5)?,
                ))
            },
        )
        .map_err(|_| {
            (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({"error": "用户名或密码错误"})),
            )
        })?
    };
    // Lock released here

    // Phase 2: bcrypt verify off async runtime (no lock held)
    let password = req.password.clone();
    let hashed_clone = hashed.clone();
    let valid = tokio::task::spawn_blocking(move || {
        bcrypt::verify(&password, &hashed_clone).unwrap_or(false)
    })
    .await
    .unwrap_or(false);

    if !valid {
        // Record failed attempt
        limiter.record_attempt(&ip).await;
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "用户名或密码错误"})),
        ));
    }

    // Check if account is disabled
    if !enabled {
        return Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "账号已被禁用，请联系管理员"})),
        ));
    }

    // Clear rate limit on success
    limiter.clear(&ip).await;

    // Phase 3: update last_login (re-acquire lock briefly)
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    {
        let db = db.lock().await;
        let _ = db.execute(
            "UPDATE users SET last_login = ?1 WHERE id = ?2",
            rusqlite::params![now, id],
        );
    }

    let token = jwt::create_token(id, &username, &role).map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": "Token generation failed"})),
        )
    })?;

    Ok(Json(AuthResponse {
        token,
        user: UserInfo {
            id,
            username,
            role,
            created_at,
        },
    }))
}

pub async fn register(
    State(db): State<Db>,
    Json(req): Json<RegisterRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    validate_username(&req.username)?;

    if req.password.len() < 6 {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "密码至少6个字符"})),
        ));
    }

    // bcrypt hash off the async runtime (no lock held)
    let password = req.password.clone();
    let hashed = tokio::task::spawn_blocking(move || bcrypt::hash(&password, 10))
        .await
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Password hashing failed"})),
            )
        })?
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Password hashing failed"})),
            )
        })?;

    // Acquire lock only for the INSERT
    let db = db.lock().await;
    let result = db.execute(
        "INSERT INTO users (username, password, role) VALUES (?1, ?2, 'user')",
        rusqlite::params![&req.username, &hashed],
    );

    match result {
        Ok(_) => {
            let id = db.last_insert_rowid();
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as i64;

            let token = jwt::create_token(id, &req.username, "user").map_err(|_| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(serde_json::json!({"error": "Token generation failed"})),
                )
            })?;

            Ok((
                StatusCode::CREATED,
                Json(AuthResponse {
                    token,
                    user: UserInfo {
                        id,
                        username: req.username,
                        role: "user".to_string(),
                        created_at: now,
                    },
                }),
            ))
        }
        Err(_) => Err((
            StatusCode::CONFLICT,
            Json(serde_json::json!({"error": "用户名已存在"})),
        )),
    }
}

pub async fn me(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
) -> Result<Json<UserInfo>, (StatusCode, Json<serde_json::Value>)> {
    let db = db.lock().await;
    let (username, role, created_at) = db
        .query_row(
            "SELECT username, role, created_at FROM users WHERE id = ?1",
            rusqlite::params![claims.sub],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            },
        )
        .map_err(|_| {
            (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({"error": "用户不存在"})),
            )
        })?;

    Ok(Json(UserInfo {
        id: claims.sub,
        username,
        role,
        created_at,
    }))
}

/// Bind a device to the current user, returns a device token
#[derive(Deserialize)]
pub struct BindDeviceRequest {
    pub imei: String,
    #[serde(default)]
    pub alias: String,
}

#[derive(Serialize)]
pub struct BindDeviceResponse {
    pub device_token: String,
    pub imei: String,
}

pub async fn bind_device(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    Json(req): Json<BindDeviceRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    if req.imei.trim().is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "IMEI不能为空"})),
        ));
    }

    // Create device token (no lock needed)
    let device_token = jwt::create_device_token(claims.sub, &req.imei).map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": "Token generation failed"})),
        )
    })?;

    let db = db.lock().await;

    // Insert binding (ignore if already exists)
    let _ = db.execute(
        "INSERT OR IGNORE INTO device_bindings (user_id, imei, alias) VALUES (?1, ?2, ?3)",
        rusqlite::params![claims.sub, &req.imei, &req.alias],
    );

    // Remove old device tokens for this user+imei to prevent unbounded accumulation
    let _ = db.execute(
        "DELETE FROM device_tokens WHERE user_id = ?1 AND imei = ?2",
        rusqlite::params![claims.sub, &req.imei],
    );

    // Store new device token
    let _ = db.execute(
        "INSERT INTO device_tokens (user_id, imei, token) VALUES (?1, ?2, ?3)",
        rusqlite::params![claims.sub, &req.imei, &device_token],
    );

    Ok(Json(BindDeviceResponse {
        device_token,
        imei: req.imei,
    }))
}

pub async fn unbind_device(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    Json(req): Json<BindDeviceRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    let db = db.lock().await;

    db.execute(
        "DELETE FROM device_bindings WHERE user_id = ?1 AND imei = ?2",
        rusqlite::params![claims.sub, &req.imei],
    )
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": "Database error"})),
        )
    })?;

    // Also remove device tokens
    let _ = db.execute(
        "DELETE FROM device_tokens WHERE user_id = ?1 AND imei = ?2",
        rusqlite::params![claims.sub, &req.imei],
    );

    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn my_devices(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
) -> Json<serde_json::Value> {
    let db = db.lock().await;

    let devices: Vec<serde_json::Value> = db
        .prepare("SELECT imei, alias, bound_at FROM device_bindings WHERE user_id = ?1")
        .and_then(|mut stmt| {
            stmt.query_map(rusqlite::params![claims.sub], |row| {
                Ok(serde_json::json!({
                    "imei": row.get::<_, String>(0)?,
                    "alias": row.get::<_, String>(1)?,
                    "bound_at": row.get::<_, i64>(2)?,
                }))
            })
            .map(|rows| rows.filter_map(|r| r.ok()).collect())
        })
        .unwrap_or_else(|e| {
            tracing::error!("DB error in my_devices: {e}");
            vec![]
        });

    Json(serde_json::json!({"devices": devices}))
}

pub async fn change_password(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    Json(req): Json<ChangePasswordRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    if req.new_password.len() < 6 {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "新密码至少6个字符"})),
        ));
    }

    // Phase 1: query current password hash (hold lock briefly)
    let hashed = {
        let db = db.lock().await;
        db.query_row(
            "SELECT password FROM users WHERE id = ?1",
            rusqlite::params![claims.sub],
            |row| row.get::<_, String>(0),
        )
        .map_err(|_| {
            (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({"error": "用户不存在"})),
            )
        })?
    };
    // Lock released

    // Phase 2: bcrypt verify + hash off async runtime (no lock held)
    let old_password = req.old_password.clone();
    let hashed_clone = hashed.clone();
    let valid = tokio::task::spawn_blocking(move || {
        bcrypt::verify(&old_password, &hashed_clone).unwrap_or(false)
    })
    .await
    .unwrap_or(false);

    if !valid {
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "原密码错误"})),
        ));
    }

    let new_password = req.new_password.clone();
    let new_hashed = tokio::task::spawn_blocking(move || bcrypt::hash(&new_password, 10))
        .await
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Password hashing failed"})),
            )
        })?
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Password hashing failed"})),
            )
        })?;

    // Phase 3: update password (re-acquire lock briefly)
    {
        let db = db.lock().await;
        db.execute(
            "UPDATE users SET password = ?1 WHERE id = ?2",
            rusqlite::params![&new_hashed, claims.sub],
        )
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Database error"})),
            )
        })?;
    }

    Ok(Json(serde_json::json!({"success": true})))
}

#[derive(Deserialize)]
pub struct ChangePasswordRequest {
    pub old_password: String,
    pub new_password: String,
}
