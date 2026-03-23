use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket};
use axum::extract::{Path, State, WebSocketUpgrade};
use axum::response::IntoResponse;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;

use crate::device::protocol::{BrowserStreamMessage, ServerCommand};
use crate::device::registry::{DeviceRegistry, RtcSignal};
use crate::error::AppError;

pub async fn stream_ws(
    ws: WebSocketUpgrade,
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    if !registry.devices.contains_key(&imei) {
        return Err(AppError::DeviceNotFound(imei));
    }

    Ok(ws.on_upgrade(move |socket| handle_stream_ws(socket, imei, registry)))
}

async fn handle_stream_ws(socket: WebSocket, imei: String, registry: Arc<DeviceRegistry>) {
    let browser_id = uuid::Uuid::new_v4().to_string();
    let (mut ws_tx, mut ws_rx) = socket.split();

    tracing::info!(imei = %imei, browser_id = %browser_id, "Stream viewer connected");

    // Increment viewer count
    let viewer_count = match registry.get_viewer_count(&imei) {
        Some(vc) => vc,
        None => {
            tracing::warn!(imei = %imei, "Device not found for stream");
            return;
        }
    };
    let prev = viewer_count.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    // Subscribe to frame broadcast (for WS fallback mode)
    let frame_tx = match registry.get_frame_tx(&imei) {
        Some(tx) => tx,
        None => return,
    };
    let mut frame_rx = frame_tx.subscribe();

    let cmd_tx = match registry.get_cmd_tx(&imei) {
        Some(tx) => tx,
        None => return,
    };

    // Track P2P vs WS mode
    let mut is_p2p = false;
    let mut ws_streaming = false;

    // Register RTC signal channel for this browser
    let rtc_signal_txs = registry.get_rtc_signal_txs(&imei);
    let (rtc_tx, mut rtc_rx) = mpsc::channel::<RtcSignal>(32);
    if let Some(ref txs) = rtc_signal_txs {
        txs.insert(browser_id.clone(), rtc_tx);
    }

    if prev == 0 {
        tracing::info!(imei = %imei, "First viewer, waiting for P2P or WS fallback request");
    }

    // Send browser_id to the client
    let init_msg = serde_json::json!({
        "type": "init",
        "browser_id": browser_id,
        "imei": imei,
    });
    if ws_tx
        .send(Message::Text(init_msg.to_string().into()))
        .await
        .is_err()
    {
        return;
    }

    // Forward frames to browser (WS fallback) + handle incoming messages
    loop {
        tokio::select! {
            // Receive messages from browser
            msg = ws_rx.next() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        match serde_json::from_str::<BrowserStreamMessage>(&text) {
                            Ok(BrowserStreamMessage::StartStream { quality, interval, max_height }) => {
                                tracing::info!(imei = %imei, browser_id = %browser_id, "WS fallback stream requested q={} i={} h={}", quality, interval, max_height);
                                is_p2p = false;
                                ws_streaming = true;
                                let mut params = std::collections::HashMap::new();
                                params.insert("quality".to_string(), serde_json::json!(quality));
                                params.insert("interval".to_string(), serde_json::json!(interval));
                                params.insert("maxHeight".to_string(), serde_json::json!(max_height));
                                let cmd = ServerCommand::Cmd {
                                    id: uuid::Uuid::new_v4().to_string(),
                                    action: "start_stream".to_string(),
                                    params,
                                };
                                let _ = cmd_tx.send(cmd).await;
                            }
                            Ok(BrowserStreamMessage::StopStream) => {
                                ws_streaming = false;
                                let cmd = ServerCommand::Cmd {
                                    id: uuid::Uuid::new_v4().to_string(),
                                    action: "stop_stream".to_string(),
                                    params: std::collections::HashMap::new(),
                                };
                                let _ = cmd_tx.send(cmd).await;
                            }
                            Ok(BrowserStreamMessage::AdjustStream { quality, interval, max_height }) => {
                                tracing::debug!(imei = %imei, "Adaptive adjust: q={:?} i={:?} h={:?}", quality, interval, max_height);
                                let mut params = std::collections::HashMap::new();
                                if let Some(q) = quality { params.insert("quality".to_string(), serde_json::json!(q)); }
                                if let Some(i) = interval { params.insert("interval".to_string(), serde_json::json!(i)); }
                                if let Some(h) = max_height { params.insert("maxHeight".to_string(), serde_json::json!(h)); }
                                let cmd = ServerCommand::Cmd {
                                    id: uuid::Uuid::new_v4().to_string(),
                                    action: "adjust_stream".to_string(),
                                    params,
                                };
                                let _ = cmd_tx.send(cmd).await;
                            }
                            Ok(BrowserStreamMessage::RtcOffer { sdp }) => {
                                tracing::info!(imei = %imei, browser_id = %browser_id, "Forwarding RTC offer to device");
                                let cmd = ServerCommand::RtcOffer {
                                    browser_id: browser_id.clone(),
                                    sdp,
                                };
                                let _ = cmd_tx.send(cmd).await;
                                is_p2p = true;
                            }
                            Ok(BrowserStreamMessage::RtcIce { candidate }) => {
                                let cmd = ServerCommand::RtcIce {
                                    browser_id: browser_id.clone(),
                                    candidate,
                                };
                                let _ = cmd_tx.send(cmd).await;
                            }
                            Err(e) => {
                                tracing::warn!(error = %e, "Invalid stream message from browser");
                            }
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => {
                        tracing::info!(imei = %imei, browser_id = %browser_id, "Stream viewer disconnected");
                        break;
                    }
                    _ => {}
                }
            }
            // Forward RTC signaling from device to browser
            rtc_signal = rtc_rx.recv() => {
                match rtc_signal {
                    Some(RtcSignal::Answer { sdp }) => {
                        let msg = serde_json::json!({ "type": "rtc_answer", "sdp": sdp });
                        if ws_tx.send(Message::Text(msg.to_string().into())).await.is_err() { break; }
                    }
                    Some(RtcSignal::Ice { candidate }) => {
                        let msg = serde_json::json!({ "type": "rtc_ice", "candidate": candidate });
                        if ws_tx.send(Message::Text(msg.to_string().into())).await.is_err() { break; }
                    }
                    None => { break; }
                }
            }
            // Forward frames from device to browser (WS fallback only)
            frame = frame_rx.recv() => {
                if !ws_streaming || is_p2p {
                    if let Err(tokio::sync::broadcast::error::RecvError::Closed) = frame {
                        break;
                    }
                    continue;
                }
                match frame {
                    Ok(data) => {
                        if ws_tx.send(Message::Binary(data.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        tracing::debug!(imei = %imei, lagged = n, "Frame relay lagged, skipping");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
        }
    }

    // Cleanup RTC signal channel
    if let Some(ref txs) = rtc_signal_txs {
        txs.remove(&browser_id);
    }

    // Decrement viewer count with underflow protection (CAS loop)
    loop {
        let current = viewer_count.load(std::sync::atomic::Ordering::Relaxed);
        if current == 0 { break; }
        if viewer_count.compare_exchange(
            current, current - 1,
            std::sync::atomic::Ordering::Relaxed,
            std::sync::atomic::Ordering::Relaxed,
        ).is_ok() {
            break;
        }
    }
    let remaining = viewer_count.load(std::sync::atomic::Ordering::Relaxed);

    // If no more viewers, tell device to stop streaming
    if remaining == 0 && ws_streaming {
        let cmd = ServerCommand::Cmd {
            id: uuid::Uuid::new_v4().to_string(),
            action: "stop_stream".to_string(),
            params: std::collections::HashMap::new(),
        };
        let _ = cmd_tx.send(cmd).await;
        tracing::info!(imei = %imei, "No more viewers, stopped device stream");
    }
}
