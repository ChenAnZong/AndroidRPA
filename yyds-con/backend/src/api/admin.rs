use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::auth::db::Db;
use crate::auth::jwt;
use crate::device::registry::DeviceRegistry;

/// Require admin role — verify against DB to prevent stale JWT bypass
async fn require_admin(
    claims: &jwt::Claims,
    db: &Db,
) -> Result<(), (StatusCode, Json<serde_json::Value>)> {
    if claims.role != "admin" {
        return Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "需要管理员权限"})),
        ));
    }
    // Double-check against DB: user may have been demoted since JWT was issued
    let conn = db.lock().await;
    let current_role: String = conn
        .query_row(
            "SELECT role FROM users WHERE id = ?1",
            rusqlite::params![claims.sub],
            |row| row.get(0),
        )
        .map_err(|_| {
            (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({"error": "用户不存在"})),
            )
        })?;
    drop(conn); // Release lock immediately
    if current_role != "admin" {
        return Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": "管理员权限已被撤销，请重新登录"})),
        ));
    }
    Ok(())
}

/// List all users
pub async fn list_users(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;
    let db = db.lock().await;

    let mut stmt = db
        .prepare("SELECT id, username, role, enabled, max_devices, created_at, last_login FROM users ORDER BY id")
        .map_err(|e| {
            tracing::error!("DB prepare error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?;

    let users: Vec<serde_json::Value> = stmt
        .query_map([], |row| {
            Ok(serde_json::json!({
                "id": row.get::<_, i64>(0)?,
                "username": row.get::<_, String>(1)?,
                "role": row.get::<_, String>(2)?,
                "enabled": row.get::<_, bool>(3)?,
                "max_devices": row.get::<_, i64>(4)?,
                "created_at": row.get::<_, i64>(5)?,
                "last_login": row.get::<_, Option<i64>>(6)?,
            }))
        })
        .map_err(|e| {
            tracing::error!("DB query error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?
        .filter_map(|r| r.ok())
        .collect();

    Ok(Json(serde_json::json!({"users": users, "total": users.len()})))
}

/// Get user detail with bound devices
pub async fn get_user(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    axum::extract::Path(user_id): axum::extract::Path<i64>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;
    let db = db.lock().await;

    let user = db
        .query_row(
            "SELECT id, username, role, enabled, max_devices, created_at, last_login FROM users WHERE id = ?1",
            rusqlite::params![user_id],
            |row| {
                Ok(serde_json::json!({
                    "id": row.get::<_, i64>(0)?,
                    "username": row.get::<_, String>(1)?,
                    "role": row.get::<_, String>(2)?,
                    "enabled": row.get::<_, bool>(3)?,
                    "max_devices": row.get::<_, i64>(4)?,
                    "created_at": row.get::<_, i64>(5)?,
                    "last_login": row.get::<_, Option<i64>>(6)?,
                }))
            },
        )
        .map_err(|_| {
            (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({"error": "用户不存在"})),
            )
        })?;

    let mut stmt = db
        .prepare("SELECT imei, alias, bound_at FROM device_bindings WHERE user_id = ?1")
        .map_err(|e| {
            tracing::error!("DB prepare error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?;
    let devices: Vec<serde_json::Value> = stmt
        .query_map(rusqlite::params![user_id], |row| {
            Ok(serde_json::json!({
                "imei": row.get::<_, String>(0)?,
                "alias": row.get::<_, String>(1)?,
                "bound_at": row.get::<_, i64>(2)?,
            }))
        })
        .map_err(|e| {
            tracing::error!("DB query error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?
        .filter_map(|r| r.ok())
        .collect();

    Ok(Json(serde_json::json!({
        "user": user,
        "devices": devices,
    })))
}

/// Delete a user (admin only, cannot delete self)
pub async fn delete_user(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    axum::extract::Path(user_id): axum::extract::Path<i64>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;

    if claims.sub == user_id {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "不能删除自己"})),
        ));
    }

    let db = db.lock().await;
    let affected = db
        .execute("DELETE FROM users WHERE id = ?1", rusqlite::params![user_id])
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Database error"})),
            )
        })?;

    if affected == 0 {
        return Err((
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "用户不存在"})),
        ));
    }

    Ok(Json(serde_json::json!({"success": true})))
}

