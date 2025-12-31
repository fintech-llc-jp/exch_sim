use crate::api::AppState;
use crate::middleware::AuthState;
use crate::models::{CancelOrderRequest, NewOrderRequest};
use axum::{
    extract::{Extension, Query},
    http::StatusCode,
    response::Json,
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

pub async fn new_order(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Json(request): Json<NewOrderRequest>,
) -> Result<Json<crate::models::OrderResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    state
        .order_service
        .process_new_order(username, &request)
        .await
        .map_err(|e| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Failed to process order: {}", e),
                }),
            )
        })
        .map(Json)
}

pub async fn cancel_order(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Json(request): Json<CancelOrderRequest>,
) -> Result<Json<crate::models::OrderResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    state
        .order_service
        .cancel_order(username, &request.cl_ord_id, &request.symbol)
        .await
        .map_err(|e| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Failed to cancel order: {}", e),
                }),
            )
        })
        .map(Json)
}

#[derive(Debug, Deserialize)]
pub struct OrderListQuery {
    pub symbol: Option<String>,
    pub status: Option<String>,
}

pub async fn list_orders(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Query(params): Query<OrderListQuery>,
) -> Result<Json<crate::models::OrderListResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    state
        .order_service
        .get_order_list(username, params.symbol.as_deref(), params.status.as_deref())
        .await
        .map_err(|e| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Failed to get order list: {}", e),
                }),
            )
        })
        .map(Json)
}

