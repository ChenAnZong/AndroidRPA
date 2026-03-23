use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, State};
use axum::Json;
use futures_util::future::join_all;

use super::rpc::{send_cmd_and_wait, send_cmd_fire_and_forget};
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

#[derive(serde::Deserialize)]
pub struct TouchRequest {
    pub x: i32,
    pub y: i32,
}

#[derive(serde::Deserialize)]
pub struct SwipeRequest {
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
    #[serde(default = "default_duration")]
    pub duration: i32,
}

fn default_duration() -> i32 {
    300
}

#[derive(serde::Deserialize)]
pub struct ShellRequest {
    pub command: String,
}

#[derive(serde::Deserialize)]
pub struct KeyRequest {
    pub keycode: i32,
}

#[derive(serde::Deserialize)]
pub struct TextRequest {
    pub text: String,
}

#[derive(serde::Deserialize)]
pub struct BatchTouchRequest {
    pub device_ids: Vec<String>,
    pub x: i32,
    pub y: i32,
}

#[derive(serde::Deserialize)]
pub struct BatchShellRequest {
    pub device_ids: Vec<String>,
    pub command: String,
}

#[derive(serde::Deserialize)]
pub struct BatchTextRequest {
    pub device_ids: Vec<String>,
    pub text: String,
}

#[derive(serde::Deserialize)]
pub struct InstallApkRequest {
    pub path: String,
}

#[derive(serde::Deserialize)]
pub struct BatchInstallApkRequest {
    pub device_ids: Vec<String>,
    pub path: String,
}

#[derive(serde::Deserialize)]
pub struct BatchRebootEngineRequest {
    pub device_ids: Vec<String>,
}

#[derive(serde::Deserialize)]
pub struct PasteRequest {
    pub text: String,
}

#[derive(serde::Deserialize)]
pub struct BatchPasteRequest {
    pub device_ids: Vec<String>,
    pub text: String,
}

#[derive(serde::Deserialize)]
pub struct ImeSetRequest {
    pub ime_id: String,
}

#[derive(serde::Deserialize)]
pub struct BatchImeSetRequest {
    pub device_ids: Vec<String>,
    pub ime_id: String,
}

// send_cmd_and_wait and send_cmd_fire_and_forget imported from super::rpc

pub async fn touch(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<TouchRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("x".to_string(), serde_json::json!(req.x));
    params.insert("y".to_string(), serde_json::json!(req.y));

    send_cmd_fire_and_forget(&registry, &imei, "touch", params).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn swipe(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<SwipeRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("x1".to_string(), serde_json::json!(req.x1));
    params.insert("y1".to_string(), serde_json::json!(req.y1));
    params.insert("x2".to_string(), serde_json::json!(req.x2));
    params.insert("y2".to_string(), serde_json::json!(req.y2));
    params.insert("dur".to_string(), serde_json::json!(req.duration));

    send_cmd_fire_and_forget(&registry, &imei, "swipe", params).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn shell(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<ShellRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("cmd".to_string(), serde_json::json!(req.command));

    let result = send_cmd_and_wait(&registry, &imei, "shell", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn key(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<KeyRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("keycode".to_string(), serde_json::json!(req.keycode));

    send_cmd_fire_and_forget(&registry, &imei, "key", params).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn text_input(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<TextRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("text".to_string(), serde_json::json!(req.text));

    send_cmd_fire_and_forget(&registry, &imei, "text", params).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn reboot_engine(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    send_cmd_fire_and_forget(&registry, &imei, "reboot_engine", HashMap::new()).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn batch_touch(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchTouchRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let x = req.x;
        let y = req.y;
        async move {
            let mut params = HashMap::new();
            params.insert("x".to_string(), serde_json::json!(x));
            params.insert("y".to_string(), serde_json::json!(y));
            let ok = send_cmd_fire_and_forget(&registry, &imei, "touch", params).await.is_ok();
            serde_json::json!({"imei": imei, "success": ok})
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

pub async fn batch_shell(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchShellRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let command = req.command.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("cmd".to_string(), serde_json::json!(command));
            match send_cmd_and_wait(&registry, &imei, "shell", params, 30).await {
                Ok(data) => serde_json::json!({"imei": imei, "success": true, "data": data}),
                Err(e) => serde_json::json!({"imei": imei, "success": false, "error": e.to_string()}),
            }
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

pub async fn batch_text(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchTextRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let text = req.text.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("text".to_string(), serde_json::json!(text));
            let ok = send_cmd_fire_and_forget(&registry, &imei, "text", params).await.is_ok();
            serde_json::json!({"imei": imei, "success": ok})
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

pub async fn install_apk(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<InstallApkRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(req.path));

    let result = send_cmd_and_wait(&registry, &imei, "install_apk", params, 60).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn batch_install_apk(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchInstallApkRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let path = req.path.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("path".to_string(), serde_json::json!(path));
            match send_cmd_and_wait(&registry, &imei, "install_apk", params, 60).await {
                Ok(data) => serde_json::json!({"imei": imei, "success": true, "data": data}),
                Err(e) => serde_json::json!({"imei": imei, "success": false, "error": e.to_string()}),
            }
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

pub async fn batch_reboot_engine(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchRebootEngineRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        async move {
            let ok = send_cmd_fire_and_forget(&registry, &imei, "reboot_engine", HashMap::new()).await.is_ok();
            serde_json::json!({"imei": imei, "success": ok})
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

// ── Paste ──

pub async fn paste(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<PasteRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("text".to_string(), serde_json::json!(req.text));
    let result = send_cmd_and_wait(&registry, &imei, "paste", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn batch_paste(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchPasteRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let text = req.text.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("text".to_string(), serde_json::json!(text));
            match send_cmd_and_wait(&registry, &imei, "paste", params, 10).await {
                Ok(_) => serde_json::json!({"imei": imei, "success": true}),
                Err(e) => serde_json::json!({"imei": imei, "success": false, "error": e.to_string()}),
            }
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}

// ── IME ──

pub async fn ime_get(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "ime_get", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn ime_list(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "ime_list", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn ime_set(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<ImeSetRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("ime_id".to_string(), serde_json::json!(req.ime_id));
    let result = send_cmd_and_wait(&registry, &imei, "ime_set", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn batch_ime_set(
    State(registry): State<Arc<DeviceRegistry>>,
    Json(req): Json<BatchImeSetRequest>,
) -> Json<serde_json::Value> {
    let futs = req.device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let ime_id = req.ime_id.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("ime_id".to_string(), serde_json::json!(ime_id));
            match send_cmd_and_wait(&registry, &imei, "ime_set", params, 10).await {
                Ok(_) => serde_json::json!({"imei": imei, "success": true}),
                Err(e) => serde_json::json!({"imei": imei, "success": false, "error": e.to_string()}),
            }
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Json(serde_json::json!({"results": results}))
}
