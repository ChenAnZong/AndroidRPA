use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::Json;

use super::rpc::send_cmd_and_wait;
use crate::device::registry::DeviceRegistry;
use crate::error::AppError;

// ── Request types ──

#[derive(serde::Deserialize)]
pub struct RunCodeRequest {
    pub code: String,
}

#[derive(serde::Deserialize)]
pub struct FilePathQuery {
    pub path: String,
}

#[derive(serde::Deserialize)]
pub struct FileWriteRequest {
    pub path: String,
    pub content: String,
}

#[derive(serde::Deserialize)]
pub struct FileRenameRequest {
    pub old_path: String,
    pub new_path: String,
}

#[derive(serde::Deserialize)]
pub struct PipPackageRequest {
    pub name: String,
}

#[derive(serde::Deserialize)]
pub struct ClickRequest {
    pub x: i32,
    pub y: i32,
}

// ── Handlers ──

/// Execute a Python code snippet on device
pub async fn run_code(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<RunCodeRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("code".into(), serde_json::json!(req.code));
    let result = send_cmd_and_wait(&registry, &imei, "run_code", params, 60).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Get a single screenshot as base64
pub async fn screenshot(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "screenshot", HashMap::new(), 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Get UI hierarchy XML dump
pub async fn ui_dump(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "ui_dump", HashMap::new(), 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Get foreground app info
pub async fn foreground(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "foreground", HashMap::new(), 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Read file text content from device
pub async fn file_read(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<FilePathQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".into(), serde_json::json!(q.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_read", params, 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Write text content to a file on device
pub async fn file_write(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<FileWriteRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".into(), serde_json::json!(req.path));
    params.insert("content".into(), serde_json::json!(req.content));
    let result = send_cmd_and_wait(&registry, &imei, "file_write", params, 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Check if a file exists on device
pub async fn file_exists(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<FilePathQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("path".into(), serde_json::json!(q.path));
    let result = send_cmd_and_wait(&registry, &imei, "file_exists", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Rename a file on device
pub async fn file_rename(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<FileRenameRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("old_path".into(), serde_json::json!(req.old_path));
    params.insert("new_path".into(), serde_json::json!(req.new_path));
    let result = send_cmd_and_wait(&registry, &imei, "file_rename", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// List pip packages
pub async fn pip_list(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "pip_list", HashMap::new(), 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Install a pip package
pub async fn pip_install(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<PipPackageRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".into(), serde_json::json!(req.name));
    let result = send_cmd_and_wait(&registry, &imei, "pip_install", params, 120).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Uninstall a pip package
pub async fn pip_uninstall(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<PipPackageRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".into(), serde_json::json!(req.name));
    let result = send_cmd_and_wait(&registry, &imei, "pip_uninstall", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Click at coordinates (for DevTool)
pub async fn click(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<ClickRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("x".into(), serde_json::json!(req.x));
    params.insert("y".into(), serde_json::json!(req.y));
    let result = send_cmd_and_wait(&registry, &imei, "touch", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

// ── Phase 2: Pip extended + Package endpoints ──

#[derive(serde::Deserialize)]
pub struct PipNameQuery {
    pub name: String,
}

#[derive(serde::Deserialize)]
pub struct PackageBuildRequest {
    #[serde(rename = "appName")]
    pub app_name: String,
    #[serde(rename = "projectName")]
    pub project_name: String,
    #[serde(default = "default_version")]
    pub version: String,
    #[serde(rename = "packageName", default)]
    pub package_name: Option<String>,
    #[serde(rename = "iconPath", default)]
    pub icon_path: Option<String>,
    #[serde(rename = "autoRunOnOpen", default)]
    pub auto_run_on_open: bool,
    #[serde(rename = "keepScreenOn", default = "default_true")]
    pub keep_screen_on: bool,
    #[serde(rename = "showLog", default = "default_true")]
    pub show_log: bool,
    #[serde(rename = "exitOnScriptStop", default)]
    pub exit_on_script_stop: bool,
    #[serde(rename = "encryptScripts", default)]
    pub encrypt_scripts: bool,
}

fn default_version() -> String { "1.0".into() }
fn default_true() -> bool { true }

#[derive(serde::Deserialize)]
pub struct PackageIconQuery {
    pub pkg: String,
}

/// List outdated pip packages
pub async fn pip_outdated(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "pip_outdated", HashMap::new(), 60).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Show pip package details
pub async fn pip_show(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<PipNameQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".into(), serde_json::json!(q.name));
    let result = send_cmd_and_wait(&registry, &imei, "pip_show", params, 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Upgrade a pip package
pub async fn pip_upgrade(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<PipPackageRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".into(), serde_json::json!(req.name));
    let result = send_cmd_and_wait(&registry, &imei, "pip_upgrade", params, 120).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Search PyPI for a package
pub async fn pip_search(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<PipNameQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("name".into(), serde_json::json!(q.name));
    let result = send_cmd_and_wait(&registry, &imei, "pip_search", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Build APK package
pub async fn package_build(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<PackageBuildRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("appName".into(), serde_json::json!(req.app_name));
    params.insert("projectName".into(), serde_json::json!(req.project_name));
    params.insert("version".into(), serde_json::json!(req.version));
    if let Some(ref pkg) = req.package_name {
        params.insert("packageName".into(), serde_json::json!(pkg));
    }
    if let Some(ref icon) = req.icon_path {
        params.insert("iconPath".into(), serde_json::json!(icon));
    }
    params.insert("autoRunOnOpen".into(), serde_json::json!(req.auto_run_on_open));
    params.insert("keepScreenOn".into(), serde_json::json!(req.keep_screen_on));
    params.insert("showLog".into(), serde_json::json!(req.show_log));
    params.insert("exitOnScriptStop".into(), serde_json::json!(req.exit_on_script_stop));
    params.insert("encryptScripts".into(), serde_json::json!(req.encrypt_scripts));
    let result = send_cmd_and_wait(&registry, &imei, "package_build", params, 180).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// List built APK packages
pub async fn package_list(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "package_list", HashMap::new(), 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// List installed apps on device
pub async fn package_installed_apps(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    let result = send_cmd_and_wait(&registry, &imei, "package_installed_apps", HashMap::new(), 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Get app icon as base64
pub async fn package_app_icon(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(q): Query<PackageIconQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("pkg".into(), serde_json::json!(q.pkg));
    let result = send_cmd_and_wait(&registry, &imei, "package_app_icon", params, 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

// ── Phase 3: 图色工具 + OCR API ──

#[derive(serde::Deserialize)]
pub struct GetColorRequest {
    pub x: i32,
    pub y: i32,
    #[serde(default)]
    pub image: Option<String>,
}

#[derive(serde::Deserialize)]
pub struct GetColorsRequest {
    pub points: String,  // "x1,y1 x2,y2 ..."
    #[serde(default)]
    pub image: Option<String>,
}

#[derive(serde::Deserialize)]
pub struct FindColorRequest {
    pub rgb: String,           // base color "R,G,B"
    pub points: String,        // bias points, newline-separated
    #[serde(default = "default_3")]
    pub prob: i32,             // tolerance/threshold
    #[serde(default = "default_1")]
    pub max_counts: i32,
    #[serde(default = "default_5")]
    pub step_x: i32,
    #[serde(default = "default_5")]
    pub step_y: i32,
    #[serde(default)]
    pub image: Option<String>,
    #[serde(default)]
    pub x: Option<String>,
    #[serde(default)]
    pub y: Option<String>,
    #[serde(default)]
    pub w: Option<String>,
    #[serde(default)]
    pub h: Option<String>,
}

fn default_3() -> i32 { 3 }
fn default_1() -> i32 { 1 }
fn default_5() -> i32 { 5 }

#[derive(serde::Deserialize)]
pub struct FindImageRequest {
    pub templates: String,     // semicolon-separated template paths
    #[serde(default = "default_neg1")]
    pub threshold: i32,
    #[serde(default)]
    pub image: Option<String>,
    #[serde(default)]
    pub x: Option<String>,
    #[serde(default)]
    pub y: Option<String>,
    #[serde(default)]
    pub w: Option<String>,
    #[serde(default)]
    pub h: Option<String>,
}

fn default_neg1() -> i32 { -1 }

#[derive(serde::Deserialize)]
pub struct MatchImageRequest {
    pub template: String,
    #[serde(default = "default_0")]
    pub threshold: i32,
    #[serde(default = "default_prob_08")]
    pub prob: f64,
    #[serde(default)]
    pub image: Option<String>,
    #[serde(default)]
    pub x: Option<String>,
    #[serde(default)]
    pub y: Option<String>,
    #[serde(default)]
    pub w: Option<String>,
    #[serde(default)]
    pub h: Option<String>,
}

fn default_0() -> i32 { 0 }
fn default_prob_08() -> f64 { 0.8 }

#[derive(serde::Deserialize)]
#[allow(dead_code)]
pub struct OcrRequest {
    #[serde(default)]
    pub image: Option<String>,
    #[serde(default)]
    pub use_gpu: Option<bool>,
    #[serde(default)]
    pub threshold: Option<i32>,
    #[serde(default)]
    pub x: Option<String>,
    #[serde(default)]
    pub y: Option<String>,
    #[serde(default)]
    pub w: Option<String>,
    #[serde(default)]
    pub h: Option<String>,
}

/// Get pixel color at (x, y)
pub async fn get_color(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<GetColorRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("x".into(), serde_json::json!(req.x));
    params.insert("y".into(), serde_json::json!(req.y));
    if let Some(ref img) = req.image {
        params.insert("image".into(), serde_json::json!(img));
    }
    let result = send_cmd_and_wait(&registry, &imei, "get_color", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Get pixel colors at multiple points
pub async fn get_colors(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<GetColorsRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("points".into(), serde_json::json!(req.points));
    if let Some(ref img) = req.image {
        params.insert("image".into(), serde_json::json!(img));
    }
    let result = send_cmd_and_wait(&registry, &imei, "get_color", params, 10).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Find color (multi-point color matching)
pub async fn find_color(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<FindColorRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("rgb".into(), serde_json::json!(req.rgb));
    params.insert("points".into(), serde_json::json!(req.points));
    params.insert("prob".into(), serde_json::json!(req.prob));
    params.insert("max_counts".into(), serde_json::json!(req.max_counts));
    params.insert("step_x".into(), serde_json::json!(req.step_x));
    params.insert("step_y".into(), serde_json::json!(req.step_y));
    if let Some(ref img) = req.image {
        params.insert("image".into(), serde_json::json!(img));
    }
    if let Some(ref v) = req.x { params.insert("x".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.y { params.insert("y".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.w { params.insert("w".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.h { params.insert("h".into(), serde_json::json!(v)); }
    let result = send_cmd_and_wait(&registry, &imei, "find_color", params, 15).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Find image (template matching)
pub async fn find_image(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<FindImageRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("templates".into(), serde_json::json!(req.templates));
    params.insert("threshold".into(), serde_json::json!(req.threshold));
    if let Some(ref img) = req.image {
        params.insert("image".into(), serde_json::json!(img));
    }
    if let Some(ref v) = req.x { params.insert("x".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.y { params.insert("y".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.w { params.insert("w".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.h { params.insert("h".into(), serde_json::json!(v)); }
    let result = send_cmd_and_wait(&registry, &imei, "find_image", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Match image (single template with probability)
pub async fn match_image(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<MatchImageRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    params.insert("template".into(), serde_json::json!(req.template));
    params.insert("threshold".into(), serde_json::json!(req.threshold));
    params.insert("prob".into(), serde_json::json!(req.prob));
    if let Some(ref img) = req.image {
        params.insert("image".into(), serde_json::json!(img));
    }
    if let Some(ref v) = req.x { params.insert("x".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.y { params.insert("y".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.w { params.insert("w".into(), serde_json::json!(v)); }
    if let Some(ref v) = req.h { params.insert("h".into(), serde_json::json!(v)); }
    let result = send_cmd_and_wait(&registry, &imei, "match_image", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Screen OCR (capture screen and recognize text)
pub async fn screen_ocr(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Query(req): Query<OcrRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    if let Some(gpu) = req.use_gpu {
        params.insert("use_gpu".into(), serde_json::json!(gpu));
    }
    if let Some(t) = req.threshold {
        params.insert("threshold".into(), serde_json::json!(t));
    }
    let result = send_cmd_and_wait(&registry, &imei, "screen_ocr", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}

/// Image OCR (recognize text from a saved image file)
pub async fn image_ocr(
    State(registry): State<Arc<DeviceRegistry>>,
    Path(imei): Path<String>,
    Json(req): Json<OcrRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let mut params = HashMap::new();
    if let Some(ref path) = req.image {
        params.insert("path".into(), serde_json::json!(path));
    }
    if let Some(gpu) = req.use_gpu {
        params.insert("use_gpu".into(), serde_json::json!(gpu));
    }
    if let Some(t) = req.threshold {
        params.insert("threshold".into(), serde_json::json!(t));
    }
    let result = send_cmd_and_wait(&registry, &imei, "image_ocr", params, 30).await?;
    Ok(Json(serde_json::json!({"success": true, "data": result})))
}
