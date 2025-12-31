use crate::api::AppState;
use crate::auth::{JwtService, PasswordService};
use axum::{
    extract::Extension,
    http::StatusCode,
    response::Json,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct SignupRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub username: String,
    pub roles: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

pub async fn signup(
    Extension(state): Extension<AppState>,
    Extension(jwt_service): Extension<Arc<JwtService>>,
    Json(request): Json<SignupRequest>,
) -> Result<Json<AuthResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Check if user already exists
    if state
        .database
        .user_exists(&request.username)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Database error: {}", e),
                }),
            )
        })?
    {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "User already exists".to_string(),
            }),
        ));
    }

    // Hash password
    let hashed_password = PasswordService::hash_password(&request.password)
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to hash password: {}", e),
                }),
            )
        })?;

    // Register user with default role
    let roles = vec!["ROLE_USER".to_string()];
    state
        .database
        .register_user(&request.username, &hashed_password, &roles)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to register user: {}", e),
                }),
            )
        })?;

    // Generate JWT token
    let token = jwt_service.generate_token(&request.username, &roles).map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResponse {
                error: format!("Failed to generate token: {}", e),
            }),
        )
    })?;

    Ok(Json(AuthResponse {
        token,
        username: request.username,
        roles,
    }))
}

pub async fn login(
    Extension(state): Extension<AppState>,
    Extension(jwt_service): Extension<Arc<JwtService>>,
    Json(request): Json<LoginRequest>,
) -> Result<Json<AuthResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Load user
    let user = state
        .database
        .load_user(&request.username)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Database error: {}", e),
                }),
            )
        })?
        .ok_or_else(|| {
            (
                StatusCode::UNAUTHORIZED,
                Json(ErrorResponse {
                    error: "Invalid username or password".to_string(),
                }),
            )
        })?;

    // Verify password
    let is_valid = PasswordService::verify_password(&request.password, &user.password)
        .map_err(|e| {
            tracing::error!("Password verification error for user {}: {}", request.username, e);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to verify password: {}", e),
                }),
            )
        })?;

    if !is_valid {
        tracing::warn!("Password verification failed for user: {}", request.username);
        return Err((
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse {
                error: "Invalid username or password".to_string(),
            }),
        ));
    }

    // Generate JWT token
    let token = jwt_service
        .generate_token(&user.username, &user.roles)
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to generate token: {}", e),
                }),
            )
        })?;

    Ok(Json(AuthResponse {
        token,
        username: user.username,
        roles: user.roles,
    }))
}

