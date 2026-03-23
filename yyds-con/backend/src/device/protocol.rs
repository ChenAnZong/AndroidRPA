use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Messages sent from device to server
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DeviceMessage {
    Register {
        imei: String,
        model: String,
        sw: u32,
        sh: u32,
        ver: u32,
    },
    Response {
        id: String,
        success: bool,
        #[serde(default)]
        data: serde_json::Value,
    },
    Log {
        text: String,
    },
    Status {
        running: bool,
        #[serde(default)]
        project: String,
        #[serde(default)]
        fg: String,
    },
    RtcAnswer {
        browser_id: String,
        sdp: String,
    },
    RtcIce {
        browser_id: String,
        candidate: String,
    },
    /// Hint: next binary frame is a thumbnail
    Thumbnail,
}

/// Commands sent from server to device
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerCommand {
    Cmd {
        id: String,
        action: String,
        #[serde(default)]
        params: HashMap<String, serde_json::Value>,
    },
    RtcOffer {
        browser_id: String,
        sdp: String,
    },
    RtcIce {
        browser_id: String,
        candidate: String,
    },
}

/// Device info stored in registry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub imei: String,
    pub model: String,
    pub screen_width: u32,
    pub screen_height: u32,
    pub version: u32,
    pub online: bool,
    #[serde(with = "chrono::serde::ts_milliseconds")]
    pub connected_at: chrono::DateTime<chrono::Utc>,
    #[serde(with = "chrono::serde::ts_milliseconds")]
    pub last_seen: chrono::DateTime<chrono::Utc>,
    #[serde(default)]
    pub running_project: String,
    #[serde(default)]
    pub foreground_app: String,
}

/// Browser-side signaling messages (browser → server via stream WS)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum BrowserStreamMessage {
    StartStream {
        #[serde(default = "default_quality")]
        quality: u32,
        #[serde(default = "default_interval")]
        interval: u64,
        #[serde(default = "default_max_height")]
        max_height: u32,
    },
    StopStream,
    AdjustStream {
        #[serde(default)]
        quality: Option<u32>,
        #[serde(default)]
        interval: Option<u64>,
        #[serde(default)]
        max_height: Option<u32>,
    },
    RtcOffer {
        sdp: String,
    },
    RtcIce {
        candidate: String,
    },
}

fn default_quality() -> u32 {
    70
}

fn default_interval() -> u64 {
    33
}

fn default_max_height() -> u32 {
    1280
}

/// API response for device list
#[derive(Debug, Clone, Serialize)]
pub struct DeviceListItem {
    pub imei: String,
    pub model: String,
    pub screen_width: u32,
    pub screen_height: u32,
    pub version: u32,
    pub online: bool,
    pub connected_at: i64,
    pub last_seen: i64,
    pub running_project: String,
    pub foreground_app: String,
    pub stream_viewers: usize,
}
