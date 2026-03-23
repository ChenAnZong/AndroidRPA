use std::sync::Arc;

use bytes::Bytes;
use dashmap::DashMap;
use tokio::sync::{broadcast, mpsc, oneshot};

use super::protocol::{DeviceInfo, ServerCommand};

/// RTC signaling message forwarded from device to a specific browser
#[derive(Debug, Clone)]
pub enum RtcSignal {
    Answer { sdp: String },
    Ice { candidate: String },
}

/// Per-device state held in the registry
pub struct DeviceState {
    pub info: DeviceInfo,
    /// Send commands to the device's WS connection loop
    pub cmd_tx: mpsc::Sender<ServerCommand>,
    /// Broadcast channel for screenshot frames (WS fallback mode)
    pub frame_tx: broadcast::Sender<Bytes>,
    /// Broadcast channel for log lines
    pub log_tx: broadcast::Sender<String>,
    /// Latest thumbnail frame (cached for HTTP GET)
    pub thumbnail: Arc<tokio::sync::RwLock<Option<Bytes>>>,
    /// Number of active stream viewers (for auto start/stop)
    pub viewer_count: Arc<std::sync::atomic::AtomicUsize>,
    /// Pending command responses: request_id → oneshot sender
    pub pending_responses: Arc<DashMap<String, oneshot::Sender<serde_json::Value>>>,
    /// RTC signaling: browser_id → mpsc sender (multiple ICE candidates)
    pub rtc_signal_txs: Arc<DashMap<String, mpsc::Sender<RtcSignal>>>,
}

/// Global device registry
pub struct DeviceRegistry {
    pub devices: DashMap<String, DeviceState>,
}

impl DeviceRegistry {
    pub fn new() -> Self {
        Self {
            devices: DashMap::new(),
        }
    }

    pub fn register(
        &self,
        imei: String,
        info: DeviceInfo,
        cmd_tx: mpsc::Sender<ServerCommand>,
    ) -> (broadcast::Sender<Bytes>, broadcast::Sender<String>) {
        let (frame_tx, _) = broadcast::channel(64); // 64 frames buffer for 30fps streaming
        let (log_tx, _) = broadcast::channel(256);

        let frame_tx_clone = frame_tx.clone();
        let log_tx_clone = log_tx.clone();

        let state = DeviceState {
            info,
            cmd_tx,
            frame_tx,
            log_tx,
            thumbnail: Arc::new(tokio::sync::RwLock::new(None)),
            viewer_count: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            pending_responses: Arc::new(DashMap::new()),
            rtc_signal_txs: Arc::new(DashMap::new()),
        };

        // If device already registered (reconnect), drop old state to trigger old WS cleanup
        if let Some((_, old)) = self.devices.remove(&imei) {
            tracing::warn!(imei = %imei, "Device re-registering, kicking old connection");
            // Dropping old cmd_tx closes the channel → old send_task breaks → old connection cleans up
            drop(old);
        }
        self.devices.insert(imei, state);
        (frame_tx_clone, log_tx_clone)
    }

    pub fn unregister(&self, imei: &str) {
        if let Some((_, state)) = self.devices.remove(imei) {
            // Mark all pending responses as failed
            for entry in state.pending_responses.iter() {
                // oneshot senders will be dropped, receivers get error
                let _ = entry.value();
            }
            state.pending_responses.clear();
            tracing::info!(imei = imei, "Device unregistered");
        }
    }

    pub fn update_info<F>(&self, imei: &str, f: F)
    where
        F: FnOnce(&mut DeviceInfo),
    {
        if let Some(mut entry) = self.devices.get_mut(imei) {
            f(&mut entry.info);
        }
    }

    pub fn get_cmd_tx(&self, imei: &str) -> Option<mpsc::Sender<ServerCommand>> {
        self.devices.get(imei).map(|d| d.cmd_tx.clone())
    }

    pub fn get_frame_tx(&self, imei: &str) -> Option<broadcast::Sender<Bytes>> {
        self.devices.get(imei).map(|d| d.frame_tx.clone())
    }

    pub fn get_log_tx(&self, imei: &str) -> Option<broadcast::Sender<String>> {
        self.devices.get(imei).map(|d| d.log_tx.clone())
    }

    pub fn get_thumbnail(&self, imei: &str) -> Option<Arc<tokio::sync::RwLock<Option<Bytes>>>> {
        self.devices.get(imei).map(|d| d.thumbnail.clone())
    }

    pub fn get_viewer_count(&self, imei: &str) -> Option<Arc<std::sync::atomic::AtomicUsize>> {
        self.devices.get(imei).map(|d| d.viewer_count.clone())
    }

    pub fn get_pending_responses(
        &self,
        imei: &str,
    ) -> Option<Arc<DashMap<String, oneshot::Sender<serde_json::Value>>>> {
        self.devices.get(imei).map(|d| d.pending_responses.clone())
    }

    pub fn get_rtc_signal_txs(
        &self,
        imei: &str,
    ) -> Option<Arc<DashMap<String, mpsc::Sender<RtcSignal>>>> {
        self.devices.get(imei).map(|d| d.rtc_signal_txs.clone())
    }

    pub fn list_devices(&self) -> Vec<super::protocol::DeviceListItem> {
        self.devices
            .iter()
            .map(|entry| {
                let state = entry.value();
                super::protocol::DeviceListItem {
                    imei: state.info.imei.clone(),
                    model: state.info.model.clone(),
                    screen_width: state.info.screen_width,
                    screen_height: state.info.screen_height,
                    version: state.info.version,
                    online: state.info.online,
                    connected_at: state.info.connected_at.timestamp_millis(),
                    last_seen: state.info.last_seen.timestamp_millis(),
                    running_project: state.info.running_project.clone(),
                    foreground_app: state.info.foreground_app.clone(),
                    stream_viewers: state
                        .viewer_count
                        .load(std::sync::atomic::Ordering::Relaxed),
                }
            })
            .collect()
    }

    pub fn device_count(&self) -> usize {
        self.devices.len()
    }

    pub fn online_count(&self) -> usize {
        self.devices
            .iter()
            .filter(|e| e.value().info.online)
            .count()
    }
}
