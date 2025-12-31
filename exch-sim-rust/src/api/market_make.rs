use crate::api::AppState;
use crate::middleware::AuthState;
use crate::models::{MarketMakeOrderRequest, MarketMakeOrderResponse};
use axum::{
    extract::Extension,
    http::StatusCode,
    response::Json,
};
use serde::Serialize;
use once_cell::sync::Lazy;
use parking_lot::Mutex;

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

// MarketMake注文の管理（簡略化版）
static MARKET_MAKE_ORDERS: Lazy<Mutex<std::collections::HashMap<String, std::collections::HashSet<String>>>> = 
    Lazy::new(|| Mutex::new(std::collections::HashMap::new()));

#[axum::debug_handler]
pub async fn market_make_orders(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Json(request): Json<MarketMakeOrderRequest>,
) -> Result<Json<MarketMakeOrderResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Check if user has MARKET_MAKER role
    if !auth_state.roles.contains(&"ROLE_MARKET_MAKER".to_string()) {
        return Err((
            StatusCode::FORBIDDEN,
            Json(ErrorResponse {
                error: "Market maker role required".to_string(),
            }),
        ));
    }

    let username = &auth_state.username;
    let symbol = &request.symbol;

    // Get symbol lock (simplified - using a global lock per symbol)
    let order_ids_to_cancel: Vec<String> = {
        let orders_map = MARKET_MAKE_ORDERS.lock();
        let user_orders = orders_map.get(username).cloned().unwrap_or_default();
        user_orders.into_iter().collect()
    };

    // 1. Cancel all existing market make orders for this symbol
    let mut cancelled_count = 0;
    for order_id in &order_ids_to_cancel {
        if let Ok(_) = state
            .order_service
            .cancel_order(username, order_id, symbol)
            .await
        {
            let mut orders_map = MARKET_MAKE_ORDERS.lock();
            if let Some(user_orders) = orders_map.get_mut(username) {
                user_orders.remove(order_id);
            }
            cancelled_count += 1;
        }
    }

    // 2. Place new orders
    let mut bid_order_ids = Vec::new();
    let mut ask_order_ids = Vec::new();

    if let Some(bid_levels) = &request.bid_levels {
        for level in bid_levels {
            let new_order_request = crate::models::NewOrderRequest {
                symbol: symbol.clone(),
                side: "BUY".to_string(),
                ord_type: level.ord_type.clone(),
                price: Some(level.price),
                quantity: level.quantity,
                tif: level.tif.clone(),
                is_market_make: true,
            };

            if let Ok(response) = state
                .order_service
                .process_new_order(username, &new_order_request)
                .await
            {
                bid_order_ids.push(response.cl_ord_id.clone());
                let mut orders_map = MARKET_MAKE_ORDERS.lock();
                orders_map
                    .entry(username.clone())
                    .or_insert_with(std::collections::HashSet::new)
                    .insert(response.cl_ord_id);
            }
        }
    }

    if let Some(ask_levels) = &request.ask_levels {
        for level in ask_levels {
            let new_order_request = crate::models::NewOrderRequest {
                symbol: symbol.clone(),
                side: "SELL".to_string(),
                ord_type: level.ord_type.clone(),
                price: Some(level.price),
                quantity: level.quantity,
                tif: level.tif.clone(),
                is_market_make: true,
            };

            if let Ok(response) = state
                .order_service
                .process_new_order(username, &new_order_request)
                .await
            {
                ask_order_ids.push(response.cl_ord_id.clone());
                let mut orders_map = MARKET_MAKE_ORDERS.lock();
                orders_map
                    .entry(username.clone())
                    .or_insert_with(std::collections::HashSet::new)
                    .insert(response.cl_ord_id);
            }
        }
    }

    Ok(Json(MarketMakeOrderResponse {
        username: username.clone(),
        symbol: symbol.clone(),
        cancelled_orders_count: cancelled_count,
        new_bid_orders_count: bid_order_ids.len(),
        new_ask_orders_count: ask_order_ids.len(),
        bid_order_ids,
        ask_order_ids,
        message: "Market make orders processed successfully".to_string(),
    }))
}

