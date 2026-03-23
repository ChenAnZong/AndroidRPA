use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::oneshot;

use crate::device::protocol::ServerCommand;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

/// Send a command to a device and wait for the response with timeout.
/// Shared by control, projects, and files modules.
pub async fn send_cmd_and_wait(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    action: &str,
    params: HashMap<String, serde_json::Value>,
    timeout_secs: u64,
) -> Result<serde_json::Value, AppError> {
    let cmd_tx = registry
        .get_cmd_tx(imei)
        .ok_or_else(|| AppError::DeviceOffline(imei.to_string()))?;

    let pending = registry
        .get_pending_responses(imei)
        .ok_or_else(|| AppError::DeviceOffline(imei.to_string()))?;

    let request_id = uuid::Uuid::new_v4().to_string();
    let (tx, rx) = oneshot::channel();
    pending.insert(request_id.clone(), tx);

    let cmd = ServerCommand::Cmd {
        id: request_id.clone(),
        action: action.to_string(),
        params,
    };

    cmd_tx.send(cmd).await.map_err(|_| {
        pending.remove(&request_id);
        AppError::DeviceOffline(imei.to_string())
    })?;

    match tokio::time::timeout(Duration::from_secs(timeout_secs), rx).await {
        Ok(Ok(val)) => {
            if let Some(err) = val.get("error") {
                Err(AppError::CommandFailed(err.to_string()))
            } else {
                Ok(val)
            }
        }
        Ok(Err(_)) => {
            pending.remove(&request_id);
            Err(AppError::CommandFailed("Response channel closed".into()))
        }
        Err(_) => {
            pending.remove(&request_id);
            Err(AppError::CommandTimeout(format!(
                "Command '{}' timed out after {}s",
                action, timeout_secs
            )))
        }
    }
}

/// Send a command without waiting for a response (fire-and-forget).
pub async fn send_cmd_fire_and_forget(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    action: &str,
    params: HashMap<String, serde_json::Value>,
) -> Result<(), AppError> {
    let cmd_tx = registry
        .get_cmd_tx(imei)
        .ok_or_else(|| AppError::DeviceOffline(imei.to_string()))?;

    let cmd = ServerCommand::Cmd {
        id: uuid::Uuid::new_v4().to_string(),
        action: action.to_string(),
        params,
    };

    cmd_tx
        .send(cmd)
        .await
        .map_err(|_| AppError::DeviceOffline(imei.to_string()))
}
