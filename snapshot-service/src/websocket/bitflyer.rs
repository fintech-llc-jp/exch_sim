use crate::config::Config;
use crate::market_board::MarketBoardManager;
use anyhow::{Context, Result};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::PgPool;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{error, info, warn};
use uuid::Uuid;

#[derive(Debug, Serialize)]
struct SubscribeRequest {
    jsonrpc: String,
    method: String,
    params: SubscribeParams,
    id: u64,
}

#[derive(Debug, Serialize)]
struct SubscribeParams {
    channel: String,
}

#[derive(Debug, Deserialize)]
struct SubscribeResponse {
    jsonrpc: String,
    result: Option<serde_json::Value>,
    error: Option<serde_json::Value>,
    id: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct BoardMessage {
    #[serde(rename = "method")]
    method: Option<String>,  // methodフィールドはオプショナル（レスポンスには含まれない）
    params: BoardParams,
}

#[derive(Debug, Deserialize)]
struct BoardParams {
    channel: String,
    message: BoardData,
}

#[derive(Debug, Deserialize)]
struct BoardData {
    #[serde(default)]
    mid_price: Option<f64>,
    bids: Vec<PriceLevelData>,
    asks: Vec<PriceLevelData>,
}

#[derive(Debug, Deserialize)]
struct ChannelMessage {
    #[serde(rename = "method")]
    method: Option<String>,
    params: ChannelMessageParams,
}

#[derive(Debug, Deserialize)]
struct ChannelMessageParams {
    channel: String,
    message: serde_json::Value, // Can be BoardData or Vec<ExecutionData>
}

#[derive(Debug, Deserialize)]
struct ExecutionsMessage {
    #[serde(rename = "method")]
    method: Option<String>,
    params: ExecutionsParams,
}

#[derive(Debug, Deserialize)]
struct ExecutionsParams {
    channel: String,
    message: Vec<ExecutionData>,
}

#[derive(Debug, Deserialize)]
struct ExecutionData {
    id: i64,
    side: String,
    price: f64,
    size: f64,
    exec_date: String,
}

#[derive(Debug, Deserialize)]
struct PriceLevelData {
    price: f64,
    size: f64,
}

pub struct BitflyerWebSocketClient {
    config: Config,
    board_manager: MarketBoardManager,
    pool: Option<Arc<sqlx::PgPool>>,
    jsonrpc_id: AtomicU64,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl BitflyerWebSocketClient {
    pub fn new(config: Config, board_manager: MarketBoardManager) -> Self {
        Self {
            config,
            board_manager,
            pool: None,
            jsonrpc_id: AtomicU64::new(1),
            shutdown_tx: None,
        }
    }

    pub fn with_pool(mut self, pool: Arc<sqlx::PgPool>) -> Self {
        self.pool = Some(pool);
        self
    }

    pub async fn start(&mut self) -> Result<()> {
        if !self.config.websocket.bitflyer.enabled {
            info!("Bitflyer WebSocket is disabled");
            return Ok(());
        }

        let (shutdown_tx, mut shutdown_rx) = mpsc::channel(1);
        self.shutdown_tx = Some(shutdown_tx);

        let config = self.config.clone();
        let board_manager = self.board_manager.clone();
        let pool = self.pool.clone();
        let mut reconnect_attempts = 0u32;

        tokio::spawn(async move {
            let base_delay_ms = config.websocket.bitflyer.reconnect_delay_ms;
            let max_delay_ms = 60_000u64;

            loop {
                let result = Self::connect_and_run(&config, board_manager.clone(), pool.clone()).await;
                let delay_ms = match result {
                    Ok(_) => {
                        // Server-side close (e.g. daily maintenance) — reconnect after short delay
                        info!("Bitflyer WebSocket connection closed normally, reconnecting...");
                        reconnect_attempts = 0;
                        base_delay_ms
                    }
                    Err(e) => {
                        reconnect_attempts += 1;
                        let d = (base_delay_ms * (1u64 << reconnect_attempts.min(10))).min(max_delay_ms);
                        warn!(
                            "Bitflyer WebSocket reconnect attempt {} in {}ms - Error: {}",
                            reconnect_attempts, d, e
                        );
                        d
                    }
                };

                tokio::select! {
                    _ = sleep(Duration::from_millis(delay_ms)) => {}
                    _ = shutdown_rx.recv() => {
                        info!("Bitflyer WebSocket shutdown requested");
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    async fn connect_and_run(
        config: &Config,
        board_manager: MarketBoardManager,
        pool: Option<Arc<sqlx::PgPool>>,
    ) -> Result<()> {
        let url = &config.websocket.bitflyer.url;
        info!("Connecting to Bitflyer WebSocket: {}", url);

        let (ws_stream, _) = connect_async(url)
            .await
            .context("Failed to connect to Bitflyer WebSocket")?;

        info!("Bitflyer WebSocket connection established");

        let (mut write, mut read) = ws_stream.split();
        let jsonrpc_id = AtomicU64::new(1);

        // Subscribe to channels
        let channels = vec![
            "lightning_board_snapshot_BTC_JPY",
            "lightning_board_snapshot_FX_BTC_JPY",
            "lightning_board_BTC_JPY",
            "lightning_board_FX_BTC_JPY",
            // Executions (trades) channels for machine learning
            "lightning_executions_BTC_JPY",
            "lightning_executions_FX_BTC_JPY",
        ];

        for channel in channels {
            let subscribe_request = json!({
                "jsonrpc": "2.0",
                "method": "subscribe",
                "params": {
                    "channel": channel
                },
                "id": jsonrpc_id.fetch_add(1, Ordering::SeqCst)
            });

            let message = Message::Text(serde_json::to_string(&subscribe_request)?);
            write.send(message).await?;
            info!("Subscribed to Bitflyer channel: {}", channel);
            tokio::time::sleep(Duration::from_millis(500)).await;
        }

        // Message handling loop
        while let Some(message) = read.next().await {
            match message {
                Ok(Message::Text(text)) => {
                    tracing::debug!("Received Bitflyer message: {}", text);
                    if let Err(e) = Self::handle_message(&text, &board_manager, &config, pool.as_ref()).await {
                        error!("Error handling Bitflyer message: {} - Message: {}", e, text);
                    }
                }
                Ok(Message::Close(_)) => {
                    info!("Bitflyer WebSocket connection closed");
                    break;
                }
                Err(e) => {
                    error!("Bitflyer WebSocket error: {}", e);
                    return Err(e.into());
                }
                _ => {}
            }
        }

        Ok(())
    }

    async fn handle_message(
        text: &str,
        board_manager: &MarketBoardManager,
        config: &Config,
        pool: Option<&Arc<sqlx::PgPool>>,
    ) -> Result<()> {
        // Skip JSON-RPC responses
        if text.contains("\"jsonrpc\"") && text.contains("\"result\"") {
            tracing::debug!("Skipping JSON-RPC response: {}", text);
            return Ok(());
        }
        
        // Try to parse as ChannelMessage (for executions via channelMessage method)
        if let Ok(channel_msg) = serde_json::from_str::<ChannelMessage>(text) {
            let channel = &channel_msg.params.channel;
            if channel.starts_with("lightning_executions_") {
                // Parse message as array of executions
                if let Ok(executions) = serde_json::from_value::<Vec<ExecutionData>>(channel_msg.params.message.clone()) {
                    let symbol = Self::extract_symbol_from_channel(channel)?;
                    let target_symbol = Self::map_symbol(&symbol, config)?;
                    
                    for execution in executions {
                        let side = execution.side.to_uppercase();
                        info!(
                            "Bitflyer Trade: {} - side: {}, price: {}, size: {}",
                            target_symbol, side, execution.price, execution.size
                        );
                        
                        // Save to executions table
                        if let Some(pool_ref) = pool {
                            if let Err(e) = Self::save_trade_to_db(
                                pool_ref.as_ref(),
                                &target_symbol,
                                execution.price,
                                execution.size,
                                &side,
                            ).await {
                                error!("Failed to save Bitflyer trade to database: {:#}", e);
                            }
                        } else {
                            warn!("PostgreSQL pool not available, skipping trade save");
                        }
                    }
                    return Ok(());
                }
            }
        }
        
        // Try to parse as ExecutionsMessage (direct method format)
        if let Ok(executions_msg) = serde_json::from_str::<ExecutionsMessage>(text) {
            let channel = &executions_msg.params.channel;
            if channel.starts_with("lightning_executions_") {
                let symbol = Self::extract_symbol_from_channel(channel)?;
                let target_symbol = Self::map_symbol(&symbol, config)?;
                
                for execution in executions_msg.params.message {
                    let side = execution.side.to_uppercase();
                    info!(
                        "Bitflyer Trade: {} - side: {}, price: {}, size: {}",
                        target_symbol, side, execution.price, execution.size
                    );
                    
                    // Save to executions table
                    if let Some(pool_ref) = pool {
                        if let Err(e) = Self::save_trade_to_db(
                            pool_ref.as_ref(),
                            &target_symbol,
                            execution.price,
                            execution.size,
                            &side,
                        ).await {
                            error!("Failed to save Bitflyer trade to database: {:#}", e);
                        }
                    } else {
                        warn!("PostgreSQL pool not available, skipping trade save");
                    }
                }
                return Ok(());
            }
        }
        
        // Try to parse as BoardMessage
        if let Ok(board_msg) = serde_json::from_str::<BoardMessage>(text) {
            let channel = &board_msg.params.channel;
            let symbol = Self::extract_symbol_from_channel(channel)?;
            let target_symbol = Self::map_symbol(&symbol, config)?;

            let bids: Vec<(f64, f64)> = board_msg
                .params
                .message
                .bids
                .iter()
                .filter(|b| b.size > 0.0)  // sizeが0のものは除外
                .map(|b| (b.price, b.size))
                .collect();

            let asks: Vec<(f64, f64)> = board_msg
                .params
                .message
                .asks
                .iter()
                .filter(|a| a.size > 0.0)  // sizeが0のものは除外
                .map(|a| (a.price, a.size))
                .collect();

            if channel.contains("snapshot") {
                board_manager
                    .update_board_snapshot(target_symbol.clone(), bids.clone(), asks.clone())
                    .await;
                info!(
                    "Bitflyer Board Snapshot: {} - {} bids, {} asks (channel: {})",
                    target_symbol,
                    bids.len(),
                    asks.len(),
                    channel
                );
            } else {
                board_manager
                    .update_board_delta(target_symbol.clone(), bids.clone(), asks.clone())
                    .await;
                tracing::debug!(
                    "Bitflyer Board Delta: {} - {} bids, {} asks (channel: {})",
                    target_symbol,
                    bids.len(),
                    asks.len(),
                    channel
                );
            }
        } else {
            tracing::debug!("Failed to parse as BoardMessage: {}", text);
        }

        Ok(())
    }

    fn extract_symbol_from_channel(channel: &str) -> Result<String> {
        // FX_BTC_JPYを先にチェック（BTC_JPYを含むため）
        if channel.contains("FX_BTC_JPY") {
            Ok("FX_BTC_JPY".to_string())
        } else if channel.contains("BTC_JPY") {
            Ok("BTC_JPY".to_string())
        } else {
            Err(anyhow::anyhow!("Unknown channel: {}", channel))
        }
    }

    fn map_symbol(symbol: &str, config: &Config) -> Result<String> {
        match symbol {
            "BTC_JPY" => Ok(config.symbol_mapping.bitflyer.btc_jpy.clone()),
            "FX_BTC_JPY" => Ok(config.symbol_mapping.bitflyer.fx_btc_jpy.clone()),
            _ => Err(anyhow::anyhow!("Unknown symbol: {}", symbol)),
        }
    }

    async fn save_trade_to_db(
        pool: &PgPool,
        symbol: &str,
        price: f64,
        size: f64,
        side: &str,
    ) -> Result<()> {
        // Generate UUIDs for exec_id and order_id (dummy for external trades)
        let exec_id = Uuid::new_v4().to_string();
        let order_id = Uuid::new_v4().to_string();
        
        // Convert to internal values using multipliers
        // For B_FX_BTCJPY: price_multiplier=1, qty_multiplier=1000
        let price_multiplier = 1i64;
        let qty_multiplier = 1000i64;
        let last_px = (price * price_multiplier as f64) as i64;
        let last_qty = (size * qty_multiplier as f64) as i64;
        
        // Ensure minimum qty of 1
        let last_qty = last_qty.max(1);
        
        let username = "EXTERNAL_FEED";
        let counter_party_username = "EXTERNAL_FEED";
        let exec_status = "FILLED";
        let is_market_maker = false;
        let created_at = Utc::now();

        let result = sqlx::query(
            r#"
            INSERT INTO executions (
                exec_id, order_id, username, symbol, exec_status,
                last_px, last_qty, counter_party_username, created_at,
                is_market_maker, side
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            "#,
        )
        .bind(&exec_id)
        .bind(&order_id)
        .bind(username)
        .bind(symbol)
        .bind(exec_status)
        .bind(last_px)
        .bind(last_qty)
        .bind(counter_party_username)
        .bind(created_at)
        .bind(is_market_maker)
        .bind(side)
        .execute(pool)
        .await;

        match result {
            Ok(_) => {
                info!(
                    "✅ Saved Bitflyer trade to executions table: symbol={}, side={}, price={}, size={}, exec_id={}",
                    symbol, side, price, size, exec_id
                );
            }
            Err(sqlx::Error::Database(db_err)) => {
                // PostgreSQLのunique_violationエラー（重複挿入）を無視
                // エラーコード 23505 = unique_violation
                if db_err.code().as_deref() == Some("23505") {
                    tracing::debug!("Execution already exists (duplicate exec_id): {}", exec_id);
                    return Ok(());
                } else {
                    return Err(anyhow::anyhow!("Database error: Code: {:?}, Message: {}", 
                        db_err.code(),
                        db_err.message()
                    ))
                    .context(format!(
                        "Failed to insert execution - exec_id={}, username={}, symbol={}, exec_status={}, last_px={}, last_qty={}",
                        exec_id, username, symbol, exec_status, last_px, last_qty
                    ));
                }
            }
            Err(e) => {
                return Err(anyhow::anyhow!("SQLx error: {}", e))
                    .context(format!(
                        "Failed to insert execution - exec_id={}, username={}, symbol={}, exec_status={}, last_px={}, last_qty={}",
                        exec_id, username, symbol, exec_status, last_px, last_qty
                    ));
            }
        }

        Ok(())
    }

    pub async fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(()).await;
        }
    }
}

use futures_util::{SinkExt, StreamExt};

