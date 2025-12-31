use crate::config::Config;
use crate::market_board::manager::MarketBoardManager;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{error, info, warn};
use futures_util::{SinkExt, StreamExt};

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
    bids: Vec<PriceSize>,
    asks: Vec<PriceSize>,
}

#[derive(Debug, Deserialize)]
struct PriceSize {
    price: String,
    size: String,
}

pub struct GmoWebSocketClient {
    config: Config,
    board_manager: MarketBoardManager,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl GmoWebSocketClient {
    pub fn new(config: Config, board_manager: MarketBoardManager) -> Self {
        Self {
            config,
            board_manager,
            shutdown_tx: None,
        }
    }

    pub async fn start(&mut self) -> Result<()> {
        if !self.config.websocket.gmo.enabled {
            info!("GMO WebSocket client is disabled.");
            return Ok(());
        }

        let (shutdown_tx, mut shutdown_rx) = mpsc::channel(1);
        self.shutdown_tx = Some(shutdown_tx);

        let config = self.config.clone();
        let board_manager = self.board_manager.clone();

        tokio::spawn(async move {
            let mut reconnect_attempts = 0;
            let max_reconnect_attempts = config.websocket.gmo.max_reconnect_attempts;
            let reconnect_delay = Duration::from_millis(config.websocket.gmo.reconnect_delay_ms);

            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => {
                        info!("GMO WebSocket client received shutdown signal.");
                        break;
                    }
                    result = Self::connect_and_listen(&config, &board_manager) => {
                        match result {
                            Ok(_) => {
                                info!("GMO WebSocket connection closed gracefully.");
                                reconnect_attempts = 0;
                            }
                            Err(e) => {
                                error!("GMO WebSocket connection error: {}", e);
                                reconnect_attempts += 1;
                                if reconnect_attempts > max_reconnect_attempts {
                                    error!("Max GMO WebSocket reconnection attempts ({}) exceeded. Shutting down.", max_reconnect_attempts);
                                    break;
                                }
                                warn!("Attempting to reconnect to GMO WebSocket in {:?} (attempt {}/{})", reconnect_delay, reconnect_attempts, max_reconnect_attempts);
                                sleep(reconnect_delay).await;
                            }
                        }
                    }
                }
            }
            info!("GMO WebSocket client stopped.");
        });
        Ok(())
    }

    pub async fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(()).await;
        }
    }

    async fn connect_and_listen(
        config: &Config,
        board_manager: &MarketBoardManager,
    ) -> Result<()> {
        let url = &config.websocket.gmo.url;
        let (ws_stream, _) = connect_async(url)
            .await
            .with_context(|| format!("Failed to connect to GMO WebSocket: {}", url))?;

        info!("GMO WebSocket connection established");

        let (mut write, mut read) = ws_stream.split();

        // Subscribe to channels (following Java version's sequence with 2 second delays)
        // Java version sends: BTC trades -> BTC_JPY orderbooks -> BTC orderbooks -> BTC_JPY trades
        // We'll follow the same sequence to avoid rate limiting
        let subscriptions = vec![
            ("BTC", "trades"),
            ("BTC_JPY", "orderbooks"),
            ("BTC", "orderbooks"),
            ("BTC_JPY", "trades"),
        ];

        info!("📡 GMO starting subscription sequence...");
        for (symbol, channel) in subscriptions {
            let subscribe_request = json!({
                "command": "subscribe",
                "channel": channel,
                "symbol": symbol
            });

            let message = Message::Text(serde_json::to_string(&subscribe_request)?);
            write.send(message).await?;
            info!("Subscribed to GMO {}: {}", channel, symbol);
            // Use 2 second delay like Java version to avoid rate limiting
            tokio::time::sleep(Duration::from_millis(2000)).await;
        }
        info!("✅ GMO subscription sequence completed");

        // Message handling loop
        while let Some(message) = read.next().await {
            match message {
                Ok(Message::Text(text)) => {
                    tracing::debug!("Received GMO message: {}", text);
                    if let Err(e) = Self::handle_message(&text, board_manager, config).await {
                        error!("Error handling GMO message: {}", e);
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
                        a.size.parse::<f64>().unwrap_or(0.0) > 0.0
                    })
                    .map(|a| {
                        Ok((
                            a.price.parse::<f64>()?,
                            a.size.parse::<f64>()?,
                        ))
                    })
                    .collect();

                let mut bids = bids?;
                let mut asks = asks?;

                // Sort bids in descending order (highest price first)
                bids.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
                
                // Sort asks in ascending order (lowest price first)
                asks.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));

                // Log first bid and ask for debugging
                if !bids.is_empty() && !asks.is_empty() {
                    let spread = asks[0].0 - bids[0].0;
                    tracing::debug!(
                        "GMO Orderbook {}: first bid={}, first ask={}, spread={}",
                        target_symbol,
                        bids[0].0,
                        asks[0].0,
                        spread
                    );
                    
                    // Log BTC symbol specifically for debugging
                    if orderbook_msg.symbol == "BTC" {
                        info!(
                            "GMO BTC orderbook message received: {} bids, {} asks, first bid={}, first ask={}, spread={}",
                            bids.len(),
                            asks.len(),
                            bids[0].0,
                            asks[0].0,
                            spread
                        );
                    }
                }

                board_manager
                    .update_board_snapshot(target_symbol.clone(), bids.clone(), asks.clone())
                    .await;

                info!(
                    "GMO Orderbook: {} (from {}) - {} bids, {} asks",
                    target_symbol,
                    symbol,
                    bids.len(),
                    asks.len()
                );
            }
        } else {
            tracing::debug!("Failed to parse as OrderbookMessage: {}", text);
        }

        Ok(())
    }

    fn map_symbol(symbol: &str, _config: &Config) -> Result<String> {
        match symbol {
            "BTC_JPY" => Ok("G_FX_BTCJPY".to_string()),
            "BTC" => Ok("G_BTCJPY".to_string()),
            _ => Err(anyhow::anyhow!("Unknown GMO symbol for mapping: {}", symbol)),
        }
    }
}

