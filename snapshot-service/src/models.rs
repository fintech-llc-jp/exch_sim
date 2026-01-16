use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PriceLevel {
    pub price: f64,
    pub quantity: f64,
    pub side: String, // "BID" or "ASK"
    pub level_index: i32,
}

#[derive(Debug, Clone)]
pub struct MarketBoardSnapshot {
    pub symbol: String,
    pub timestamp: DateTime<Utc>,
    pub bids: Vec<PriceLevel>,
    pub asks: Vec<PriceLevel>,
}

#[derive(Debug, Clone, FromRow)]
pub struct SnapshotRecord {
    pub id: i64,
    pub symbol: String,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, FromRow)]
pub struct PriceLevelRecord {
    pub id: i64,
    pub snapshot_id: i64,
    pub price: f64,
    pub quantity: f64,
    pub side: String,
    pub level_index: i32,
}

