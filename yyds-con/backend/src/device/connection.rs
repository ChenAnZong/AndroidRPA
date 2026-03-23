use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket};
use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::{broadcast, mpsc};

use super::protocol::{DeviceInfo, DeviceMessage, ServerCommand};
use super::registry::{DeviceRegistry, RtcSignal};
use crate::config;

/// Handle a device WebSocket connection
pub async fn handle_device_ws(
    socket: WebSocket,
    imei: String,
    model: String,
    sw: u32,
    sh: u32,
    ver: u32,
    registry: Arc<DeviceRegistry>,
) {
    let (mut ws_tx, mut ws_rx) = socket.split();
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<ServerCommand>(64);

    let now = chrono::Utc::now();
    let info = DeviceInfo {
        imei: imei.clone(),
        model: model.clone(),
        screen_width: sw,
        screen_height: sh,
        version: ver,
        online: true,
        connected_at: now,
        last_seen: now,
        running_project: String::new(),
        foreground_app: String::new(),
    };

    let (frame_tx, log_tx) = registry.register(imei.clone(), info, cmd_tx);
    let pending = match registry.get_pending_responses(&imei) {
        Some(p) => p,
        None => {
            tracing::error!(imei = %imei, "Race condition: device registered but pending_responses missing");
            return; // 安全退出，避免 panic
        }
    };

    tracing::info!(
        imei = %imei,
        model = %model,
        screen = %format!("{}x{}", sw, sh),
        "Device connected"
    );

    // Task: forward server commands to device WS + send periodic pings
    let imei_clone = imei.clone();
    let send_task = tokio::spawn(async move {
        let mut ping_interval =
            tokio::time::interval(std::time::Duration::from_secs(config::HEARTBEAT_INTERVAL_SECS));
        loop {
            tokio::select! {
                cmd = cmd_rx.recv() => {
                    match cmd {
                        Some(cmd) => {
                            match serde_json::to_string(&cmd) {
                                Ok(json) => {
                                    if ws_tx.send(Message::Text(json.into())).await.is_err() {
                                        tracing::warn!(imei = %imei_clone, "Failed to send command to device");
                                        break;
                                    }
                                }
                                Err(e) => {
                                    tracing::error!(imei = %imei_clone, error = %e, "Failed to serialize command");
                                }
                            }
                        }
                        None => break, // cmd channel closed (device unregistered)
                    }
                }
                _ = ping_interval.tick() => {
                    if ws_tx.send(Message::Ping(vec![].into())).await.is_err() {
                        tracing::warn!(imei = %imei_clone, "Ping failed, device likely dead");
                        break;
                    }
                }
            }
        }
    });

    // Main loop: receive messages from device
    let imei_recv = imei.clone();
    let registry_recv = registry.clone();
    let recv_task = tokio::spawn(async move {
        let mut heartbeat_interval =
            tokio::time::interval(std::time::Duration::from_secs(config::HEARTBEAT_INTERVAL_SECS));

        loop {
            tokio::select! {
                msg = ws_rx.next() => {
                    match msg {
                        Some(Ok(Message::Text(text))) => {
                            handle_text_message(
                                &imei_recv,
                                &text,
                                &registry_recv,
                                &pending,
                                &log_tx,
                            ).await;
                        }
                        Some(Ok(Message::Binary(data))) => {
                            // Binary frames = screenshot data (WS fallback mode)
                            let bytes = Bytes::from(data.to_vec());
                            // Update thumbnail cache
                            if let Some(thumb) = registry_recv.get_thumbnail(&imei_recv) {
                                let mut lock = thumb.write().await;
                                *lock = Some(bytes.clone());
                            }
                            // Broadcast to stream viewers
                            let _ = frame_tx.send(bytes);
                        }
                        Some(Ok(Message::Ping(data))) => {
                            // Axum handles pong automatically
                            let _ = data;
                        }
                        Some(Ok(Message::Pong(_))) => {
                            registry_recv.update_info(&imei_recv, |info| {
                                info.last_seen = chrono::Utc::now();
                            });
                        }
                        Some(Ok(Message::Close(_))) | None => {
                            tracing::info!(imei = %imei_recv, "Device disconnected");
                            break;
                        }
                        Some(Err(e)) => {
                            tracing::warn!(imei = %imei_recv, error = %e, "WebSocket error");
                            break;
                        }
                    }
                }
                _ = heartbeat_interval.tick() => {
                    // Check if last_seen is too old (no Pong received)
                    let timed_out = registry_recv.devices.get(&*imei_recv).map(|d| {
                        let elapsed = chrono::Utc::now() - d.info.last_seen;
                        elapsed.num_seconds() > config::HEARTBEAT_TIMEOUT_SECS as i64
                    }).unwrap_or(true);
                    if timed_out {
                        tracing::warn!(imei = %imei_recv, "Device heartbeat timeout, disconnecting");
                        break;
                    }
                }
            }
        }
    });

    // Wait for either task to finish
    tokio::select! {
        _ = send_task => {}
        _ = recv_task => {}
    }

    // Cleanup
    registry.update_info(&imei, |info| {
        info.online = false;
    });
    registry.unregister(&imei);
    tracing::info!(imei = %imei, "Device connection cleaned up");
}

async fn handle_text_message(
    imei: &str,
    text: &str,
    registry: &Arc<DeviceRegistry>,
    pending: &Arc<dashmap::DashMap<String, tokio::sync::oneshot::Sender<serde_json::Value>>>,
    log_tx: &broadcast::Sender<String>,
) {
    let msg: DeviceMessage = match serde_json::from_str(text) {
        Ok(m) => m,
        Err(e) => {
            tracing::warn!(imei = imei, error = %e, text = text, "Failed to parse device message");
            return;
        }
    };

    match msg {
        DeviceMessage::Register { .. } => {
            // Already registered during connection setup; ignore duplicate
        }
        DeviceMessage::Response { id, success, data } => {
            if let Some((_, sender)) = pending.remove(&id) {
                let val = if success {
                    data
                } else {
                    serde_json::json!({"error": data})
                };
                let _ = sender.send(val);
            }
        }
        DeviceMessage::Log { text } => {
            tracing::info!(imei = imei, text = %text, "📋 Device log received");
            let _ = log_tx.send(text);
        }
        DeviceMessage::Status {
            running,
            project,
            fg,
        } => {
            registry.update_info(imei, |info| {
                info.running_project = if running { project } else { String::new() };
                info.foreground_app = fg;
                info.last_seen = chrono::Utc::now();
            });
        }
        DeviceMessage::Thumbnail => {
            // No-op: next binary frame will be stored as thumbnail by the binary handler
        }
        DeviceMessage::RtcAnswer { browser_id, sdp } => {
            // Forward RTC answer to browser via rtc_signal channel
            if let Some(rtc_txs) = registry.get_rtc_signal_txs(imei) {
                if let Some(tx) = rtc_txs.get(&browser_id) {
                    let _ = tx.send(RtcSignal::Answer { sdp }).await;
                }
            }
        }
        DeviceMessage::RtcIce {
            browser_id,
            candidate,
        } => {
            // Forward ICE candidate to browser via rtc_signal channel (supports multiple)
            if let Some(rtc_txs) = registry.get_rtc_signal_txs(imei) {
                if let Some(tx) = rtc_txs.get(&browser_id) {
                    let _ = tx.send(RtcSignal::Ice { candidate }).await;
                }
            }
        }
    }
}
