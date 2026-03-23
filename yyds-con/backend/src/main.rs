mod alert;
mod api;
mod auth;
mod config;
mod device;
mod error;
mod scheduler;

use std::sync::Arc;

use axum::extract::ws::WebSocketUpgrade;
use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::Router;
use tower_http::compression::CompressionLayer;
use tower_http::cors::CorsLayer;

use auth::db::Db;
use auth::rate_limit::RateLimiter;
use device::registry::DeviceRegistry;

/// Combined state for routes that need both registry and db
#[derive(Clone)]
struct AppState {
    registry: Arc<DeviceRegistry>,
    db: Db,
    rate_limiter: Arc<RateLimiter>,
}

// Allow extracting Arc<DeviceRegistry> from AppState
impl axum::extract::FromRef<AppState> for Arc<DeviceRegistry> {
    fn from_ref(state: &AppState) -> Self {
        state.registry.clone()
    }
}

// Allow extracting Db from AppState
impl axum::extract::FromRef<AppState> for Db {
    fn from_ref(state: &AppState) -> Self {
        state.db.clone()
    }
}

// Allow extracting Arc<RateLimiter> from AppState
impl axum::extract::FromRef<AppState> for Arc<RateLimiter> {
    fn from_ref(state: &AppState) -> Self {
        state.rate_limiter.clone()
    }
}

#[derive(serde::Deserialize)]
struct DeviceWsQuery {
    imei: String,
    #[serde(default)]
    model: String,
    #[serde(default)]
    ver: u32,
    #[serde(default)]
    sw: u32,
    #[serde(default)]
    sh: u32,
    /// Device auth token (JWT)
    #[serde(default)]
    token: String,
}

