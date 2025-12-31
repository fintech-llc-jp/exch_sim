use crate::api::AppState;
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

#[derive(Debug, Deserialize)]
pub struct MarketBoardQuery {
    #[serde(default = "default_depth")]
    depth: u32,
}

fn default_depth() -> u32 {
    10
}

pub async fn get_market_board(
    Extension(state): Extension<AppState>,
    Path(symbol): Path<String>,
    Query(params): Query<MarketBoardQuery>,
) -> Result<Json<crate::models::MarketBoardResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Validate depth parameter
    if params.depth == 0 || params.depth > 100 {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "Depth must be between 1 and 100".to_string(),
            }),
        ));
    }

    // Get or create board (this ensures board exists even if empty)
    let board = state
        .market_board_manager
        .get_or_create_board(symbol.clone())
        .await;

    // Get instrument config for price/qty multipliers
    let instrument = state
        .config
        .get_instrument(&symbol)
        .ok_or_else(|| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Invalid symbol: {}", symbol),
                }),
            )
        })?;

    let price_multiplier = instrument.price_multiplier as f64;
    let qty_multiplier = instrument.qty_multiplier as f64;

    let (bids, asks, as_of) = {
        let board_guard = board.read().await;
        let (raw_bids, raw_asks) = board_guard.get_snapshot(params.depth as usize);
        let as_of = board_guard.get_last_order_time();
        
        // Convert raw prices and quantities to actual values
        let bids: Vec<crate::models::PriceLevel> = raw_bids
            .into_iter()
            .map(|p| crate::models::PriceLevel {
                price: p.price / price_multiplier,
                quantity: p.quantity / qty_multiplier,
            })
            .collect();
        
        let asks: Vec<crate::models::PriceLevel> = raw_asks
            .into_iter()
            .map(|p| crate::models::PriceLevel {
                price: p.price / price_multiplier,
                quantity: p.quantity / qty_multiplier,
            })
            .collect();
        
        // Log and validate spread
        if !bids.is_empty() && !asks.is_empty() {
            let best_bid = bids[0].price;
            let best_ask = asks[0].price;
            let spread = best_ask - best_bid;
            
            tracing::info!(
                "MarketBoard API {}: best_bid={}, best_ask={}, spread={}",
                symbol,
                best_bid,
                best_ask,
                spread
            );
            
            if spread < 0.0 {
                tracing::warn!(
                    "MarketBoard API {}: Negative spread detected! best_bid={}, best_ask={}, spread={}",
                    symbol,
                    best_bid,
                    best_ask,
                    spread
                );
            }
        } else {
            tracing::debug!(
                "MarketBoard API {}: Empty board - bids={}, asks={}",
                symbol,
                bids.len(),
                asks.len()
            );
        }
        
        (bids, asks, as_of)
    };

    Ok(Json(crate::models::MarketBoardResponse {
        symbol,
        bids,
        asks,
        as_of,
    }))
}

