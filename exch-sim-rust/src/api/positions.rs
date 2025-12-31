use crate::api::AppState;
use crate::middleware::AuthState;
use crate::models::{PortfolioSummaryResponse, PositionResponse, TradeHistoryDto, TradeHistoryResponse};
use axum::{
    extract::{Extension, Path, Query},
    http::StatusCode,
    response::Json,
};
use serde::Serialize;
use std::collections::HashMap;

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

async fn get_current_price(state: &AppState, symbol: &str) -> f64 {
    // Get market board to calculate mid price
    let board = state.market_board_manager.get_or_create_board(symbol.to_string()).await;
    let (bids, asks) = {
        let board_guard = board.read().await;
        board_guard.get_snapshot(1)
    };
    
    if !bids.is_empty() && !asks.is_empty() {
        return (bids[0].price + asks[0].price) / 2.0;
    }
    0.0
}

async fn get_current_prices(state: &AppState, positions: &[crate::position::position::Position]) -> HashMap<String, f64> {
    let mut prices = HashMap::new();
    for position in positions {
        if position.symbol != "JPY" {
            let price = get_current_price(state, &position.symbol).await;
            prices.insert(position.symbol.clone(), price);
        }
    }
    prices
}

pub async fn get_positions_summary(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
) -> Result<Json<PortfolioSummaryResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    let positions = state
        .position_manager
        .get_all_positions(username)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get positions: {}", e),
                }),
            )
        })?;

    // Get current prices for unrealized P/L calculation
    let current_prices = get_current_prices(&state, &positions).await;

    // Helper function to sanitize f64 values
    fn sanitize_value(value: f64) -> f64 {
        if value.is_nan() || value.is_infinite() {
            0.0
        } else if value == 0.0 && value.is_sign_negative() {
            0.0
        } else {
            value
        }
    }

    // Calculate portfolio metrics
    let total_realized_pnl = sanitize_value(
        state
            .position_manager
            .get_total_realized_pnl(username)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get total realized PnL: {}", e),
                    }),
                )
            })?
    );

    let total_unrealized_pnl = sanitize_value(
        state
            .position_manager
            .get_total_unrealized_pnl(username, &current_prices)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get total unrealized PnL: {}", e),
                    }),
                )
            })?
    );

    let total_pnl = sanitize_value(
        state
            .position_manager
            .get_total_pnl(username, &current_prices)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get total PnL: {}", e),
                    }),
                )
            })?
    );

    let total_trade_count = state
        .position_manager
        .get_total_trade_count(username)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get total trade count: {}", e),
                }),
            )
        })?;

    let total_trading_volume = sanitize_value(
        state
            .position_manager
            .get_total_trading_volume(username)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get total trading volume: {}", e),
                    }),
                )
            })?
    );

    let symbol_trade_counts = state
        .position_manager
        .get_symbol_trade_counts(username)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get symbol trade counts: {}", e),
                }),
            )
        })?;

    // Convert symbol_trade_counts from i64 to i64 (already correct)
    let symbol_trade_counts: HashMap<String, i64> = symbol_trade_counts;

    // Get cash balance
    let cash_balance = sanitize_value(
        state
            .position_manager
            .get_cash_balance(username)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get cash balance: {}", e),
                    }),
                )
            })?
    );

    // Convert positions to PositionResponse
    let position_responses: Vec<PositionResponse> = positions
        .into_iter()
        .filter(|p| p.symbol != "JPY") // Exclude cash position from list
        .map(|p| {
            let current_price = current_prices.get(&p.symbol).copied().unwrap_or(0.0);
            let unrealized_pnl = sanitize_value(p.get_unrealized_pnl(current_price));
            let total_pnl = sanitize_value(p.get_total_pnl(current_price));
            
            PositionResponse {
                username: p.username.clone(),
                symbol: p.symbol.clone(),
                unit: "BTC".to_string(), // TODO: Get from config
                total_buy_qty: sanitize_value(p.total_buy_qty),
                total_buy_amount: sanitize_value(p.total_buy_amount),
                total_sell_qty: sanitize_value(p.total_sell_qty),
                total_sell_amount: sanitize_value(p.total_sell_amount),
                net_qty: sanitize_value(p.net_qty),
                average_buy_price: sanitize_value(p.average_buy_price),
                average_sell_price: sanitize_value(p.average_sell_price),
                realized_pnl: sanitize_value(p.realized_pnl),
                unrealized_pnl,
                total_pnl,
                last_updated: p.last_updated,
            }
        })
        .collect();

    // Calculate total value (cash + position values)
    let positions_value: f64 = position_responses
        .iter()
        .map(|pos| {
            let current_price = current_prices.get(&pos.symbol).copied().unwrap_or(0.0);
            sanitize_value(pos.net_qty * current_price)
        })
        .sum();
    let total_value = sanitize_value(cash_balance + positions_value);

    // Debug: Log actual values before serialization
    tracing::info!(
        "PortfolioSummary values: total_realized_pnl={}, total_unrealized_pnl={}, total_pnl={}, is_nan={}, is_inf={}",
        total_realized_pnl,
        total_unrealized_pnl,
        total_pnl,
        total_realized_pnl.is_nan() || total_unrealized_pnl.is_nan() || total_pnl.is_nan(),
        total_realized_pnl.is_infinite() || total_unrealized_pnl.is_infinite() || total_pnl.is_infinite()
    );

    Ok(Json(PortfolioSummaryResponse {
        username: username.clone(),
        cash_balance,
        total_value,
        total_realized_pnl,
        total_unrealized_pnl,
        total_pnl,
        total_trade_count,
        total_trading_volume,
        positions: position_responses,
        symbol_trade_counts,
    }))
}

