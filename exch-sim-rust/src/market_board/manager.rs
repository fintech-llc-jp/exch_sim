use crate::config::Config;
use crate::market_board::board::MarketBoard;
use crate::order::service::OrderService;
use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Clone)]
pub struct MarketBoardManager {
    boards: Arc<DashMap<String, Arc<RwLock<MarketBoard>>>>,
    config: Arc<Config>,
    order_service: Arc<tokio::sync::RwLock<Option<Arc<OrderService>>>>,
}

impl MarketBoardManager {
    pub fn new(config: Config) -> Self {
        Self {
            boards: Arc::new(DashMap::new()),
            config: Arc::new(config),
            order_service: Arc::new(tokio::sync::RwLock::new(None)),
        }
    }

    pub async fn set_order_service(&self, order_service: Arc<OrderService>) {
        let mut service = self.order_service.write().await;
        *service = Some(order_service);
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
        
        // Clear existing market maker orders first
        {
            let mut board_guard = board.write().await;
            tracing::debug!(
                "Before clear_market_maker_orders: symbol={}, ask_order_board levels={}, ask_entry_board levels={}",
                symbol,
                board_guard.get_ask_order_board_levels(),
                board_guard.get_ask_entry_board_levels()
            );
            board_guard.clear_market_maker_orders();
            tracing::debug!(
                "After clear_market_maker_orders: symbol={}, ask_order_board levels={}, ask_entry_board levels={}",
                symbol,
                board_guard.get_ask_order_board_levels(),
                board_guard.get_ask_entry_board_levels()
            );
        }
        
        // Process each bid/ask level and create market maker orders
        // This will trigger matching with existing user orders
        let order_service_opt = self.order_service.read().await.clone();
        if let Some(order_service) = order_service_opt {
            tracing::debug!(
                "Processing market maker orders for snapshot: symbol={}, {} bids, {} asks",
                symbol,
                bids.len(),
                asks.len()
            );
            
            // Process bids (market maker buy orders)
            let mut bid_executions = 0;
            for (price, qty) in bids.iter().take(10) {
                if *qty > 0.0 {
                    match order_service
                        .process_market_maker_order(
                            &symbol,
                            crate::models::Side::Buy,
                            *price,
                            *qty,
                        )
                        .await
                    {
                        Ok(execs) => {
                            bid_executions += execs.len();
                            if !execs.is_empty() {
                                tracing::info!(
                                    "Market maker bid order matched: symbol={}, price={}, qty={}, executions={}",
                                    symbol,
                                    price,
                                    qty,
                                    execs.len()
                                );
                            }
                        }
                        Err(e) => {
                            tracing::error!(
                                "Failed to process market maker bid order: price={}, qty={}, error={}",
                                price,
                                qty,
                                e
                            );
                        }
                    }
                }
            }
            
            // Process asks (market maker sell orders)
            let mut ask_executions = 0;
            for (price, qty) in asks.iter().take(10) {
                if *qty > 0.0 {
                    match order_service
                        .process_market_maker_order(
                            &symbol,
                            crate::models::Side::Sell,
                            *price,
                            *qty,
                        )
                        .await
                    {
                        Ok(execs) => {
                            ask_executions += execs.len();
                            if !execs.is_empty() {
                                tracing::info!(
                                    "Market maker ask order matched: symbol={}, price={}, qty={}, executions={}",
                                    symbol,
                                    price,
                                    qty,
                                    execs.len()
                                );
                            }
                        }
                        Err(e) => {
                            tracing::error!(
                                "Failed to process market maker ask order: price={}, qty={}, error={}",
                                price,
                                qty,
                                e
                            );
                        }
                    }
                }
            }
            
            tracing::debug!(
                "Market maker snapshot processing completed: symbol={}, bid_executions={}, ask_executions={}",
                symbol,
                bid_executions,
                ask_executions
            );
        } else {
            // Fallback: if order_service is not set, use the old method
            let mut board_guard = board.write().await;
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
        
        // Process delta updates: remove orders with qty=0, update/add orders with qty>0
        let order_service_opt = self.order_service.read().await.clone();
        if let Some(order_service) = order_service_opt {
            // First, clear existing market maker orders at price levels with qty=0
            // Use update_external_market_data_delta to handle qty=0 removals
            
            // Use update_external_market_data_delta to handle qty=0 removals
            {
                let mut board_guard = board.write().await;
                board_guard.update_external_market_data_delta(bids.clone(), asks.clone(), price_multiplier, qty_multiplier);
            }
            
            // Then process orders with qty>0 (this will trigger matching)
            tracing::debug!(
                "Processing market maker orders for delta: symbol={}, {} bids, {} asks",
                symbol,
                bids.len(),
                asks.len()
            );
            
            let mut bid_executions = 0;
            for (price, qty) in bids.iter() {
                if *qty > 0.0 {
                    match order_service
                        .process_market_maker_order(
                            &symbol,
                            crate::models::Side::Buy,
                            *price,
                            *qty,
                        )
                        .await
                    {
                        Ok(execs) => {
                            bid_executions += execs.len();
                            if !execs.is_empty() {
                                tracing::info!(
                                    "Market maker bid delta matched: symbol={}, price={}, qty={}, executions={}",
                                    symbol,
                                    price,
                                    qty,
                                    execs.len()
                                );
                            }
                        }
                        Err(e) => {
                            tracing::error!(
                                "Failed to process market maker bid delta: price={}, qty={}, error={}",
                                price,
                                qty,
                                e
                            );
                        }
                    }
                }
            }
            
            let mut ask_executions = 0;
            for (price, qty) in asks.iter() {
                if *qty > 0.0 {
                    match order_service
                        .process_market_maker_order(
                            &symbol,
                            crate::models::Side::Sell,
                            *price,
                            *qty,
                        )
                        .await
                    {
                        Ok(execs) => {
                            ask_executions += execs.len();
                            if !execs.is_empty() {
                                tracing::info!(
                                    "Market maker ask delta matched: symbol={}, price={}, qty={}, executions={}",
                                    symbol,
                                    price,
                                    qty,
                                    execs.len()
                                );
                            }
                        }
                        Err(e) => {
                            tracing::error!(
                                "Failed to process market maker ask delta: price={}, qty={}, error={}",
                                price,
                                qty,
                                e
                            );
                        }
                    }
                }
            }
            
            tracing::debug!(
                "Market maker delta processing completed: symbol={}, bid_executions={}, ask_executions={}",
                symbol,
                bid_executions,
                ask_executions
            );
        } else {
            // Fallback: if order_service is not set, use the old method
            let mut board_guard = board.write().await;
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

