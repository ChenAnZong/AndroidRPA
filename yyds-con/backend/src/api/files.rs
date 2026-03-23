use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Multipart, Path, Query, State};
use axum::http::header;
use axum::response::IntoResponse;
use axum::Json;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use futures_util::future::join_all;

use super::rpc::send_cmd_and_wait;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

/// Max file size for batch upload (20MB — base64 over WebSocket is expensive)
const BATCH_UPLOAD_MAX_BYTES: usize = 20 * 1024 * 1024;

#[derive(serde::Deserialize)]
pub struct FilePathQuery {
    pub path: String,
}

#[derive(serde::Deserialize)]
pub struct MkdirRequest {
    pub path: String,
}

pub async fn list_files(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(query): Query<FilePathQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(query.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_list", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn download_file(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(query): Query<FilePathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(query.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_download", params, 60).await?;

    // result is base64-encoded file content
    let b64 = result
        .as_str()
        .ok_or_else(|| AppError::BadRequest("Invalid response from device".into()))?;
    let bytes = BASE64
        .decode(b64)
        .map_err(|e| AppError::BadRequest(format!("Base64 decode error: {e}")))?;

    let filename = query
        .path
        .rsplit('/')
        .next()
        .unwrap_or("download");
    let disposition = format!("attachment; filename=\"{}\"", filename);

    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_string()),
            (header::CONTENT_DISPOSITION, disposition),
        ],
        bytes,
    ))
}

pub async fn upload_file(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(body): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, AppError> {
    let path = body["path"]
        .as_str()
        .ok_or_else(|| AppError::BadRequest("Missing 'path' field".into()))?;
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(path));
    let result = send_cmd_and_wait(&registry, &imei, "file_upload", params, 60).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn delete_file(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(query): Query<FilePathQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(query.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_delete", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

pub async fn mkdir(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<MkdirRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".to_string(), serde_json::json!(req.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_mkdir", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Batch upload: multipart form with `file`, `path`, `device_ids` (JSON array string).
pub async fn batch_upload_file(
    State(registry): State<Arc<DeviceRegistry>>,
    mut multipart: Multipart,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut file_data: Option<Vec<u8>> = None;
    let mut path: Option<String> = None;
    let mut device_ids: Option<Vec<String>> = None;

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?
    {
        let name = field
            .name()
            .ok_or_else(|| AppError::BadRequest("Missing field name".into()))?
            .to_string();
        let bytes = field
            .bytes()
            .await
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
        match name.as_str() {
            "file" => {
                if bytes.len() > BATCH_UPLOAD_MAX_BYTES {
                    return Err(AppError::BadRequest(format!(
                        "File too large (max {} MB)",
                        BATCH_UPLOAD_MAX_BYTES / (1024 * 1024)
                    )));
                }
                file_data = Some(bytes.to_vec());
            }
            "path" => {
                path = Some(
                    std::str::from_utf8(&bytes)
                        .map_err(|_| AppError::BadRequest("path must be UTF-8".into()))?
                        .trim()
                        .to_string(),
                );
            }
            "device_ids" => {
                let s = std::str::from_utf8(&bytes)
                    .map_err(|_| AppError::BadRequest("device_ids must be UTF-8".into()))?
                    .trim();
                device_ids = Some(
                    serde_json::from_str(s).map_err(|e| AppError::BadRequest(e.to_string()))?,
                );
            }
            _ => {}
        }
    }

    let file_data = file_data.ok_or_else(|| AppError::BadRequest("Missing 'file' field".into()))?;
    let path = path.ok_or_else(|| AppError::BadRequest("Missing 'path' field".into()))?;
    let device_ids = device_ids
        .ok_or_else(|| AppError::BadRequest("Missing 'device_ids' field".into()))?;

    // Encode once, share across all concurrent sends via Arc
    let content_base64: Arc<String> = Arc::new(BASE64.encode(&file_data));
    drop(file_data); // free raw bytes early

    let futs = device_ids.iter().map(|imei| {
        let registry = registry.clone();
        let imei = imei.clone();
        let path = path.clone();
        let content = content_base64.clone();
        async move {
            let mut params = HashMap::new();
            params.insert("path".to_string(), serde_json::json!(path));
            params.insert("content".to_string(), serde_json::json!(content.as_str()));
            match send_cmd_and_wait(&registry, &imei, "file_upload", params, 120).await {
                Ok(_) => serde_json::json!({"imei": imei, "success": true}),
                Err(e) => serde_json::json!({"imei": imei, "success": false, "error": e.to_string()}),
            }
        }
    });
    let results: Vec<_> = join_all(futs).await;
    Ok(Json(serde_json::json!({"results": results})))
}
