use std::sync::Arc;

use axum::extract::ws::{Message, WebSocket};
use axum::extract::{Path, State, WebSocketUpgrade};
use axum::response::IntoResponse;
use futures_util::{SinkExt, StreamExt};

use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

pub async fn log_ws(
    ws: WebSocketUpgrade,
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    if !registry.devices.contains_key(&imei) {
        return Err(AppError::DeviceNotFound(imei));
    }
    Ok(ws.on_upgrade(move |socket| handle_log_ws(socket, imei, registry)))
}

async fn handle_log_ws(socket: WebSocket, imei: String, registry: Arc<DeviceRegistry>) {
    let (mut ws_tx, mut ws_rx) = socket.split();

    let log_tx = match registry.get_log_tx(&imei) {
        Some(tx) => tx,
        None => return,
    };
    let mut log_rx = log_tx.subscribe();

    tracing::info!(imei = %imei, "Log viewer connected");

    loop {
        tokio::select! {
            msg = ws_rx.next() => {
                match msg {
                    Some(Ok(Message::Close(_))) | None => break,
                    _ => {}
                }
            }
            log = log_rx.recv() => {
                match log {
                    Ok(text) => {
                        if ws_tx.send(Message::Text(text.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        tracing::debug!(imei = %imei, lagged = n, "Log relay lagged");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    }

    tracing::info!(imei = %imei, "Log viewer disconnected");
}
