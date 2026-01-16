use crate::config::Config;
use crate::market_board::MarketBoardManager;
use anyhow::{Context, Result};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::PgPool;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{error, info, warn};
use futures_util::{SinkExt, StreamExt};
use uuid::Uuid;

#[derive(Debug, Serialize)]
struct SubscribeRequest {
    command: String,
    channel: String,
    symbol: String,
}

#[derive(Debug, Deserialize)]
struct OrderbookMessage {
    channel: String,
    symbol: String,
    bids: Vec<GmoPriceLevel>,
    asks: Vec<GmoPriceLevel>,
}

#[derive(Debug, Deserialize)]
struct TradeMessage {
    channel: String,
    symbol: String,
    price: String,
    side: String,
    size: String,
    #[serde(default)]
    timestamp: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GmoPriceLevel {
    price: String,
    size: String,
}

pub struct GmoWebSocketClient {
    config: Config,
    board_manager: MarketBoardManager,
    pool: Arc<PgPool>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl GmoWebSocketClient {
    pub fn new(config: Config, board_manager: MarketBoardManager, pool: Arc<PgPool>) -> Self {
        Self {
            config,
            board_manager,
            pool,
            shutdown_tx: None,
        }
    }

    pub async fn start(&mut self) -> Result<()> {
        if !self.config.websocket.gmo.enabled {
            info!("GMO WebSocket is disabled");
            return Ok(());
        }

        let (shutdown_tx, mut shutdown_rx) = mpsc::channel(1);
        self.shutdown_tx = Some(shutdown_tx);

        let config = self.config.clone();
        let board_manager = self.board_manager.clone();
        let pool = self.pool.clone();
        let mut reconnect_attempts = 0u32;

        tokio::spawn(async move {
            loop {
                match Self::connect_and_run(&config, board_manager.clone(), pool.clone()).await {
                    Ok(_) => {
                        info!("GMO WebSocket connection closed normally");
                        break;
                    }
                    Err(e) => {
                        reconnect_attempts += 1;
                        if reconnect_attempts > config.websocket.gmo.max_reconnect_attempts {
                            error!(
                                "GMO WebSocket max reconnect attempts ({}) exceeded. Last error: {}",
                                config.websocket.gmo.max_reconnect_attempts,
                                e
                            );
                            break;
                        }

                        warn!(
                            "GMO WebSocket reconnection attempt {}/{} in {}ms - Error: {}",
                            reconnect_attempts,
                            config.websocket.gmo.max_reconnect_attempts,
                            config.websocket.gmo.reconnect_delay_ms,
                            e
                        );

                        tokio::select! {
                            _ = sleep(Duration::from_millis(config.websocket.gmo.reconnect_delay_ms)) => {}
                            _ = shutdown_rx.recv() => {
                                info!("GMO WebSocket shutdown requested");
                                break;
                            }
                        }
                    }
                }
            }
        });

        Ok(())
    }

    async fn connect_and_run(config: &Config, board_manager: MarketBoardManager, pool: Arc<PgPool>) -> Result<()> {
        let url = &config.websocket.gmo.url;
        info!("Connecting to GMO WebSocket: {}", url);

        let (ws_stream, _) = connect_async(url)
            .await
            .context("Failed to connect to GMO WebSocket")?;

        info!("GMO WebSocket connection established");

        let (mut write, mut read) = ws_stream.split();

        // Subscribe to orderbooks and trades
        let subscriptions = vec![
            ("BTC", "trades"),
            ("BTC_JPY", "orderbooks"),
            ("BTC", "orderbooks"),
            ("BTC_JPY", "trades"),
        ];

        for (symbol, channel) in subscriptions {
            let subscribe_request = json!({
                "command": "subscribe",
                "channel": channel,
                "symbol": symbol
            });

            let message = Message::Text(serde_json::to_string(&subscribe_request)?);
            write.send(message).await?;
            info!("Subscribed to GMO {}: {}", channel, symbol);
            tokio::time::sleep(Duration::from_millis(1000)).await;
        }

        // Message handling loop
        while let Some(message) = read.next().await {
            match message {
                Ok(Message::Text(text)) => {
                    tracing::debug!("Received GMO message: {}", text);
                    if let Err(e) = Self::handle_message(&text, &board_manager, config, pool.clone()).await {
                        error!("Error handling GMO message: {} - Message: {}", e, text);
                    }
                }
                Ok(Message::Close(_)) => {
                    info!("GMO WebSocket connection closed");
                    break;
                }
                Err(e) => {
                    error!("GMO WebSocket error: {}", e);
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
        pool: Arc<PgPool>,
    ) -> Result<()> {
        // Skip command messages
        if text.contains("\"command\"") {
            return Ok(());
        }

        // Try to parse as OrderbookMessage
        if let Ok(orderbook_msg) = serde_json::from_str::<OrderbookMessage>(text) {
            if orderbook_msg.channel == "orderbooks" {
                tracing::debug!("Processing GMO orderbook for symbol: {}", orderbook_msg.symbol);
                let symbol = &orderbook_msg.symbol;
                let target_symbol = Self::map_symbol(symbol, config)?;

                let bids: Result<Vec<(f64, f64)>, std::num::ParseFloatError> = orderbook_msg
                    .bids
                    .iter()
                    .filter(|b| {
                        // sizeが0より大きいもののみ処理
                        b.size.parse::<f64>().unwrap_or(0.0) > 0.0
                    })
                    .map(|b| {
                        Ok((
                            b.price.parse::<f64>()?,
                            b.size.parse::<f64>()?,
                        ))
                    })
                    .collect();

                let asks: Result<Vec<(f64, f64)>, std::num::ParseFloatError> = orderbook_msg
                    .asks
                    .iter()
                    .filter(|a| {
                        // sizeが0より大きいもののみ処理
                        a.size.parse::<f64>().unwrap_or(0.0) > 0.0
                    })
                    .map(|a| {
                        Ok((
                            a.price.parse::<f64>()?,
                            a.size.parse::<f64>()?,
                        ))
                    })
                    .collect();

                let bids_vec = bids?;
                let asks_vec = asks?;

                board_manager
                    .update_board_snapshot(target_symbol.clone(), bids_vec.clone(), asks_vec.clone())
                    .await;

                info!(
                    "GMO Orderbook: {} - {} bids, {} asks",
                    target_symbol,
                    bids_vec.len(),
                    asks_vec.len()
                );
            }
        } else if let Ok(trade_msg) = serde_json::from_str::<TradeMessage>(text) {
            // Try to parse as TradeMessage
            if trade_msg.channel == "trades" {
                let symbol = &trade_msg.symbol;
                let target_symbol = Self::map_symbol(symbol, config)?;

                if let (Ok(price), Ok(size)) = (
                    trade_msg.price.parse::<f64>(),
                    trade_msg.size.parse::<f64>(),
                ) {
                    let side = trade_msg.side.to_uppercase();
                    info!(
                        "GMO Trade: {} - side: {}, price: {}, size: {}",
                        target_symbol, side, price, size
                    );
                    
                    // Save to executions table
                    if let Err(e) = Self::save_trade_to_db(
                        pool.as_ref(),
                        &target_symbol,
                        price,
                        size,
                        &side,
                    ).await {
                        error!("Failed to save GMO trade to database: {}", e);
                    }
                } else {
                    warn!(
                        "⚠️ GMO trade message missing required fields or invalid values: {}",
                        text
                    );
                }
            }
        } else {
            tracing::debug!("Failed to parse as OrderbookMessage or TradeMessage: {}", text);
        }

        Ok(())
    }

    fn map_symbol(symbol: &str, config: &Config) -> Result<String> {
        match symbol {
            "BTC_JPY" => Ok(config.symbol_mapping.gmo.btc_jpy.clone()),
            "BTC" => Ok(config.symbol_mapping.gmo.btc.clone()),
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
        // Default multipliers: price_multiplier=1, qty_multiplier=1000 (Java版のデフォルト値)
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

        sqlx::query(
            r#"
            INSERT INTO executions (
                exec_id, order_id, username, symbol, exec_status,
                last_px, last_qty, counter_party_username, created_at,
                is_market_maker, side
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            ON CONFLICT (exec_id) DO NOTHING
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
        .await
        .context("Failed to insert execution")?;

        info!(
            "✅ Saved GMO trade to executions table: symbol={}, side={}, price={}, size={}, exec_id={}",
            symbol, side, price, size, exec_id
        );

        Ok(())
    }

    pub async fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(()).await;
        }
    }
}

