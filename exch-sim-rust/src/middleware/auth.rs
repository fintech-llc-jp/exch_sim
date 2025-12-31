use crate::auth::JwtService;
use axum::{
    extract::{Extension, Request},
    http::{header::AUTHORIZATION, StatusCode},
    middleware::Next,
    response::Response,
};
use std::sync::Arc;

#[derive(Clone)]
pub struct AuthState {
    pub username: String,
    pub roles: Vec<String>,
}

pub async fn auth_middleware(
    Extension(jwt_service): Extension<Arc<JwtService>>,
    mut req: Request,
    next: Next,
) -> Result<Response, StatusCode> {
    let auth_header = req
        .headers()
        .get(AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    if !auth_header.starts_with("Bearer ") {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let token = &auth_header[7..];
    let token_info = jwt_service
        .validate_token(token)
        .map_err(|_| StatusCode::UNAUTHORIZED)?;

    // Store auth state in request extensions
    req.extensions_mut().insert(AuthState {
        username: token_info.username,
        roles: token_info.roles,
    });

    Ok(next.run(req).await)
}

pub fn require_role(required_role: &str) -> impl Fn(&AuthState) -> bool {
    let role = required_role.to_string();
    move |auth_state: &AuthState| auth_state.roles.contains(&role)
}

