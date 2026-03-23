use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;

use crate::api::scheduler::{
    RunSummary, ScheduleStore, TaskRun, TaskRunStore, persist_runs,
};
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;
use crate::scheduler::engine::{RunParams, run_schedule_task};

#[derive(Clone)]
pub struct RunsState {
    pub run_store: TaskRunStore,
    pub schedule_store: ScheduleStore,
    pub registry: Arc<DeviceRegistry>,
}

impl axum::extract::FromRef<RunsState> for TaskRunStore {
    fn from_ref(s: &RunsState) -> Self {
        s.run_store.clone()
    }
}

#[derive(serde::Deserialize, Default)]
pub struct ListRunsQuery {
    /// Filter by schedule_id
    #[serde(default)]
    pub schedule_id: Option<String>,
    /// Max results (default 50)
    #[serde(default)]
    pub limit: Option<usize>,
    /// Offset for pagination
    #[serde(default)]
    pub offset: Option<usize>,
}

/// GET /api/task-runs — list all task runs (newest first), with optional schedule filter
pub async fn list_runs(
    State(state): State<RunsState>,
    Query(q): Query<ListRunsQuery>,
) -> Json<serde_json::Value> {
    let runs = state.run_store.read().await;
    let limit = q.limit.unwrap_or(50).min(200);
    let offset = q.offset.unwrap_or(0);

    let filtered: Vec<&TaskRun> = runs
        .iter()
        .rev() // newest first
        .filter(|r| {
            q.schedule_id
                .as_ref()
                .map(|id| &r.schedule_id == id)
                .unwrap_or(true)
        })
        .skip(offset)
        .take(limit)
        .collect();

    let total = runs
        .iter()
        .filter(|r| {
            q.schedule_id
                .as_ref()
                .map(|id| &r.schedule_id == id)
                .unwrap_or(true)
        })
        .count();

    Json(serde_json::json!({
        "runs": filtered,
        "total": total,
    }))
}

/// GET /api/task-runs/:run_id — get single run detail
pub async fn get_run(
    State(state): State<RunsState>,
    Path(run_id): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let runs = state.run_store.read().await;
    let run = runs
        .iter()
        .find(|r| r.id == run_id)
        .ok_or_else(|| AppError::BadRequest(format!("Task run not found: {}", run_id)))?;
    Ok(Json(serde_json::json!({ "run": run })))
}

/// DELETE /api/task-runs — clear all runs (or optionally by schedule_id)
pub async fn clear_runs(
    State(state): State<RunsState>,
    Query(q): Query<ListRunsQuery>,
) -> Json<serde_json::Value> {
    let mut runs = state.run_store.write().await;
    let before = runs.len();
    if let Some(ref sid) = q.schedule_id {
        runs.retain(|r| &r.schedule_id != sid);
    } else {
        runs.clear();
    }
    let removed = before - runs.len();
    drop(runs);
    persist_runs(&state.run_store).await;
    Json(serde_json::json!({ "removed": removed }))
}

/// GET /api/schedules/:id/runs — convenience: runs for a specific schedule
pub async fn list_schedule_runs(
    State(state): State<RunsState>,
    Path(schedule_id): Path<String>,
    Query(q): Query<ListRunsQuery>,
) -> Json<serde_json::Value> {
    let runs = state.run_store.read().await;
    let limit = q.limit.unwrap_or(20).min(100);
    let offset = q.offset.unwrap_or(0);

    let filtered: Vec<&TaskRun> = runs
        .iter()
        .rev()
        .filter(|r| r.schedule_id == schedule_id)
        .skip(offset)
        .take(limit)
        .collect();

    let total = runs.iter().filter(|r| r.schedule_id == schedule_id).count();

    Json(serde_json::json!({
        "runs": filtered,
        "total": total,
    }))
}

/// POST /api/schedules/:id/trigger — manually trigger a schedule immediately
pub async fn trigger_schedule(
    State(state): State<RunsState>,
    Path(schedule_id): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let schedule = {
        let schedules = state.schedule_store.read().await;
        schedules
            .iter()
            .find(|s| s.id == schedule_id)
            .cloned()
            .ok_or_else(|| AppError::BadRequest(format!("Schedule not found: {}", schedule_id)))?
    };

    let p = RunParams::from_schedule(&schedule, "manual");
    let run_id = p.run_id.clone();

    // Insert a "running" placeholder so the UI can show progress immediately.
    // run_schedule_task() will UPDATE this slot in-place (never drain it), fixing bug-1.
    {
        let placeholder = TaskRun {
            id: run_id.clone(),
            schedule_id: schedule_id.clone(),
            schedule_name: schedule.name.clone(),
            triggered_at: p.triggered_at,
            trigger_type: "manual".to_string(),
            status: "running".to_string(),
            device_results: vec![],
            summary: RunSummary::default(),
            duration_ms: 0,
        };
        let mut runs = state.run_store.write().await;
        runs.push(placeholder);
        // Apply ring-buffer cap immediately on insert (placeholder is not the target of drain).
        let max = crate::config::MAX_TASK_RUNS;
        if runs.len() > max {
            let drain_count = runs.len() - max;
            // Never drain the placeholder we just inserted (it's always last).
            let safe_drain = drain_count.min(runs.len().saturating_sub(1));
            if safe_drain > 0 {
                runs.drain(0..safe_drain);
            }
        }
    }

    let registry = state.registry.clone();
    let schedule_store = state.schedule_store.clone();
    let run_store = state.run_store.clone();
    let placeholder_id = run_id.clone();

    tokio::spawn(async move {
        run_schedule_task(registry, schedule_store, run_store, p, Some(placeholder_id)).await;
    });

    Ok(Json(serde_json::json!({
        "success": true,
        "run_id": run_id,
        "message": "Task triggered, running in background"
    })))
}

/// GET /api/task-runs/stats — today's stats for dashboard
pub async fn run_stats(
    State(state): State<RunsState>,
) -> Json<serde_json::Value> {
    let runs = state.run_store.read().await;
    let now_ms = chrono::Utc::now().timestamp_millis();
    let today_start = now_ms - 86_400_000; // last 24h

    // Exclude still-running tasks — they haven't completed so including them
    // skews total_today and success_rate before results are known.
    let today_runs: Vec<&TaskRun> = runs
        .iter()
        .filter(|r| r.triggered_at >= today_start && r.status != "running")
        .collect();

    let total_today = today_runs.len();
    let success_runs = today_runs.iter().filter(|r| r.status == "done").count();
    let failed_runs = today_runs
        .iter()
        .filter(|r| r.status == "all_fail" || r.status == "partial_fail")
        .count();

    let total_devices: usize = today_runs.iter().map(|r| r.summary.total).sum();
    let success_devices: usize = today_runs.iter().map(|r| r.summary.success).sum();

    let success_rate = if total_devices > 0 {
        (success_devices as f64 / total_devices as f64 * 100.0).round() as u64
    } else {
        0
    };

    Json(serde_json::json!({
        "total_today": total_today,
        "success_runs": success_runs,
        "failed_runs": failed_runs,
        "total_devices": total_devices,
        "success_devices": success_devices,
        "success_rate": success_rate,
    }))
}
