use chrono::{DateTime, Utc};
use std::collections::VecDeque;

#[derive(Debug, Clone)]
pub struct OpenPosition {
    pub exec_id: String,
    pub remaining_quantity: f64,
    pub price: f64,
    pub timestamp: DateTime<Utc>,
    pub side: String, // "BUY" or "SELL"
}

#[derive(Debug, Clone)]
pub struct FifoMatch {
    pub open_exec_id: String,
    pub matched_quantity: f64,
    pub profit_loss: f64,
}

pub struct FifoPositionQueue {
    open_positions: VecDeque<OpenPosition>,
}

impl FifoPositionQueue {
    pub fn new() -> Self {
        Self {
            open_positions: VecDeque::new(),
        }
    }

    pub fn add_open(
        &mut self,
        exec_id: String,
        quantity: f64,
        price: f64,
        timestamp: DateTime<Utc>,
        side: String,
    ) {
        self.open_positions.push_back(OpenPosition {
            exec_id,
            remaining_quantity: quantity,
            price,
            timestamp,
            side,
        });
    }

    pub fn match_close(
        &mut self,
        close_quantity: f64,
        close_price: f64,
        close_side: &str,
    ) -> Vec<FifoMatch> {
        let mut matches = Vec::new();
        let mut remaining = close_quantity;

        while remaining > 1e-10 && !self.open_positions.is_empty() {
            let oldest = self.open_positions.front_mut().unwrap();

            // Verify we're closing the correct side
            // SELL closes long positions (BUY), BUY closes short positions (SELL)
            let is_valid_close = (close_side == "SELL" && oldest.side == "BUY")
                || (close_side == "BUY" && oldest.side == "SELL");

            if !is_valid_close {
                tracing::warn!(
                    "Invalid close: trying to close {} position with {} order",
                    oldest.side,
                    close_side
                );
                break;
            }

            let match_qty = remaining.min(oldest.remaining_quantity);

            // Calculate P/L
            let pnl = if close_side == "SELL" {
                // Closing long position: (sell price - buy price) × quantity
                match_qty * (close_price - oldest.price)
            } else {
                // Closing short position: (open price - close price) × quantity
                match_qty * (oldest.price - close_price)
            };

            matches.push(FifoMatch {
                open_exec_id: oldest.exec_id.clone(),
                matched_quantity: match_qty,
                profit_loss: pnl,
            });

            oldest.remaining_quantity -= match_qty;
            if oldest.remaining_quantity <= 1e-10 {
                self.open_positions.pop_front();
            }

            remaining -= match_qty;
        }

        if remaining > 1e-10 {
            tracing::warn!(
                "Close quantity {} exceeds open positions, remaining unmatched: {}",
                close_quantity,
                remaining
            );
        }

        matches
    }

    pub fn get_remaining_quantity(&self) -> f64 {
        self.open_positions
            .iter()
            .map(|p| p.remaining_quantity)
            .sum()
    }

    pub fn is_empty(&self) -> bool {
        self.open_positions.is_empty()
    }
}