pub async fn get_position(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Path(symbol): Path<String>,
) -> Result<Json<PositionResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    let position = state
        .position_manager
        .get_position(username, &symbol)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get position: {}", e),
                }),
            )
        })?;

    // If position not found, return empty position
    let position = position.unwrap_or_else(|| {
        crate::position::position::Position::new(username.clone(), symbol.clone())
    });

    // Helper function to sanitize f64 values
    fn sanitize_value(value: f64) -> f64 {
        if value.is_nan() || value.is_infinite() {
            0.0
        } else if value == 0.0 && value.is_sign_negative() {
            0.0
        } else {
            value
        }
    }

    // Get current price
    let current_price = get_current_price(&state, &symbol).await;
    let unrealized_pnl = sanitize_value(position.get_unrealized_pnl(current_price));
    let total_pnl = sanitize_value(position.get_total_pnl(current_price));

    Ok(Json(PositionResponse {
        username: position.username.clone(),
        symbol: position.symbol.clone(),
        unit: "BTC".to_string(), // TODO: Get from config
        total_buy_qty: sanitize_value(position.total_buy_qty),
        total_buy_amount: sanitize_value(position.total_buy_amount),
        total_sell_qty: sanitize_value(position.total_sell_qty),
        total_sell_amount: sanitize_value(position.total_sell_amount),
        net_qty: sanitize_value(position.net_qty),
        average_buy_price: sanitize_value(position.average_buy_price),
        average_sell_price: sanitize_value(position.average_sell_price),
        realized_pnl: sanitize_value(position.realized_pnl),
        unrealized_pnl,
        total_pnl,
        last_updated: position.last_updated,
    }))
}

#[derive(Debug, serde::Deserialize)]
pub struct TradeHistoryQuery {
    #[serde(default = "default_limit")]
    limit: u32,
    symbol: Option<String>,
}

fn default_limit() -> u32 {
    50
}

pub async fn get_trade_history(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Query(params): Query<TradeHistoryQuery>,
) -> Result<Json<TradeHistoryResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    let trades = if let Some(symbol) = &params.symbol {
        state
            .position_manager
            .get_trade_history_by_symbol(username, symbol)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get trade history: {}", e),
                    }),
                )
            })?
    } else {
        state
            .position_manager
            .get_trade_history(username)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to get trade history: {}", e),
                    }),
                )
            })?
    };

    let trade_dtos: Vec<TradeHistoryDto> = trades
        .into_iter()
        .take(params.limit as usize)
        .map(|t| TradeHistoryDto {
            exec_id: t.exec_id,
            symbol: t.symbol,
            side: t.side,
            quantity: t.quantity,
            price: t.price,
            amount: t.amount,
            counter_party_username: t.counter_party_username,
            timestamp: t.timestamp,
            cl_ord_id: t.cl_ord_id,
            open_close: t.open_close,
            profit_loss: t.profit_loss,
        })
        .collect();

    Ok(Json(TradeHistoryResponse {
        username: username.clone(),
        total_count: trade_dtos.len() as i32,
        trades: trade_dtos,
    }))
}

