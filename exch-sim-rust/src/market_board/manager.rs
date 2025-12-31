use crate::config::Config;
use crate::market_board::board::MarketBoard;
use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Clone)]
pub struct MarketBoardManager {
    boards: Arc<DashMap<String, Arc<RwLock<MarketBoard>>>>,
    config: Arc<Config>,
}

impl MarketBoardManager {
    pub fn new(config: Config) -> Self {
        Self {
            boards: Arc::new(DashMap::new()),
            config: Arc::new(config),
        }
    }

    pub async fn get_or_create_board(&self, symbol: String) -> Arc<RwLock<MarketBoard>> {
        self.boards
            .entry(symbol.clone())
            .or_insert_with(|| Arc::new(RwLock::new(MarketBoard::new(symbol))))
            .clone()
    }

    pub async fn update_board_snapshot(
        &self,
        symbol: String,
        bids: Vec<(f64, f64)>,
        asks: Vec<(f64, f64)>,
    ) {
        // This is for external market data updates (from WebSocket)
        // Update the market board with external market data
        let bid_count = bids.len();
        let ask_count = asks.len();
        
        // Get multipliers from config
        let price_multiplier = self.config
            .get_instrument(&symbol)
            .map(|i| i.price_multiplier as f64)
            .unwrap_or(1_000_000.0);
        let qty_multiplier = self.config
            .get_instrument(&symbol)
            .map(|i| i.qty_multiplier as f64)
            .unwrap_or(1_000_000.0);
        
        let board = self.get_or_create_board(symbol.clone()).await;
        
        {
            let mut board_guard = board.write().await;
            // Clear existing external market data and update with new snapshot
            // Note: This only updates external market data, not internal orders
            board_guard.update_external_market_data(bids, asks, price_multiplier, qty_multiplier);
        }
        
        tracing::debug!(
            "External market data snapshot updated for {}: {} bids, {} asks",
            symbol,
            bid_count,
            ask_count
        );
    }

    pub async fn update_board_delta(
        &self,
        symbol: String,
        bids: Vec<(f64, f64)>,
        asks: Vec<(f64, f64)>,
    ) {
        // External market data delta updates
        let bid_count = bids.len();
        let ask_count = asks.len();
        
        // Get multipliers from config
        let price_multiplier = self.config
            .get_instrument(&symbol)
            .map(|i| i.price_multiplier as f64)
            .unwrap_or(1_000_000.0);
        let qty_multiplier = self.config
            .get_instrument(&symbol)
            .map(|i| i.qty_multiplier as f64)
            .unwrap_or(1_000_000.0);
        
        let board = self.get_or_create_board(symbol.clone()).await;
        
        {
            let mut board_guard = board.write().await;
            // Apply delta updates to external market data
            board_guard.update_external_market_data_delta(bids, asks, price_multiplier, qty_multiplier);
        }
        
        tracing::debug!(
            "External market data delta updated for {}: {} bids, {} asks",
            symbol,
            bid_count,
            ask_count
        );
    }

    pub async fn get_snapshot(
        &self,
        symbol: &str,
        max_levels: usize,
    ) -> Option<(Vec<crate::models::PriceLevel>, Vec<crate::models::PriceLevel>)> {
        if let Some(board) = self.boards.get(symbol) {
            let board = board.read().await;
            Some(board.get_snapshot(max_levels))
        } else {
            None
        }
    }

    pub async fn get_available_symbols(&self) -> Vec<String> {
        self.boards.iter().map(|entry| entry.key().clone()).collect()
    }
}

