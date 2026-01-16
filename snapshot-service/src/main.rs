mod config;
mod market_board;
mod models;
mod postgres_writer;
mod snapshot_collector;
mod websocket;

use anyhow::{Context, Result};
use config::Config;
use market_board::MarketBoardManager;
use postgres_writer::PostgresWriter;
use snapshot_collector::SnapshotCollector;
use sqlx::PgPool;
use std::sync::Arc;
use tokio::signal;
use tokio::sync::mpsc;
use tracing::{error, info};
use websocket::{BitflyerWebSocketClient, GmoWebSocketClient};

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "snapshot_service=info".into()),
        )
        .init();

    info!("Starting Snapshot Service...");

    // Load configuration
    let config_path = std::env::var("CONFIG_PATH").unwrap_or_else(|_| "config.toml".to_string());
    let config = Config::load(&config_path)
        .context("Failed to load configuration")?;
    info!("Configuration loaded from: {}", config_path);

    // Initialize PostgreSQL connection pool
    let database_url = config.get_database_url();
    let pool = PgPool::connect(&database_url)
        .await
        .context("Failed to connect to PostgreSQL")?;
    info!("Connected to PostgreSQL: {}@{}", config.postgres.user, config.postgres.host);

    // Initialize MarketBoard manager
    let board_manager = MarketBoardManager::new();

    // Create channel for snapshots
    let (snapshot_tx, snapshot_rx) = mpsc::channel(config.snapshot.queue_size_limit);

    // Initialize WebSocket clients
    let pool_arc = Arc::new(pool.clone());
    let mut bitflyer_client = BitflyerWebSocketClient::new(config.clone(), board_manager.clone());
    let mut gmo_client = GmoWebSocketClient::new(config.clone(), board_manager.clone(), pool_arc);

    // Start WebSocket clients
    bitflyer_client.start().await?;
    gmo_client.start().await?;
    info!("WebSocket clients started");

    // Wait a bit for WebSocket connections to establish
    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

    // Initialize and start snapshot collector
    let collector = SnapshotCollector::new(config.clone(), board_manager.clone(), snapshot_tx);
    collector.start().await;
    info!("Snapshot collector started");

    // Initialize and start PostgreSQL writer
    let mut writer = PostgresWriter::new(config.clone(), pool, snapshot_rx).await?;
    info!("PostgreSQL writer initialized");

    // Handle shutdown signal
    let writer_handle = tokio::spawn(async move {
        if let Err(e) = writer.start().await {
            error!("PostgreSQL writer error: {}", e);
        }
    });

    info!("Snapshot Service is running. Press Ctrl+C to stop.");

    // Wait for shutdown signal
    match signal::ctrl_c().await {
        Ok(()) => {
            info!("Shutdown signal received");
        }
        Err(err) => {
            error!("Unable to listen for shutdown signal: {}", err);
        }
    }

    // Stop WebSocket clients
    bitflyer_client.stop().await;
    gmo_client.stop().await;
    info!("WebSocket clients stopped");

    // Wait for writer to finish
    writer_handle.abort();
    info!("Snapshot Service stopped");

    Ok(())
}

