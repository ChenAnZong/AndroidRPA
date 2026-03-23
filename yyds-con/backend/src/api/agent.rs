use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;

use super::rpc::send_cmd_and_wait;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

#[derive(serde::Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_limit")]
    pub limit: u32,
    #[serde(default)]
    pub offset: u32,
}

fn default_limit() -> u32 {
    20
}

#[derive(serde::Deserialize)]
pub struct ProviderQuery {
    #[serde(default)]
    pub provider: String,
}

/// GET /api/devices/{imei}/agent/status
pub async fn agent_status(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "agent_status", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// GET /api/devices/{imei}/agent/history
pub async fn agent_history(
    Path(imei): Path<String>,
    Query(q): Query<PaginationQuery>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("limit".to_string(), serde_json::json!(q.limit));
    params.insert("offset".to_string(), serde_json::json!(q.offset));
    let result = send_cmd_and_wait(&registry, &imei, "agent_history", params, 10).await?;
    Ok(Json(result))
}

/// GET /api/devices/{imei}/agent/history/{run_id}
pub async fn agent_history_detail(
    Path((imei, run_id)): Path<(String, String)>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("run_id".to_string(), serde_json::json!(run_id));
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_history_detail", params, 10).await?;
    Ok(Json(result))
}

/// DELETE /api/devices/{imei}/agent/history
pub async fn agent_history_clear(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_history_clear", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// POST /api/devices/{imei}/agent/run
pub async fn agent_run(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
    Json(body): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, AppError> {
    let instruction = body
        .get("instruction")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let mut params = HashMap::new();
    params.insert("instruction".to_string(), serde_json::json!(instruction));
    let result = send_cmd_and_wait(&registry, &imei, "agent_run", params, 8).await?;
    Ok(Json(result))
}

/// POST /api/devices/{imei}/agent/stop
pub async fn agent_stop(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "agent_stop", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// GET /api/devices/{imei}/agent/config
pub async fn agent_config_get(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_config_get", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// PUT /api/devices/{imei}/agent/config
pub async fn agent_config_set(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
    Json(body): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    if let Some(obj) = body.as_object() {
        for (k, v) in obj {
            params.insert(k.clone(), v.clone());
        }
    }
    let result = send_cmd_and_wait(&registry, &imei, "agent_config_set", params, 10).await?;
    Ok(Json(result))
}

/// GET /api/devices/{imei}/agent/providers
pub async fn agent_providers(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_providers", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// GET /api/devices/{imei}/agent/models?provider=xx
pub async fn agent_models(
    Path(imei): Path<String>,
    Query(q): Query<ProviderQuery>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("provider".to_string(), serde_json::json!(q.provider));
    let result = send_cmd_and_wait(&registry, &imei, "agent_models", params, 10).await?;
    Ok(Json(result))
}

/// POST /api/devices/{imei}/agent/takeover
pub async fn agent_takeover(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_takeover", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// POST /api/devices/{imei}/agent/resume
pub async fn agent_resume(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_resume", HashMap::new(), 10).await?;
    Ok(Json(result))
}

/// POST /api/devices/{imei}/agent/test-connection
pub async fn agent_test_connection(
    Path(imei): Path<String>,
    State(registry): State<Arc<DeviceRegistry>>,
    Json(body): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    if let Some(obj) = body.as_object() {
        for (k, v) in obj {
            params.insert(k.clone(), v.clone());
        }
    }
    let result =
        send_cmd_and_wait(&registry, &imei, "agent_test_connection", params, 30).await?;
    Ok(Json(result))
}
