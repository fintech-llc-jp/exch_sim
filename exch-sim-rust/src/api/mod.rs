pub mod auth;
pub mod orders;
pub mod market_board;
pub mod positions;
pub mod market_make;
pub mod executions;

use crate::config::Config;
use crate::database::DatabaseTrait;
use crate::market_board::manager::MarketBoardManager;
use crate::middleware::auth_middleware;
use crate::order::service::OrderService;
use crate::position::manager::PositionManager;
use axum::{
    extract::Extension,
    middleware,
    routing::{get, post},
    Router,
};
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub database: Arc<dyn DatabaseTrait>,
    pub order_service: Arc<OrderService>,
    pub position_manager: Arc<PositionManager>,
    pub market_board_manager: MarketBoardManager,
}

pub async fn create_app(state: AppState) -> Result<Router, anyhow::Error> {
    let jwt_service = Arc::new(crate::auth::JwtService::new(&state.config)?);

    // Public routes (no authentication required)
    let public_routes = Router::new()
        .route("/api/auth/signup", post(auth::signup))
        .route("/api/auth/login", post(auth::login))
        .layer(Extension(jwt_service.clone()));

    // Protected routes (authentication required)
    let protected_routes = Router::new()
        .route("/api/orders/new", post(orders::new_order))
        .route("/api/orders/cancel", post(orders::cancel_order))
        .route("/api/orders/list", get(orders::list_orders))
        .route("/api/market-board/:symbol", get(market_board::get_market_board))
        .route("/api/market/board/:symbol", get(market_board::get_market_board))
        .route("/api/positions/summary", get(positions::get_positions_summary))
        .route("/api/positions/:symbol", get(positions::get_position))
        .route("/api/trade-history", get(positions::get_trade_history))
        .route("/api/positions/trades", get(positions::get_trade_history))
        .route("/api/executions/history", get(executions::get_execution_history))
        .route("/api/executions/all", get(executions::get_all_execution_history))
        .route("/api/executions/volume", get(executions::get_volume_calculation))
        .route("/api/market-make/orders", post(market_make::market_make_orders))
        .layer(middleware::from_fn(auth_middleware))
        .layer(Extension(jwt_service.clone()));

    let app = Router::new()
        .merge(public_routes)
        .merge(protected_routes)
        .layer(Extension(state));

    Ok(app)
}

