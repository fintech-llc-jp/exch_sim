use crate::models::{OrdStatus, OrdType, Side, TimeInForce};
use chrono::Utc;
use uuid::Uuid;

#[derive(Debug, Clone)]
pub struct Order {
    pub cl_ord_id: String,
    pub username: String,
    pub symbol: String,
    pub side: Side,
    pub ord_type: OrdType,
    pub price: Option<f64>, // None for market orders
    pub quantity: f64,
    pub tif: TimeInForce,
    pub leaves_qty: f64,
    pub cum_qty: f64,
    pub ord_status: OrdStatus,
    pub created_at: chrono::DateTime<Utc>,
    pub is_market_make: bool,
    // Raw values (multiplied)
    pub raw_price: Option<i64>,
    pub raw_quantity: i64,
    pub raw_leaves_qty: i64,
    pub raw_cum_qty: i64,
}

impl Order {
    pub fn new(
        username: String,
        symbol: String,
        side: Side,
        ord_type: OrdType,
        price: Option<f64>,
        quantity: f64,
        tif: TimeInForce,
        is_market_make: bool,
        price_multiplier: u64,
        qty_multiplier: u64,
    ) -> Self {
        let raw_price = price.map(|p| (p * price_multiplier as f64) as i64);
        let raw_quantity = (quantity * qty_multiplier as f64) as i64;
        let cl_ord_id = Uuid::new_v4().to_string();

        Self {
            cl_ord_id,
            username,
            symbol,
            side,
            ord_type,
            price,
            quantity,
            tif,
            leaves_qty: quantity,
            cum_qty: 0.0,
            ord_status: OrdStatus::New,
            created_at: Utc::now(),
            is_market_make,
            raw_price,
            raw_quantity,
            raw_leaves_qty: raw_quantity,
            raw_cum_qty: 0,
        }
    }

    pub fn get_raw_price(&self) -> i64 {
        self.raw_price.unwrap_or(0)
    }

    pub fn get_raw_quantity(&self) -> i64 {
        self.raw_quantity
    }

    pub fn get_raw_leaves_qty(&self) -> i64 {
        self.raw_leaves_qty
    }

    pub fn update_execution(&mut self, exec_qty: i64, price: i64) {
        self.raw_leaves_qty -= exec_qty;
        self.raw_cum_qty += exec_qty;
        self.leaves_qty = self.raw_leaves_qty as f64 / 1_000_000.0; // Assuming qty_multiplier = 1_000_000
        self.cum_qty = self.raw_cum_qty as f64 / 1_000_000.0;

        if self.raw_leaves_qty == 0 {
            self.ord_status = OrdStatus::Filled;
        } else {
            self.ord_status = OrdStatus::PartiallyFilled;
        }
    }
}

