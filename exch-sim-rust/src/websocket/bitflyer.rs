use crate::config::Config;
use crate::market_board::manager::MarketBoardManager;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use tracing::{error, info, warn};
use futures_util::{SinkExt, StreamExt};

#[derive(Debug, Deserialize)]
struct JsonRpcResponse {
    jsonrpc: String,
    result: Option<serde_json::Value>,
    id: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct BoardMessage {
    #[serde(default)]
    method: Option<String>,
    params: BoardParams,
}

#[derive(Debug, Deserialize)]
struct BoardParams {
    channel: String,
    message: BoardData,
}

#[derive(Debug, Deserialize)]
struct BoardData {
    bids: Vec<PriceSize>,
    asks: Vec<PriceSize>,
}

#[derive(Debug, Deserialize)]
struct PriceSize {
    price: f64,
    size: f64,
}

pub struct BitflyerWebSocketClient {
    config: Config,
    board_manager: MarketBoardManager,
    jsonrpc_id: Arc<AtomicU64>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl BitflyerWebSocketClient {
    pub fn new(config: Config, board_manager: MarketBoardManager) -> Self {
        Self {
            config,
            board_manager,
            jsonrpc_id: Arc::new(AtomicU64::new(1)),
            shutdown_tx: None,
        }
    }

    pub async fn start(&mut self) -> Result<()> {
        if !self.config.websocket.bitflyer.enabled {
            info!("Bitflyer WebSocket client is disabled.");
            return Ok(());
        }

        let (shutdown_tx, mut shutdown_rx) = mpsc::channel(1);
        self.shutdown_tx = Some(shutdown_tx);

        let config = self.config.clone();
        let board_manager = self.board_manager.clone();
        let jsonrpc_id = self.jsonrpc_id.clone();

        tokio::spawn(async move {
            let mut reconnect_attempts = 0;
            let max_reconnect_attempts = config.websocket.bitflyer.max_reconnect_attempts;
            let reconnect_delay = Duration::from_millis(config.websocket.bitflyer.reconnect_delay_ms);

            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => {
                        info!("Bitflyer WebSocket client received shutdown signal.");
                        break;
                    }
                    result = Self::connect_and_listen(&config, &board_manager, jsonrpc_id.clone()) => {
                        match result {
                            Ok(_) => {
                                info!("Bitflyer WebSocket connection closed gracefully.");
                                reconnect_attempts = 0;
                            }
                            Err(e) => {
                                error!("Bitflyer WebSocket connection error: {}", e);
                                reconnect_attempts += 1;
                                if reconnect_attempts > max_reconnect_attempts {
                                    error!("Max Bitflyer WebSocket reconnection attempts ({}) exceeded. Shutting down.", max_reconnect_attempts);
                                    break;
                                }
                                warn!("Attempting to reconnect to Bitflyer WebSocket in {:?} (attempt {}/{})", reconnect_delay, reconnect_attempts, max_reconnect_attempts);
                                sleep(reconnect_delay).await;
                            }
                        }
                    }
                }
            }
            info!("Bitflyer WebSocket client stopped.");
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
        jsonrpc_id: Arc<AtomicU64>,
    ) -> Result<()> {
        let url = &config.websocket.bitflyer.url;
        let (ws_stream, _) = connect_async(url)
            .await
            .with_context(|| format!("Failed to connect to Bitflyer WebSocket: {}", url))?;

        info!("Bitflyer WebSocket connection established");

        let (mut write, mut read) = ws_stream.split();

        // Subscribe to channels
        let channels = vec![
            "lightning_board_snapshot_BTC_JPY",
            "lightning_board_snapshot_FX_BTC_JPY",
            "lightning_board_BTC_JPY",
            "lightning_board_FX_BTC_JPY",
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
                    if let Err(e) = Self::handle_message(&text, board_manager, config).await {
                        error!("Error handling Bitflyer message: {}", e);
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
    ) -> Result<()> {
        // Skip JSON-RPC responses
        if text.contains("\"jsonrpc\"") && text.contains("\"result\"") {
            tracing::debug!("Skipping JSON-RPC response: {}", text);
            return Ok(());
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
                .filter(|b| b.size > 0.0)
                .map(|b| (b.price, b.size))
                .collect();

            let asks: Vec<(f64, f64)> = board_msg
                .params
                .message
                .asks
                .iter()
                .filter(|a| a.size > 0.0)
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
            "BTC_JPY" => Ok("B_BTCJPY".to_string()),
            "FX_BTC_JPY" => Ok("B_FX_BTCJPY".to_string()),
            _ => Err(anyhow::anyhow!("Unknown Bitflyer symbol for mapping: {}", symbol)),
        }
    }
}

