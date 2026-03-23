use std::sync::Arc;

use tokio::sync::oneshot;

use super::protocol::ServerCommand;
use super::registry::DeviceRegistry;

/// Forward a WebRTC offer from browser to device, wait for answer
#[allow(dead_code)]
pub async fn forward_rtc_offer(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    browser_id: &str,
    sdp: &str,
) -> Result<String, crate::error::AppError> {
    let cmd_tx = registry
        .get_cmd_tx(imei)
        .ok_or_else(|| crate::error::AppError::DeviceOffline(imei.to_string()))?;

    let pending = registry
        .get_pending_responses(imei)
        .ok_or_else(|| crate::error::AppError::DeviceOffline(imei.to_string()))?;

    // Register a oneshot for the answer
    let (tx, rx) = oneshot::channel();
    let key = format!("rtc_answer_{}", browser_id);
    pending.insert(key.clone(), tx);

    // Send offer to device
    let cmd = ServerCommand::RtcOffer {
        browser_id: browser_id.to_string(),
        sdp: sdp.to_string(),
    };

    cmd_tx.send(cmd).await.map_err(|_| {
        pending.remove(&key);
        crate::error::AppError::DeviceOffline(imei.to_string())
    })?;

    // Wait for answer with timeout
    match tokio::time::timeout(
        std::time::Duration::from_secs(crate::config::P2P_TIMEOUT_SECS),
        rx,
    )
    .await
    {
        Ok(Ok(val)) => val["sdp"]
            .as_str()
            .map(|s| s.to_string())
            .ok_or_else(|| crate::error::AppError::CommandFailed("Invalid RTC answer".into())),
        Ok(Err(_)) => {
            pending.remove(&key);
            Err(crate::error::AppError::CommandFailed(
                "RTC answer channel closed".into(),
            ))
        }
        Err(_) => {
            pending.remove(&key);
            Err(crate::error::AppError::CommandTimeout(
                "RTC offer timeout".into(),
            ))
        }
    }
}

/// Forward an ICE candidate from browser to device
#[allow(dead_code)]
pub async fn forward_ice_candidate(
    registry: &Arc<DeviceRegistry>,
    imei: &str,
    browser_id: &str,
    candidate: &str,
) -> Result<(), crate::error::AppError> {
    let cmd_tx = registry
        .get_cmd_tx(imei)
        .ok_or_else(|| crate::error::AppError::DeviceOffline(imei.to_string()))?;

    let cmd = ServerCommand::RtcIce {
        browser_id: browser_id.to_string(),
        candidate: candidate.to_string(),
    };

    cmd_tx
        .send(cmd)
        .await
        .map_err(|_| crate::error::AppError::DeviceOffline(imei.to_string()))?;

    Ok(())
}
