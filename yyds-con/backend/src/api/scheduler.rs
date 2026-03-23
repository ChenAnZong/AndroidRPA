use std::sync::Arc;

use axum::extract::{Path, State};
use axum::Json;
use tokio::sync::RwLock;

use crate::error::AppError;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct Schedule {
    pub id: String,
    pub name: String,
    pub cron_expr: String,
    pub action: String,
    #[serde(default)]
    pub params: serde_json::Value,
    pub device_ids: Vec<String>,
    pub enabled: bool,
    #[serde(default)]
    pub last_run: Option<i64>,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub alert: Option<AlertConfig>,
    /// Phase 4: batch execution settings
    #[serde(default)]
    pub batch_size: Option<usize>,
    #[serde(default)]
    pub batch_delay_ms: Option<u64>,
    #[serde(default)]
    pub timeout_secs: Option<u64>,
    #[serde(default)]
    pub retry_count: Option<u8>,
    /// last run status summary for UI quick display
    #[serde(default)]
    pub last_run_status: Option<String>,
}

/// Alert configuration attached to a Schedule
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct AlertConfig {
    /// Fire alert if any device fails
    #[serde(default)]
    pub on_any_fail: bool,
    /// Fire alert if fail rate exceeds this threshold (0.0–1.0)
    #[serde(default)]
    pub fail_rate_threshold: Option<f64>,
    /// Fire alert if target device is offline at trigger time
    #[serde(default)]
    pub on_device_offline: bool,
    /// Webhook URL to POST alert payload
    #[serde(default)]
    pub webhook_url: Option<String>,
}

/// Result for a single device in a task run
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DeviceResult {
    pub imei: String,
    pub model: String,
    /// "success" | "failed" | "offline" | "timeout"
    pub status: String,
    #[serde(default)]
    pub output: String,
    pub duration_ms: u64,
}

/// Aggregated summary of a task run
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Default)]
pub struct RunSummary {
    pub total: usize,
    pub success: usize,
    pub failed: usize,
    pub offline: usize,
    pub timeout: usize,
}

/// A single execution record for a schedule
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TaskRun {
    pub id: String,
    pub schedule_id: String,
    pub schedule_name: String,
    pub triggered_at: i64,
    /// "cron" | "manual"
    pub trigger_type: String,
    /// "running" | "done" | "partial_fail" | "all_fail"
    pub status: String,
    pub device_results: Vec<DeviceResult>,
    pub summary: RunSummary,
    pub duration_ms: u64,
}

pub type ScheduleStore = Arc<RwLock<Vec<Schedule>>>;
pub type TaskRunStore = Arc<RwLock<Vec<TaskRun>>>;

/// Persist task runs to disk atomically (write tmp → rename).
pub async fn persist_runs(store: &TaskRunStore) {
    let runs = store.read().await;
    let path = std::path::Path::new(crate::config::TASK_RUNS_FILE);
    match serde_json::to_string_pretty(&*runs) {
        Ok(json) => atomic_write(path, &json),
        Err(e) => tracing::error!("Failed to serialize task_runs: {}", e),
    }
}

