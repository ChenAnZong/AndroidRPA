use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::header;
use axum::response::IntoResponse;
use axum::Json;

use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

pub async fn list_devices(
    State(registry): State<Arc<DeviceRegistry>>,
) -> Json<serde_json::Value> {
    let devices = registry.list_devices();
    Json(serde_json::json!({
        "devices": devices,
        "total": registry.device_count(),
        "online": registry.online_count(),
    }))
}

pub async fn get_device(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let device = registry
        .devices
        .get(&imei)
        .ok_or_else(|| AppError::DeviceNotFound(imei.clone()))?;

    let info = &device.info;
    let viewers = device
        .viewer_count
        .load(std::sync::atomic::Ordering::Relaxed);

    Ok(Json(serde_json::json!({
        "imei": info.imei,
        "model": info.model,
        "screen_width": info.screen_width,
        "screen_height": info.screen_height,
        "version": info.version,
        "online": info.online,
        "connected_at": info.connected_at.timestamp_millis(),
        "last_seen": info.last_seen.timestamp_millis(),
        "running_project": info.running_project,
        "foreground_app": info.foreground_app,
        "stream_viewers": viewers,
    })))
}

pub async fn get_thumbnail(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    let thumb_lock = registry
        .get_thumbnail(&imei)
        .ok_or_else(|| AppError::DeviceNotFound(imei.clone()))?;

    let thumb = thumb_lock.read().await;
    match thumb.as_ref() {
        Some(bytes) => Ok((
            [(header::CONTENT_TYPE, "image/jpeg")],
            bytes.clone(),
        )
            .into_response()),
        None => Ok((
            [(header::CONTENT_TYPE, "image/jpeg")],
            bytes::Bytes::new(),
        )
            .into_response()),
    }
}
