use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use chrono::Utc;
use cron::Schedule as CronSchedule;
use tokio::sync::Semaphore;

use crate::alert;
use crate::api::scheduler::{
    AlertConfig, DeviceResult, RunSummary, Schedule, ScheduleStore, TaskRun, TaskRunStore,
    persist as persist_schedules, persist_runs,
};
use crate::device::registry::DeviceRegistry;

/// Parameters for a single schedule execution (shared by cron and manual trigger).
pub struct RunParams {
    pub run_id: String,
    pub schedule_id: String,
    pub schedule_name: String,
    pub triggered_at: i64,
    pub trigger_type: &'static str,
    pub action: String,
    pub params: serde_json::Value,
    pub device_ids: Vec<String>,
    pub timeout_secs: u64,
    pub retry_count: u8,
    pub batch_size: Option<usize>,
    pub batch_delay_ms: u64,
    pub alert_cfg: Option<AlertConfig>,
}

impl RunParams {
    pub fn from_schedule(schedule: &Schedule, trigger_type: &'static str) -> Self {
        Self {
            run_id: uuid::Uuid::new_v4().to_string(),
            schedule_id: schedule.id.clone(),
            schedule_name: schedule.name.clone(),
            triggered_at: Utc::now().timestamp_millis(),
            trigger_type,
            action: schedule.action.clone(),
            params: schedule.params.clone(),
            device_ids: schedule.device_ids.clone(),
            timeout_secs: schedule.timeout_secs.unwrap_or(60),
            retry_count: schedule.retry_count.unwrap_or(0),
            batch_size: schedule.batch_size,
            batch_delay_ms: schedule.batch_delay_ms.unwrap_or(0),
            alert_cfg: schedule.alert.clone(),
        }
    }
}

/// Execute a scheduled task, persist the result, update schedule metadata, and fire alerts.
/// This is the single canonical execution path shared by both cron loop and manual trigger.
///
/// - `placeholder_id`: if Some, the run slot already exists in run_store (manual trigger
///   inserts a "running" placeholder first); if None, a new slot is appended.
pub async fn run_schedule_task(
    registry: Arc<DeviceRegistry>,
    schedule_store: ScheduleStore,
    run_store: TaskRunStore,
    p: RunParams,
    placeholder_id: Option<String>,
) {
    let results = execute_on_devices(
        &registry,
        &p.device_ids,
        &p.action,
        &p.params,
        p.timeout_secs,
        p.retry_count,
        p.batch_size,
        p.batch_delay_ms,
    )
    .await;

    let summary = aggregate_summary(&results);
    let duration_ms = results.iter().map(|r| r.duration_ms).max().unwrap_or(0);
    // fix bug-2: use actual completion time, not loop-start time
    let completed_at = Utc::now().timestamp_millis();
    let status = derive_run_status(&summary);

    let task_run = TaskRun {
        id: p.run_id.clone(),
        schedule_id: p.schedule_id.clone(),
        schedule_name: p.schedule_name.clone(),
        triggered_at: p.triggered_at,
        trigger_type: p.trigger_type.to_string(),
        status: status.clone(),
        device_results: results,
        summary: summary.clone(),
        duration_ms,
    };

    // Persist run — ring buffer capped at MAX_TASK_RUNS.
    // fix bug-1: when a placeholder exists we UPDATE in-place rather than push+drain,
    // so the placeholder can never be evicted before it's finalised.
    {
        let mut runs = run_store.write().await;
        if let Some(ref pid) = placeholder_id {
            if let Some(slot) = runs.iter_mut().find(|r| r.id == *pid) {
                *slot = task_run.clone();
            } else {
                // Placeholder was somehow lost (shouldn't happen); append normally.
                runs.push(task_run.clone());
                let max = crate::config::MAX_TASK_RUNS;
                if runs.len() > max {
                    let drain_count = runs.len() - max;
                    runs.drain(0..drain_count);
                }
            }
        } else {
            runs.push(task_run.clone());
            let max = crate::config::MAX_TASK_RUNS;
            if runs.len() > max {
                let drain_count = runs.len() - max;
                runs.drain(0..drain_count);
            }
        }
    }
    persist_runs(&run_store).await;

    // Update last_run (actual completion time) + last_run_status on the schedule.
    {
        let mut schedules = schedule_store.write().await;
        if let Some(s) = schedules.iter_mut().find(|s| s.id == p.schedule_id) {
            s.last_run = Some(completed_at);
            s.last_run_status = Some(status.clone());
        }
    }
    persist_schedules(&schedule_store).await;

    // Fire alert if configured.
    if let Some(cfg) = p.alert_cfg {
        alert::check_and_alert(&cfg, &task_run).await;
    }

    tracing::info!(
        schedule = %p.schedule_id,
        trigger = %p.trigger_type,
        status = %status,
        success = summary.success,
        failed = summary.failed,
        offline = summary.offline,
        duration_ms,
        "Task run completed"
    );
}

