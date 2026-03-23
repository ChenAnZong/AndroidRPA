use axum::http::{Request, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use super::jwt;

/// JWT auth middleware for /api/ routes.
/// Extracts Bearer token from Authorization header or ?token= query param.
/// Sets x-user-id, x-username, x-role headers on the request for downstream handlers.
pub async fn jwt_auth_middleware(mut req: Request<axum::body::Body>, next: Next) -> Response {
    let path = req.uri().path().to_string();

    // Public routes that don't need auth
    let public_paths = [
        "/api/auth/login",
        "/api/auth/register",
    ];
    if public_paths.iter().any(|p| path == *p) {
        return next.run(req).await;
    }

    // Only protect /api/ routes
    if !path.starts_with("/api/") {
        return next.run(req).await;
    }

    // Extract token from Authorization: Bearer <token> or ?token=<token>
    let token = req
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(|s| s.to_string())
        .or_else(|| {
            req.uri().query().and_then(|q| {
                q.split('&').find_map(|pair| {
                    let mut parts = pair.splitn(2, '=');
                    if parts.next() == Some("token") {
                        parts.next().map(|s| s.to_string())
                    } else {
                        None
                    }
                })
            })
        });

    let token = match token {
        Some(t) => t,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                axum::Json(serde_json::json!({"error": "Missing authentication token"})),
            )
                .into_response();
        }
    };

    match jwt::verify_token(&token) {
        Ok(claims) => {
            // Inject user info into request extensions
            req.extensions_mut().insert(claims);
            next.run(req).await
        }
        Err(_) => (
            StatusCode::UNAUTHORIZED,
            axum::Json(serde_json::json!({"error": "Invalid or expired token"})),
        )
            .into_response(),
    }
}
