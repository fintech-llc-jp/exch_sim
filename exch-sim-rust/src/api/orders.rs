use crate::api::AppState;
use crate::middleware::AuthState;
use crate::models::{CancelOrderRequest, NewOrderRequest, OrderStatusResponse};
use axum::{
    extract::{Extension, Path, Query},
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
    tracing::debug!("Cancel order request: cl_ord_id={}, symbol={}", request.cl_ord_id, request.symbol);

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

#[derive(Debug, Deserialize)]
pub struct GetOrderStatusPath {
    pub cl_ord_id: String,
}

pub async fn get_order_status(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Path(path): Path<GetOrderStatusPath>,
) -> Result<Json<OrderStatusResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    match state
        .order_service
        .get_order_status(username, &path.cl_ord_id)
        .await
    {
        Ok(resp) => Ok(Json(resp)),
        Err(e) => {
            let msg = e.to_string();
            let status = if msg.contains("Order not found") {
                StatusCode::NOT_FOUND
            } else {
                StatusCode::BAD_REQUEST
            };
            Err((
                status,
                Json(ErrorResponse { error: msg }),
            ))
        }
    }
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

