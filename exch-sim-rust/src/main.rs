mod config;
mod models;
mod database;
mod auth;
mod websocket;
mod market_board;
mod order;
mod position;
mod api;
mod middleware;

use anyhow::Result;
use config::Config;
use database::DatabaseImpl;
use std::sync::Arc;
use tracing::info;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "exch_sim_rust=info".into()),
        )
        .init();

    info!("Starting ExchSim Rust Service...");

    // Load configuration
    let config_path = std::env::var("CONFIG_PATH").unwrap_or_else(|_| "config.toml".to_string());
    let config = Config::load(&config_path)?;
    info!("Configuration loaded from: {}", config_path);

    // Initialize PostgreSQL connection pool
    let database = DatabaseImpl::new(&config).await?;
    info!("Connected to PostgreSQL: {}@{}", config.postgres.user, config.postgres.host);

    // Initialize services
    let market_board_manager = market_board::manager::MarketBoardManager::new(config.clone());
    let position_manager = Arc::new(position::manager::PositionManager::new(database.clone(), config.clone()));
    let order_service = Arc::new(order::service::OrderService::new(
        database.clone(),
        market_board_manager.clone(),
        position_manager.clone(),
        config.clone(),
    ).await?);

    // Initialize WebSocket clients
    let mut bitflyer_client = websocket::bitflyer::BitflyerWebSocketClient::new(
        config.clone(),
        market_board_manager.clone(),
    );
    let mut gmo_client = websocket::gmo::GmoWebSocketClient::new(
        config.clone(),
        market_board_manager.clone(),
    );

    // Start WebSocket clients
    bitflyer_client.start().await?;
    gmo_client.start().await?;
    info!("WebSocket clients started");

    // Wait a bit for WebSocket connections to establish
    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

    // Initialize and start REST API server
    let app_state = api::AppState {
        config: config.clone(),
        database: Arc::new(database),
        order_service,
        position_manager,
        market_board_manager,
    };

    let app = api::create_app(app_state).await?;

    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", config.server.port)).await?;
    info!("REST API server listening on port {}", config.server.port);

    // Handle shutdown signal
    let shutdown_signal = async {
        tokio::signal::ctrl_c()
            .await
            .expect("Failed to install Ctrl+C handler");
        info!("Shutdown signal received");
    };

    // Start server
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal)
        .await?;

    // Stop WebSocket clients
    bitflyer_client.stop().await;
    gmo_client.stop().await;
    info!("ExchSim Rust Service stopped");

    Ok(())
}