/// Update user role
#[derive(Deserialize)]
pub struct UpdateUserRequest {
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub enabled: Option<bool>,
    #[serde(default)]
    pub max_devices: Option<i64>,
}

pub async fn update_user(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
    axum::extract::Path(user_id): axum::extract::Path<i64>,
    Json(req): Json<UpdateUserRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;

    if let Some(ref role) = req.role {
        if role != "admin" && role != "user" {
            return Err((
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "角色只能是 admin 或 user"})),
            ));
        }
    }

    // bcrypt hash off async runtime, before acquiring lock
    let new_hashed = if let Some(ref password) = req.password {
        if password.len() < 6 {
            return Err((
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "密码至少6个字符"})),
            ));
        }
        let pw = password.clone();
        let hashed = tokio::task::spawn_blocking(move || bcrypt::hash(&pw, 10))
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
        Some(hashed)
    } else {
        None
    };

    // Acquire lock only for DB writes
    let db = db.lock().await;

    if let Some(ref role) = req.role {
        db.execute(
            "UPDATE users SET role = ?1 WHERE id = ?2",
            rusqlite::params![role, user_id],
        )
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Database error"})),
            )
        })?;
    }

    if let Some(ref hashed) = new_hashed {
        db.execute(
            "UPDATE users SET password = ?1 WHERE id = ?2",
            rusqlite::params![hashed, user_id],
        )
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Database error"})),
            )
        })?;
    }

    if let Some(enabled) = req.enabled {
        db.execute(
            "UPDATE users SET enabled = ?1 WHERE id = ?2",
            rusqlite::params![enabled, user_id],
        )
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": "Database error"})),
            )
        })?;
    }

    if let Some(max_devices) = req.max_devices {
        if max_devices < 0 || max_devices > 9999 {
            return Err((
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "最大设备数范围: 0-9999"})),
            ));
        }
        db.execute(
            "UPDATE users SET max_devices = ?1 WHERE id = ?2",
            rusqlite::params![max_devices, user_id],
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

/// Admin: list all device bindings
pub async fn list_all_bindings(
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;
    let db = db.lock().await;

    let mut stmt = db
        .prepare(
            "SELECT b.id, b.user_id, u.username, b.imei, b.alias, b.bound_at
             FROM device_bindings b JOIN users u ON b.user_id = u.id
             ORDER BY b.bound_at DESC",
        )
        .map_err(|e| {
            tracing::error!("DB prepare error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?;

    let bindings: Vec<serde_json::Value> = stmt
        .query_map([], |row| {
            Ok(serde_json::json!({
                "id": row.get::<_, i64>(0)?,
                "user_id": row.get::<_, i64>(1)?,
                "username": row.get::<_, String>(2)?,
                "imei": row.get::<_, String>(3)?,
                "alias": row.get::<_, String>(4)?,
                "bound_at": row.get::<_, i64>(5)?,
            }))
        })
        .map_err(|e| {
            tracing::error!("DB query error: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": "Database error"})))
        })?
        .filter_map(|r| r.ok())
        .collect();

    Ok(Json(serde_json::json!({"bindings": bindings, "total": bindings.len()})))
}

/// Admin dashboard stats
pub async fn admin_stats(
    State(registry): State<Arc<DeviceRegistry>>,
    State(db): State<Db>,
    claims: axum::Extension<jwt::Claims>,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    require_admin(&claims, &db).await?;
    let db = db.lock().await;

    let user_count: i64 = db
        .query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))
        .unwrap_or(0);
    let binding_count: i64 = db
        .query_row("SELECT COUNT(*) FROM device_bindings", [], |row| row.get(0))
        .unwrap_or(0);

    Ok(Json(serde_json::json!({
        "users": user_count,
        "device_bindings": binding_count,
        "devices_total": registry.device_count(),
        "devices_online": registry.online_count(),
    })))
}