async fn device_ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    Query(params): Query<DeviceWsQuery>,
) -> Result<impl IntoResponse, (StatusCode, axum::Json<serde_json::Value>)> {
    // Validate device token if provided
    if !params.token.is_empty() {
        match auth::jwt::verify_device_token(&params.token) {
            Ok(claims) => {
                if claims.imei != params.imei {
                    return Err((
                        StatusCode::UNAUTHORIZED,
                        axum::Json(serde_json::json!({"error": "Token IMEI mismatch"})),
                    ));
                }

                // Check user enabled + max_devices
                {
                    let db = state.db.lock().await;
                    let user_info = db.query_row(
                        "SELECT enabled, max_devices FROM users WHERE id = ?1",
                        rusqlite::params![claims.sub],
                        |row| Ok((row.get::<_, bool>(0)?, row.get::<_, i64>(1)?)),
                    );
                    match user_info {
                        Ok((enabled, max_devices)) => {
                            if !enabled {
                                return Err((
                                    StatusCode::FORBIDDEN,
                                    axum::Json(serde_json::json!({"error": "Account disabled"})),
                                ));
                            }
                            // Count current online devices for this user
                            let bound_imeis: Vec<String> = db
                                .prepare("SELECT imei FROM device_bindings WHERE user_id = ?1")
                                .and_then(|mut stmt| {
                                    stmt.query_map(rusqlite::params![claims.sub], |row| {
                                        row.get::<_, String>(0)
                                    })
                                    .map(|rows| rows.filter_map(|r| r.ok()).collect())
                                })
                                .unwrap_or_default();
                            let online_count = bound_imeis
                                .iter()
                                .filter(|imei| state.registry.devices.contains_key(imei.as_str()))
                                .count() as i64;
                            if online_count >= max_devices {
                                return Err((
                                    StatusCode::TOO_MANY_REQUESTS,
                                    axum::Json(serde_json::json!({
                                        "error": format!("已达最大设备并发数限制 ({})", max_devices)
                                    })),
                                ));
                            }
                        }
                        Err(_) => {
                            return Err((
                                StatusCode::UNAUTHORIZED,
                                axum::Json(serde_json::json!({"error": "User not found"})),
                            ));
                        }
                    }
                }

                let imei = params.imei.clone();
                let registry = state.registry.clone();
                let model = params.model.clone();
                let sw = params.sw;
                let sh = params.sh;
                let ver = params.ver;
                return Ok(ws.on_upgrade(move |socket| async move {
                    device::connection::handle_device_ws(socket, imei.clone(), model, sw, sh, ver, registry.clone()).await;
                }).into_response());
            }
            Err(_) => {
                return Err((
                    StatusCode::UNAUTHORIZED,
                    axum::Json(serde_json::json!({"error": "Invalid device token"})),
                ));
            }
        }
    }

    // Allow connection without token (backward compat / dev mode)
    let registry = state.registry.clone();
    let imei = params.imei;
    let model = params.model;
    let sw = params.sw;
    let sh = params.sh;
    let ver = params.ver;
    Ok(ws.on_upgrade(move |socket| {
        device::connection::handle_device_ws(socket, imei, model, sw, sh, ver, registry)
    }).into_response())
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "yyds_con_backend=info,tower_http=info".into()),
        )
        .init();

    // Initialize database
    let db = auth::db::init_db();
    let registry = Arc::new(DeviceRegistry::new());
    let rate_limiter = RateLimiter::new(db.clone());
    let schedule_store = api::scheduler::load_or_create_store();
    let run_store = api::scheduler::load_or_create_run_store();

    let app_state = AppState {
        registry: registry.clone(),
        db: db.clone(),
        rate_limiter: rate_limiter.clone(),
    };

    // Spawn periodic cleanup of old login attempts
    {
        let rl = rate_limiter.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(600)).await;
                rl.cleanup().await;
            }
        });
    }

    // Start scheduler engine
    scheduler::engine::spawn_scheduler(registry.clone(), schedule_store.clone(), run_store.clone());

    // ── Auth routes (public, no JWT required) ──
    let auth_routes = Router::new()
        .route("/api/auth/login", post(api::auth::login))
        .route("/api/auth/register", post(api::auth::register))
        .with_state(app_state.clone());

    // ── Device WebSocket endpoint ──
    let device_routes = Router::new()
        .route("/ws/device", get(device_ws_handler))
        .with_state(app_state.clone());

    // ── User routes (JWT required) ──
    let user_routes = Router::new()
        .route("/api/auth/me", get(api::auth::me))
        .route("/api/auth/change-password", post(api::auth::change_password))
        .route("/api/auth/devices", get(api::auth::my_devices))
        .route("/api/auth/bind-device", post(api::auth::bind_device))
        .route("/api/auth/unbind-device", post(api::auth::unbind_device))
        .with_state(db.clone());

    // ── Admin routes (JWT + admin role required) ──
    let admin_routes = Router::new()
        .route("/api/admin/stats", get(api::admin::admin_stats))
        .route("/api/admin/users", get(api::admin::list_users))
        .route(
            "/api/admin/users/{id}",
            get(api::admin::get_user)
                .put(api::admin::update_user)
                .delete(api::admin::delete_user),
        )
        .route("/api/admin/bindings", get(api::admin::list_all_bindings))
        .with_state(app_state.clone());

    // ── Browser API routes (JWT required) ──
    let api_routes = Router::new()
        // Device list & info
        .route("/api/devices", get(api::devices::list_devices))
        .route("/api/devices/{imei}", get(api::devices::get_device))
        .route(
            "/api/devices/{imei}/thumbnail",
            get(api::devices::get_thumbnail),
        )
        // Stream (P2P signaling + WS fallback)
        .route("/api/devices/{imei}/stream", get(api::stream::stream_ws))
        // Control
        .route("/api/devices/{imei}/touch", post(api::control::touch))
        .route("/api/devices/{imei}/swipe", post(api::control::swipe))
        .route("/api/devices/{imei}/shell", post(api::control::shell))
        .route("/api/devices/{imei}/key", post(api::control::key))
        .route("/api/devices/{imei}/text", post(api::control::text_input))
        .route(
            "/api/devices/{imei}/reboot-engine",
            post(api::control::reboot_engine),
        )
        .route("/api/batch/touch", post(api::control::batch_touch))
        .route("/api/batch/shell", post(api::control::batch_shell))
        .route("/api/batch/text", post(api::control::batch_text))
        // Paste
        .route("/api/devices/{imei}/paste", post(api::control::paste))
        .route("/api/batch/paste", post(api::control::batch_paste))
        // IME
        .route("/api/devices/{imei}/ime", get(api::control::ime_get).post(api::control::ime_set))
        .route("/api/devices/{imei}/ime/list", get(api::control::ime_list))
        .route("/api/batch/ime", post(api::control::batch_ime_set))
        .route(
            "/api/devices/{imei}/install-apk",
            post(api::control::install_apk),
        )
        .route("/api/batch/install-apk", post(api::control::batch_install_apk))
        .route(
            "/api/batch/reboot-engine",
            post(api::control::batch_reboot_engine),
        )
        // Projects
        .route(
            "/api/devices/{imei}/projects",
            get(api::projects::list_projects),
        )
        .route(
            "/api/devices/{imei}/projects/start",
            post(api::projects::start_project),
        )
        .route(
            "/api/devices/{imei}/projects/stop",
            post(api::projects::stop_project),
        )
        .route(
            "/api/devices/{imei}/projects/status",
            get(api::projects::project_status),
        )
        .route(
            "/api/batch/projects/start",
            post(api::projects::batch_start),
        )
        .route("/api/batch/projects/stop", post(api::projects::batch_stop))
        // Files
        .route(
            "/api/devices/{imei}/files",
            get(api::files::list_files).delete(api::files::delete_file),
        )
        .route(
            "/api/devices/{imei}/files/download",
            get(api::files::download_file),
        )
        .route(
            "/api/devices/{imei}/files/upload",
            post(api::files::upload_file),
        )
        .route(
            "/api/devices/{imei}/files/mkdir",
            post(api::files::mkdir),
        )
        .route(
            "/api/batch/files/upload",
            post(api::files::batch_upload_file)
                .layer(axum::extract::DefaultBodyLimit::max(25 * 1024 * 1024)),
        )
        // Logs
        .route("/api/devices/{imei}/log", get(api::logs::log_ws))
        // Logcat
        .route(
            "/api/devices/{imei}/logcat/dump",
            get(api::logcat::logcat_dump),
        )
        .route(
            "/api/devices/{imei}/logcat/clear",
            post(api::logcat::logcat_clear),
        )
        .route(
            "/api/devices/{imei}/logcat/buffers",
            get(api::logcat::logcat_buffers),
        )
        // IDE: code execution, screenshot, UI dump, file ops, pip
        .route(
            "/api/devices/{imei}/run-code",
            post(api::ide::run_code),
        )
        .route(
            "/api/devices/{imei}/screenshot",
            get(api::ide::screenshot),
        )
        .route(
            "/api/devices/{imei}/ui-dump",
            get(api::ide::ui_dump),
        )
        .route(
            "/api/devices/{imei}/foreground",
            get(api::ide::foreground),
        )
        .route(
            "/api/devices/{imei}/file/read",
            get(api::ide::file_read),
        )
        .route(
            "/api/devices/{imei}/file/write",
            post(api::ide::file_write),
        )
        .route(
            "/api/devices/{imei}/file/exists",
            get(api::ide::file_exists),
        )
        .route(
            "/api/devices/{imei}/file/rename",
            post(api::ide::file_rename),
        )
        .route(
            "/api/devices/{imei}/pip/list",
            get(api::ide::pip_list),
        )
        .route(
            "/api/devices/{imei}/pip/install",
            post(api::ide::pip_install),
        )
        .route(
            "/api/devices/{imei}/pip/uninstall",
            post(api::ide::pip_uninstall),
        )
        .route(
            "/api/devices/{imei}/click",
            post(api::ide::click),
        )
        // Phase 2: Pip extended
        .route(
            "/api/devices/{imei}/pip/outdated",
            get(api::ide::pip_outdated),
        )
        .route(
            "/api/devices/{imei}/pip/show",
            get(api::ide::pip_show),
        )
        .route(
            "/api/devices/{imei}/pip/upgrade",
            post(api::ide::pip_upgrade),
        )
        .route(
            "/api/devices/{imei}/pip/search",
            get(api::ide::pip_search),
        )
        // Phase 2: APK packaging
        .route(
            "/api/devices/{imei}/package/build",
            post(api::ide::package_build),
        )
        .route(
            "/api/devices/{imei}/package/list",
            get(api::ide::package_list),
        )
        .route(
            "/api/devices/{imei}/package/installed-apps",
            get(api::ide::package_installed_apps),
        )
        .route(
            "/api/devices/{imei}/package/app-icon",
            get(api::ide::package_app_icon),
        )
        // Phase 3: 图色工具 + OCR
        .route(
            "/api/devices/{imei}/get-color",
            post(api::ide::get_color),
        )
        .route(
            "/api/devices/{imei}/get-colors",
            post(api::ide::get_colors),
        )
        .route(
            "/api/devices/{imei}/find-color",
            post(api::ide::find_color),
        )
        .route(
            "/api/devices/{imei}/find-image",
            post(api::ide::find_image),
        )
        .route(
            "/api/devices/{imei}/match-image",
            post(api::ide::match_image),
        )
        .route(
            "/api/devices/{imei}/screen-ocr",
            get(api::ide::screen_ocr),
        )
        .route(
            "/api/devices/{imei}/image-ocr",
            post(api::ide::image_ocr),
        )
        // Agent control + history
        .route(
            "/api/devices/{imei}/agent/status",
            get(api::agent::agent_status),
        )
        .route(
            "/api/devices/{imei}/agent/run",
            post(api::agent::agent_run),
        )
        .route(
            "/api/devices/{imei}/agent/stop",
            post(api::agent::agent_stop),
        )
        .route(
            "/api/devices/{imei}/agent/config",
            get(api::agent::agent_config_get).put(api::agent::agent_config_set),
        )
        .route(
            "/api/devices/{imei}/agent/providers",
            get(api::agent::agent_providers),
        )
        .route(
            "/api/devices/{imei}/agent/models",
            get(api::agent::agent_models),
        )
        .route(
            "/api/devices/{imei}/agent/takeover",
            post(api::agent::agent_takeover),
        )
        .route(
            "/api/devices/{imei}/agent/resume",
            post(api::agent::agent_resume),
        )
        .route(
            "/api/devices/{imei}/agent/test-connection",
            post(api::agent::agent_test_connection),
        )
        .route(
            "/api/devices/{imei}/agent/history",
            get(api::agent::agent_history).delete(api::agent::agent_history_clear),
        )
        .route(
            "/api/devices/{imei}/agent/history/{run_id}",
            get(api::agent::agent_history_detail),
        )
        .with_state(registry.clone());

    // Runs state (needs registry + schedule_store + run_store)
    let runs_state = api::runs::RunsState {
        run_store: run_store.clone(),
        schedule_store: schedule_store.clone(),
        registry: registry.clone(),
    };

    // Scheduler routes (separate state)
    let scheduler_routes = Router::new()
        .route(
            "/api/schedules",
            get(api::scheduler::list_schedules).post(api::scheduler::create_schedule),
        )
        .route(
            "/api/schedules/{id}",
            put(api::scheduler::update_schedule).delete(api::scheduler::delete_schedule),
        )
        .with_state(schedule_store);

    // Task run routes
    let run_routes = Router::new()
        .route("/api/task-runs", get(api::runs::list_runs).delete(api::runs::clear_runs))
        .route("/api/task-runs/stats", get(api::runs::run_stats))
        .route("/api/task-runs/{run_id}", get(api::runs::get_run))
        .route("/api/schedules/{id}/runs", get(api::runs::list_schedule_runs))
        .route("/api/schedules/{id}/trigger", post(api::runs::trigger_schedule))
        .with_state(runs_state);

    // Serve frontend static files from ../frontend/dist (SPA fallback)
    let static_dir =
        std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../frontend/dist");
    let spa_fallback = tower_http::services::ServeDir::new(&static_dir)
        .fallback(tower_http::services::ServeFile::new(
            static_dir.join("index.html"),
        ));

    let spa_router = Router::new()
        .fallback_service(spa_fallback)
        .layer(axum::middleware::map_response(
            |uri: axum::http::Uri, mut resp: Response| async move {
                let cache_value = if uri.path().starts_with("/assets/") {
                    "public, max-age=31536000, immutable"
                } else {
                    "no-cache"
                };
                resp.headers_mut().insert(
                    header::CACHE_CONTROL,
                    cache_value.parse().unwrap(),
                );
                resp
            },
        ));

    let app = Router::new()
        .merge(auth_routes)
        .merge(device_routes)
        .merge(user_routes)
        .merge(admin_routes)
        .merge(api_routes)
        .merge(scheduler_routes)
        .merge(run_routes)
        .merge(spa_router)
        .layer(axum::middleware::from_fn(auth::middleware::jwt_auth_middleware))
        .layer(CompressionLayer::new())
        .layer(
            CorsLayer::new()
                .allow_origin(tower_http::cors::Any)
                .allow_methods(tower_http::cors::Any)
                .allow_headers(tower_http::cors::Any),
        );

    let addr = config::server_addr();
    tracing::info!("yyds-con server starting on {}", addr);
    tracing::info!("  Device WS: ws://{}:{}/ws/device", addr.ip(), addr.port());
    tracing::info!("  Browser:   http://{}:{}/", addr.ip(), addr.port());
    tracing::info!("  API:       http://{}:{}/api/", addr.ip(), addr.port());
    tracing::info!("  Default admin: admin / admin123");

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("Failed to bind to address");

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .expect("Server error");
}

async fn shutdown_signal() {
    tokio::signal::ctrl_c()
        .await
        .expect("Failed to install CTRL+C handler");
    tracing::info!("Shutdown signal received");
}
