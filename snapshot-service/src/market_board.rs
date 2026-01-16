use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use crate::models::PriceLevel;

#[derive(Debug, Clone)]
pub struct MarketBoard {
    symbol: String,
    bids: Vec<(f64, f64)>, // (price, quantity) sorted by price descending
    asks: Vec<(f64, f64)>, // (price, quantity) sorted by price ascending
}

impl MarketBoard {
    pub fn new(symbol: String) -> Self {
        Self {
            symbol,
            bids: Vec::new(),
            asks: Vec::new(),
        }
    }

    pub fn update_from_snapshot(&mut self, bids: Vec<(f64, f64)>, asks: Vec<(f64, f64)>) {
        // Sort bids descending (highest price first)
        let mut sorted_bids = bids;
        sorted_bids.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        
        // Sort asks ascending (lowest price first)
        let mut sorted_asks = asks;
        sorted_asks.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
        
        self.bids = sorted_bids;
        self.asks = sorted_asks;
    }

    pub fn apply_delta(&mut self, bids: Vec<(f64, f64)>, asks: Vec<(f64, f64)>) {
        // Apply delta updates to existing board
        for (price, quantity) in bids {
            if quantity == 0.0 {
                // Remove price level
                self.bids.retain(|(p, _)| *p != price);
            } else {
                // Update or add price level
                if let Some(pos) = self.bids.iter().position(|(p, _)| *p == price) {
                    self.bids[pos] = (price, quantity);
                } else {
                    self.bids.push((price, quantity));
                }
            }
        }
        
        for (price, quantity) in asks {
            if quantity == 0.0 {
                // Remove price level
                self.asks.retain(|(p, _)| *p != price);
            } else {
                // Update or add price level
                if let Some(pos) = self.asks.iter().position(|(p, _)| *p == price) {
                    self.asks[pos] = (price, quantity);
                } else {
                    self.asks.push((price, quantity));
                }
            }
        }
        
        // Re-sort after delta updates
        self.bids.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        self.asks.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    }

    pub fn get_snapshot(&self, max_levels: usize) -> (Vec<PriceLevel>, Vec<PriceLevel>) {
        let bids: Vec<PriceLevel> = self.bids
            .iter()
            .take(max_levels)
            .enumerate()
            .map(|(i, (price, quantity))| PriceLevel {
                price: *price,
                quantity: *quantity,
                side: "BID".to_string(),
                level_index: i as i32,
            })
            .collect();
        
        let asks: Vec<PriceLevel> = self.asks
            .iter()
            .take(max_levels)
            .enumerate()
            .map(|(i, (price, quantity))| PriceLevel {
                price: *price,
                quantity: *quantity,
                side: "ASK".to_string(),
                level_index: i as i32,
            })
            .collect();
        
        (bids, asks)
    }

    pub fn is_empty(&self) -> bool {
        self.bids.is_empty() && self.asks.is_empty()
    }
}

#[derive(Clone)]
pub struct MarketBoardManager {
    boards: Arc<RwLock<HashMap<String, MarketBoard>>>,
}

impl MarketBoardManager {
    pub fn new() -> Self {
        Self {
            boards: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn get_or_create_board(&self, symbol: String) -> MarketBoard {
        let mut boards = self.boards.write().await;
        boards.entry(symbol.clone())
            .or_insert_with(|| MarketBoard::new(symbol))
            .clone()
    }

    pub async fn update_board_snapshot(&self, symbol: String, bids: Vec<(f64, f64)>, asks: Vec<(f64, f64)>) {
        let mut boards = self.boards.write().await;
        let symbol_clone = symbol.clone();
        let board = boards.entry(symbol).or_insert_with(|| MarketBoard::new(symbol_clone));
        board.update_from_snapshot(bids, asks);
    }

    pub async fn update_board_delta(&self, symbol: String, bids: Vec<(f64, f64)>, asks: Vec<(f64, f64)>) {
        let mut boards = self.boards.write().await;
        if let Some(board) = boards.get_mut(&symbol) {
            board.apply_delta(bids, asks);
        }
    }

    pub async fn get_snapshot(&self, symbol: &str, max_levels: usize) -> Option<(Vec<PriceLevel>, Vec<PriceLevel>)> {
        let boards = self.boards.read().await;
        boards.get(symbol).map(|board| board.get_snapshot(max_levels))
    }

    pub async fn get_available_symbols(&self) -> Vec<String> {
        let boards = self.boards.read().await;
        boards.keys().cloned().collect()
    }
}