/// Start the scheduler engine that checks cron schedules every 30 seconds.
pub fn spawn_scheduler(
    registry: Arc<DeviceRegistry>,
    store: ScheduleStore,
    run_store: TaskRunStore,
) {
    tokio::spawn(async move {
        tracing::info!("Scheduler engine started");
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(30));

        loop {
            interval.tick().await;

            let schedules = store.read().await.clone();
            let now = Utc::now();

            for schedule in &schedules {
                if !schedule.enabled {
                    continue;
                }

                let cron: CronSchedule = match schedule.cron_expr.parse() {
                    Ok(c) => c,
                    Err(e) => {
                        tracing::warn!(
                            id = %schedule.id,
                            expr = %schedule.cron_expr,
                            error = %e,
                            "Invalid cron expression, skipping"
                        );
                        continue;
                    }
                };

                // Window: [now-35s, now] — slightly wider than poll interval to tolerate drift
                let window_start = now - chrono::Duration::seconds(35);
                let last_run_ts = schedule.last_run.unwrap_or(0);
                let mut should_fire = false;
                for next in cron.after(&window_start).take(4) {
                    if next > now {
                        break;
                    }
                    if next.timestamp_millis() > last_run_ts {
                        should_fire = true;
                        break;
                    }
                }

                if !should_fire {
                    continue;
                }

                tracing::info!(
                    id = %schedule.id,
                    name = %schedule.name,
                    action = %schedule.action,
                    devices = schedule.device_ids.len(),
                    "Firing scheduled task"
                );

                let p = RunParams::from_schedule(schedule, "cron");
                let registry_clone = registry.clone();
                let store_clone = store.clone();
                let run_store_clone = run_store.clone();

                // Spawn execution so we don't block the cron loop.
                tokio::spawn(async move {
                    run_schedule_task(registry_clone, store_clone, run_store_clone, p, None).await;
                });
            }
        }
    });
}

/// Execute an action on a list of devices with optional batching, timeout, and retry.
pub async fn execute_on_devices(
    registry: &Arc<DeviceRegistry>,
    device_ids: &[String],
    action: &str,
    params: &serde_json::Value,
    timeout_secs: u64,
    retry_count: u8,
    batch_size: Option<usize>,
    batch_delay_ms: u64,
) -> Vec<DeviceResult> {
    let semaphore = Arc::new(Semaphore::new(crate::config::MAX_CONCURRENT_DEVICES));
    let chunks: Vec<&[String]> = if let Some(size) = batch_size {
        device_ids.chunks(size).collect()
    } else {
        vec![device_ids]
    };

    let mut all_results: Vec<DeviceResult> = Vec::new();

    for (batch_idx, chunk) in chunks.iter().enumerate() {
        if batch_idx > 0 && batch_delay_ms > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(batch_delay_ms)).await;
        }

        let futs = chunk.iter().map(|imei| {
            let registry = registry.clone();
            let imei = imei.clone();
            let action = action.to_string();
            let params = params.clone();
            let semaphore = semaphore.clone();
            async move {
                let _permit = semaphore.acquire().await;
                execute_single_device_with_retry(
                    &registry,
                    &imei,
                    &action,
                    &params,
                    timeout_secs,
                    retry_count,
                )
                .await
            }
        });

        let results = futures_util::future::join_all(futs).await;
        all_results.extend(results);
    }

    all_results
}

