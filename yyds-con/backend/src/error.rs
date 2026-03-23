use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
#[allow(dead_code)]
pub enum AppError {
    #[error("Device not found: {0}")]
    DeviceNotFound(String),

    #[error("Device offline: {0}")]
    DeviceOffline(String),

    #[error("Command timeout: {0}")]
    CommandTimeout(String),

    #[error("Command failed: {0}")]
    CommandFailed(String),

    #[error("Bad request: {0}")]
    BadRequest(String),

    #[error("Unauthorized: {0}")]
    Unauthorized(String),

    #[error("Forbidden: {0}")]
    Forbidden(String),

    #[error("Internal error: {0}")]
    Internal(#[from] anyhow::Error),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::DeviceNotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::DeviceOffline(_) => (StatusCode::SERVICE_UNAVAILABLE, self.to_string()),
            AppError::CommandTimeout(_) => (StatusCode::GATEWAY_TIMEOUT, self.to_string()),
            AppError::CommandFailed(_) => (StatusCode::BAD_GATEWAY, self.to_string()),
            AppError::BadRequest(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, self.to_string()),
            AppError::Forbidden(_) => (StatusCode::FORBIDDEN, self.to_string()),
            AppError::Internal(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Internal server error".to_string(),
            ),
        };

        let body = axum::Json(json!({ "error": message }));
        (status, body).into_response()
    }
}