/// Load task runs from disk or create empty store
pub fn load_or_create_run_store() -> TaskRunStore {
    let path = std::path::Path::new(crate::config::TASK_RUNS_FILE);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let runs = match std::fs::read_to_string(path) {
        Ok(data) => serde_json::from_str::<Vec<TaskRun>>(&data).unwrap_or_default(),
        Err(_) => Vec::new(),
    };
    Arc::new(RwLock::new(runs))
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct CreateScheduleRequest {
    pub name: String,
    pub cron_expr: String,
    pub action: String,
    #[serde(default)]
    pub params: serde_json::Value,
    pub device_ids: Vec<String>,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
    #[serde(default)]
    pub alert: Option<AlertConfig>,
    #[serde(default)]
    pub batch_size: Option<usize>,
    #[serde(default)]
    pub batch_delay_ms: Option<u64>,
    #[serde(default)]
    pub timeout_secs: Option<u64>,
    #[serde(default)]
    pub retry_count: Option<u8>,
}

fn default_enabled() -> bool {
    true
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct UpdateScheduleRequest {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub cron_expr: Option<String>,
    #[serde(default)]
    pub action: Option<String>,
    #[serde(default)]
    pub params: Option<serde_json::Value>,
    #[serde(default)]
    pub device_ids: Option<Vec<String>>,
    #[serde(default)]
    pub enabled: Option<bool>,
    #[serde(default)]
    pub alert: Option<AlertConfig>,
    #[serde(default)]
    pub batch_size: Option<usize>,
    #[serde(default)]
    pub batch_delay_ms: Option<u64>,
    #[serde(default)]
    pub timeout_secs: Option<u64>,
    #[serde(default)]
    pub retry_count: Option<u8>,
}

#[allow(dead_code)]
pub fn new_schedule_store() -> ScheduleStore {
    Arc::new(RwLock::new(Vec::new()))
}

/// Load schedules from disk or create empty store
pub fn load_or_create_store() -> ScheduleStore {
    let path = std::path::Path::new(crate::config::SCHEDULE_FILE);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let schedules = match std::fs::read_to_string(path) {
        Ok(data) => {
            match serde_json::from_str::<Vec<Schedule>>(&data) {
                Ok(s) => {
                    tracing::info!("Loaded {} schedules from {}", s.len(), path.display());
                    s
                }
                Err(e) => {
                    tracing::warn!("Failed to parse schedules file: {}, starting fresh", e);
                    Vec::new()
                }
            }
        }
        Err(_) => {
            tracing::info!("No schedules file found, starting fresh");
            Vec::new()
        }
    };
    Arc::new(RwLock::new(schedules))
}

/// Persist schedules to disk atomically (write tmp → rename).
pub async fn persist(store: &ScheduleStore) {
    let schedules = store.read().await;
    let path = std::path::Path::new(crate::config::SCHEDULE_FILE);
    match serde_json::to_string_pretty(&*schedules) {
        Ok(json) => atomic_write(path, &json),
        Err(e) => tracing::error!("Failed to serialize schedules: {}", e),
    }
}

/// Atomically write data to a file: write to <path>.tmp then rename.
/// Prevents partial-write corruption on process crash.
fn atomic_write(path: &std::path::Path, data: &str) {
    let tmp_path = path.with_extension("tmp");
    if let Err(e) = std::fs::write(&tmp_path, data) {
        tracing::error!("Failed to write tmp file {}: {}", tmp_path.display(), e);
        return;
    }
    if let Err(e) = std::fs::rename(&tmp_path, path) {
        tracing::error!("Failed to rename {} → {}: {}", tmp_path.display(), path.display(), e);
        // Best-effort cleanup
        let _ = std::fs::remove_file(&tmp_path);
    }
}

pub async fn list_schedules(
    State(store): State<ScheduleStore>,
) -> Json<serde_json::Value> {
    let schedules = store.read().await;
    Json(serde_json::json!({"schedules": *schedules}))
}

pub async fn create_schedule(
    State(store): State<ScheduleStore>,
    Json(req): Json<CreateScheduleRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    // Validate cron expression
    req.cron_expr
        .parse::<cron::Schedule>()
        .map_err(|e| AppError::BadRequest(format!("Invalid cron expression: {}", e)))?;

    let schedule = Schedule {
        id: uuid::Uuid::new_v4().to_string(),
        name: req.name,
        cron_expr: req.cron_expr,
        action: req.action,
        params: req.params,
        device_ids: req.device_ids,
        enabled: req.enabled,
        last_run: None,
        created_at: chrono::Utc::now().timestamp_millis(),
        alert: req.alert,
        batch_size: req.batch_size,
        batch_delay_ms: req.batch_delay_ms,
        timeout_secs: req.timeout_secs,
        retry_count: req.retry_count,
        last_run_status: None,
    };

    let id = schedule.id.clone();
    store.write().await.push(schedule);
    persist(&store).await;

    Ok(Json(serde_json::json!({"success": true, "id": id})))
}

pub async fn update_schedule(
    State(store): State<ScheduleStore>,
    Path(id): Path<String>,
    Json(req): Json<UpdateScheduleRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    if let Some(ref expr) = req.cron_expr {
        expr.parse::<cron::Schedule>()
            .map_err(|e| AppError::BadRequest(format!("Invalid cron expression: {}", e)))?;
    }

    let mut schedules = store.write().await;
    let schedule = schedules
        .iter_mut()
        .find(|s| s.id == id)
        .ok_or_else(|| AppError::BadRequest(format!("Schedule not found: {}", id)))?;

    if let Some(name) = req.name {
        schedule.name = name;
    }
    if let Some(cron_expr) = req.cron_expr {
        schedule.cron_expr = cron_expr;
    }
    if let Some(action) = req.action {
        schedule.action = action;
    }
    if let Some(params) = req.params {
        schedule.params = params;
    }
    if let Some(device_ids) = req.device_ids {
        schedule.device_ids = device_ids;
    }
    if let Some(enabled) = req.enabled {
        schedule.enabled = enabled;
    }
    if let Some(alert) = req.alert {
        schedule.alert = Some(alert);
    }
    if let Some(batch_size) = req.batch_size {
        schedule.batch_size = Some(batch_size);
    }
    if let Some(batch_delay_ms) = req.batch_delay_ms {
        schedule.batch_delay_ms = Some(batch_delay_ms);
    }
    if let Some(timeout_secs) = req.timeout_secs {
        schedule.timeout_secs = Some(timeout_secs);
    }
    if let Some(retry_count) = req.retry_count {
        schedule.retry_count = Some(retry_count);
    }
    drop(schedules);
    persist(&store).await;

    Ok(Json(serde_json::json!({"success": true})))
}

pub async fn delete_schedule(
    State(store): State<ScheduleStore>,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut schedules = store.write().await;
    let len_before = schedules.len();
    schedules.retain(|s| s.id != id);
    if schedules.len() == len_before {
        return Err(AppError::BadRequest(format!("Schedule not found: {}", id)));
    }
    drop(schedules);
    persist(&store).await;
    Ok(Json(serde_json::json!({"success": true})))
}