/// Execute action on one device with retries. Returns DeviceResult.
async fn execute_single_device_with_retry(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    action: &str,
    params: &serde_json::Value,
    timeout_secs: u64,
    retry_count: u8,
) -> DeviceResult {
    let model = registry
        .devices
        .get(imei)
        .map(|d| d.info.model.clone())
        .unwrap_or_default();

    let mut attempts = 0u8;
    loop {
        let result =
            execute_single_device(registry, imei, &model, action, params, timeout_secs).await;
        if result.status == "success" || attempts >= retry_count {
            return result;
        }
        attempts += 1;
        tracing::info!(
            imei = imei,
            action = action,
            attempt = attempts,
            "Retrying after failure"
        );
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
}

/// Execute action on a single device. Returns a DeviceResult.
async fn execute_single_device(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    model: &str,
    action: &str,
    params: &serde_json::Value,
    timeout_secs: u64,
) -> DeviceResult {
    let start = Instant::now();

    let cmd_params = build_params(action, params);

    // Check if device is online
    let pending = match registry.get_pending_responses(imei) {
        Some(p) => p,
        None => {
            return DeviceResult {
                imei: imei.to_string(),
                model: model.to_string(),
                status: "offline".to_string(),
                output: "Device not connected".to_string(),
                duration_ms: start.elapsed().as_millis() as u64,
            };
        }
    };

    let cmd_tx = match registry.get_cmd_tx(imei) {
        Some(tx) => tx,
        None => {
            return DeviceResult {
                imei: imei.to_string(),
                model: model.to_string(),
                status: "offline".to_string(),
                output: "Device not connected".to_string(),
                duration_ms: start.elapsed().as_millis() as u64,
            };
        }
    };

    // Fire-and-forget actions don't need a response channel
    let is_fire_forget = matches!(
        action,
        "start_project" | "stop_project" | "reboot_engine" | "touch" | "key" | "text"
    );

    if is_fire_forget {
        let cmd = crate::device::protocol::ServerCommand::Cmd {
            id: uuid::Uuid::new_v4().to_string(),
            action: action.to_string(),
            params: cmd_params,
        };
        match cmd_tx.send(cmd).await {
            Ok(_) => DeviceResult {
                imei: imei.to_string(),
                model: model.to_string(),
                status: "success".to_string(),
                output: String::new(),
                duration_ms: start.elapsed().as_millis() as u64,
            },
            Err(e) => DeviceResult {
                imei: imei.to_string(),
                model: model.to_string(),
                status: "failed".to_string(),
                output: e.to_string(),
                duration_ms: start.elapsed().as_millis() as u64,
            },
        }
    } else {
        // Use request-response pattern
        let request_id = uuid::Uuid::new_v4().to_string();
        let (tx, rx) = tokio::sync::oneshot::channel();
        pending.insert(request_id.clone(), tx);

        let cmd = crate::device::protocol::ServerCommand::Cmd {
            id: request_id.clone(),
            action: action.to_string(),
            params: cmd_params,
        };

        if cmd_tx.send(cmd).await.is_err() {
            pending.remove(&request_id);
            return DeviceResult {
                imei: imei.to_string(),
                model: model.to_string(),
                status: "offline".to_string(),
                output: "Send failed".to_string(),
                duration_ms: start.elapsed().as_millis() as u64,
            };
        }

        match tokio::time::timeout(std::time::Duration::from_secs(timeout_secs), rx).await {
            Ok(Ok(val)) => {
                let has_error = val.get("error").is_some();
                let output = val
                    .get("output")
                    .or_else(|| val.get("data"))
                    .map(|v| v.to_string())
                    .unwrap_or_default();
                DeviceResult {
                    imei: imei.to_string(),
                    model: model.to_string(),
                    status: if has_error {
                        "failed".to_string()
                    } else {
                        "success".to_string()
                    },
                    output: if has_error {
                        val.get("error")
                            .map(|v| v.as_str().unwrap_or("").to_string())
                            .unwrap_or_default()
                    } else {
                        output
                    },
                    duration_ms: start.elapsed().as_millis() as u64,
                }
            }
            Ok(Err(_)) => {
                pending.remove(&request_id);
                DeviceResult {
                    imei: imei.to_string(),
                    model: model.to_string(),
                    status: "failed".to_string(),
                    output: "Response channel closed".to_string(),
                    duration_ms: start.elapsed().as_millis() as u64,
                }
            }
            Err(_) => {
                pending.remove(&request_id);
                DeviceResult {
                    imei: imei.to_string(),
                    model: model.to_string(),
                    status: "timeout".to_string(),
                    output: format!("Timed out after {}s", timeout_secs),
                    duration_ms: start.elapsed().as_millis() as u64,
                }
            }
        }
    }
}

/// Build the params HashMap from action name and JSON params.
fn build_params(
    action: &str,
    params: &serde_json::Value,
) -> HashMap<String, serde_json::Value> {
    let mut cmd_params = HashMap::new();
    match action {
        "start_project" => {
            if let Some(name) = params.get("name").and_then(|v| v.as_str()) {
                cmd_params.insert("name".to_string(), serde_json::json!(name));
            }
        }
        "stop_project" => {}
        "reboot_engine" => {}
        "shell" => {
            if let Some(command) = params.get("command").and_then(|v| v.as_str()) {
                cmd_params.insert("cmd".to_string(), serde_json::json!(command));
            }
        }
        _ => {
            if let Some(obj) = params.as_object() {
                for (k, v) in obj {
                    cmd_params.insert(k.clone(), v.clone());
                }
            }
        }
    }
    cmd_params
}

/// Aggregate DeviceResults into a RunSummary.
pub fn aggregate_summary(results: &[DeviceResult]) -> RunSummary {
    let mut summary = RunSummary {
        total: results.len(),
        ..Default::default()
    };
    for r in results {
        match r.status.as_str() {
            "success" => summary.success += 1,
            "offline" => summary.offline += 1,
            "timeout" => summary.timeout += 1,
            _ => summary.failed += 1,
        }
    }
    summary
}

/// Derive overall run status string from summary.
pub fn derive_run_status(summary: &RunSummary) -> String {
    if summary.total == 0 {
        return "done".to_string();
    }
    let non_success = summary.failed + summary.offline + summary.timeout;
    if non_success == 0 {
        "done".to_string()
    } else if non_success == summary.total {
        "all_fail".to_string()
    } else {
        "partial_fail".to_string()
    }
}
