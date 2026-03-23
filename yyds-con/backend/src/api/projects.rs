use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, State};
use axum::Json;

use super::rpc::send_cmd_and_wait;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

#[derive(serde::Deserialize)]
pub struct StartProjectRequest {
    pub name: String,
}

#[derive(serde::Deserialize)]
pub struct BatchStartRequest {
    pub device_ids: Vec<String>,
    pub name: String,
}

#[derive(serde::Deserialize)]
pub struct BatchStopRequest {
    pub device_ids: Vec<String>,
}

pub async fn list_projects(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "project_list", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn start_project(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<StartProjectRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".to_string(), serde_json::json!(req.name));
    let result = send_cmd_and_wait(&registry, &imei, "start_project", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn stop_project(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "stop_project", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn project_status(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result =
        send_cmd_and_wait(&registry, &imei, "project_status", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn batch_start(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchStartRequest>,
) -> Json<serde_json::Value> {
    let mut results = Vec::new();
    for imei in &req.device_ids {
        let mut params = HashMap::new();
        params.insert("name".to_string(), serde_json::json!(req.name));
        let result = send_cmd_and_wait(&registry, imei, "start_project", params, 10).await;
        match result {
            Ok(data) => {
                results.push(serde_json::json!({"imei": imei, "success": true, "data": data}))
            }
            Err(e) => results
                .push(serde_json::json!({"imei": imei, "success": false, "error": e.to_string()})),
        }
    }
    Json(serde_json::json!({"results": results}))
}

pub async fn batch_stop(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchStopRequest>,
) -> Json<serde_json::Value> {
    let mut results = Vec::new();
    for imei in &req.device_ids {
        let result =
            send_cmd_and_wait(&registry, imei, "stop_project", HashMap::new(), 10).await;
        match result {
            Ok(data) => {
                results.push(serde_json::json!({"imei": imei, "success": true, "data": data}))
            }
            Err(e) => results
                .push(serde_json::json!({"imei": imei, "success": false, "error": e.to_string()})),
        }
    }
    Json(serde_json::json!({"results": results}))
}
