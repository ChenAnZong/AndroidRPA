use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;

use super::rpc::send_cmd_and_wait;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

#[derive(serde::Deserialize, Default)]
pub struct LogcatDumpQuery {
    #[serde(default = "default_level")]
    pub level: String,
    pub pid: Option<String>,
    pub tag: Option<String>,
    #[serde(default = "default_format")]
    pub format: String,
    #[serde(default)]
    pub lines: u32,
    pub since: Option<String>,
}

fn default_level() -> String {
    "V".to_string()
}
fn default_format() -> String {
    "threadtime".to_string()
}

#[derive(serde::Deserialize)]
pub struct LogcatClearRequest {
    #[serde(default = "default_buffer")]
    pub buffer: String,
}

fn default_buffer() -> String {
    "all".to_string()
}

/// Build a logcat command string from parameters (mirrors the Kotlin buildLogcatCommand)
fn build_logcat_command(dump: bool, q: &LogcatDumpQuery) -> String {
    let mut parts = vec!["logcat".to_string()];
    if dump {
        parts.push("-d".to_string());
    }
    parts.push("-v".to_string());
    parts.push(q.format.clone());

    if let Some(ref pid) = q.pid {
        let pid = pid.trim();
        if !pid.is_empty() {
            parts.push(format!("--pid={}", pid));
        }
    }
    if q.lines > 0 {
        parts.push("-t".to_string());
        parts.push(q.lines.to_string());
    }
    if let Some(ref since) = q.since {
        let since = since.trim();
        if !since.is_empty() {
            parts.push("-T".to_string());
            parts.push(format!("'{}'", since));
        }
    }

    let safe_level = if q.level.len() == 1 && "VDIWEF".contains(&q.level) {
        q.level.clone()
    } else {
        "V".to_string()
    };

    if let Some(ref tag) = q.tag {
        let safe_tag: String = tag
            .trim()
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '.' || *c == '*' || *c == '-')
            .collect();
        if !safe_tag.is_empty() {
            parts.push("-s".to_string());
            parts.push(format!("{}:{}", safe_tag, safe_level));
        }
    } else if safe_level != "V" {
        parts.push(format!("*:{}", safe_level));
    }

    parts.join(" ")
}

/// GET /api/devices/{imei}/logcat/dump?level=&pid=&tag=&format=&lines=&since=
pub async fn logcat_dump(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<LogcatDumpQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let cmd = build_logcat_command(true, &q);

    let mut params = HashMap::new();
    params.insert("cmd".to_string(), serde_json::json!(cmd));

    let result = send_cmd_and_wait(&registry, &imei, "shell", params, 30).await?;

    // send_cmd_and_wait returns the "data" field directly from DeviceMessage::Response,
    // which is already the shell output string (Value::String), not a wrapper object.
    let data = result.as_str().unwrap_or("");

    let line_count = data.lines().filter(|l| !l.trim().is_empty()).count();

    Ok(Json(serde_json::json!({
        "success": true,
        "data": data,
        "lineCount": line_count,
        "command": cmd
    })))
}

/// POST /api/devices/{imei}/logcat/clear
pub async fn logcat_clear(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<LogcatClearRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let cmd = match req.buffer.as_str() {
        "main" => "logcat -b main -c",
        "system" => "logcat -b system -c",
        "crash" => "logcat -b crash -c",
        "events" => "logcat -b events -c",
        _ => "logcat -c",
    };

    let mut params = HashMap::new();
    params.insert("cmd".to_string(), serde_json::json!(cmd));

    send_cmd_and_wait(&registry, &imei, "shell", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true})))
}

/// GET /api/devices/{imei}/logcat/buffers
pub async fn logcat_buffers(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("cmd".to_string(), serde_json::json!("logcat -g 2>&1"));

    let result = send_cmd_and_wait(&registry, &imei, "shell", params, 10).await?;
    let data = result.as_str().unwrap_or("");

    Ok(Json(serde_json::json!({
        "success": true,
        "data": data
    })))
}
