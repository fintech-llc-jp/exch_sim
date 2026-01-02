use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize, Serializer};
use sqlx::FromRow;

// Custom serializer to sanitize f64 values (replace NaN/Infinity with 0.0, normalize -0.0 to 0.0)
fn sanitize_f64_serialize<S>(value: &f64, serializer: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    let sanitized = if value.is_nan() || value.is_infinite() {
        0.0
    } else if *value == 0.0 && value.is_sign_negative() {
        // Normalize -0.0 to 0.0
        0.0
    } else {
        *value
    };
    serializer.serialize_f64(sanitized)
}

// Order models
#[derive(Debug, Clone, Serialize, Deserialize)]
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
    pub created_at: DateTime<Utc>,
    pub is_market_make: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Side {
    Buy,
    Sell,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum OrdType {
    Limit,
    Market,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum TimeInForce {
    Gtc, // Good Till Cancel
    Ioc, // Immediate or Cancel
    Fok, // Fill or Kill
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OrdStatus {
    New,
    PartiallyFilled,
    Filled,
    Canceled,
    Rejected,
}

// Execution models
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Execution {
    pub exec_id: String,
    pub order_id: String,
    pub cl_ord_id: String,
    pub username: String,
    pub symbol: String,
    pub exec_status: ExecStatus,
    pub last_px: i64, // Raw price (multiplied)
    pub last_qty: i64, // Raw quantity (multiplied)
    pub counter_party_username: String,
    pub created_at: DateTime<Utc>,
    pub is_market_maker: bool,
    pub side: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExecStatus {
    New,
    PartiallyFilled,
    Filled,
    Canceled,
    Rejected,
}

// Position models (Database entity - stores quantities as BIGINT * 1000)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Position {
    pub id: Option<String>,      // username + "_" + symbol
    pub username: String,
    pub symbol: String,
    pub unit: String,            // "JPY", "BTC", etc.
    pub total_buy_qty: f64,      // Database value (will be divided by 1000 when converting to domain)
    pub total_buy_amount: f64,
    pub total_sell_qty: f64,     // Database value (will be divided by 1000 when converting to domain)
    pub total_sell_amount: f64,
    pub net_qty: f64,            // Database value (will be divided by 1000 when converting to domain)
    pub average_buy_price: f64,
    pub average_sell_price: f64,
    pub realized_pnl: f64,
    pub last_updated: DateTime<Utc>,
}

// TradeHistory models
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TradeHistory {
    pub exec_id: String,
    pub username: String,
    pub symbol: String,
    pub side: String,
    pub quantity: f64,
    pub price: f64,
    pub amount: f64,
    pub counter_party_username: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub cl_ord_id: Option<String>,
    pub is_market_maker: bool,
    pub open_close: Option<String>,
    pub profit_loss: Option<f64>,
    pub matched_open_exec_ids: Option<String>,
}

// User models
#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct User {
    pub username: String,
    pub password: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserWithRoles {
    pub username: String,
    pub password: String,
    pub roles: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

// API Request/Response models
#[derive(Debug, Deserialize)]
pub struct NewOrderRequest {
    pub symbol: String,
    pub side: String,
    #[serde(alias = "ordType")]
    pub ord_type: String,
    pub price: Option<f64>,
    pub quantity: f64,
    pub tif: String,
    #[serde(default)]
    pub is_market_make: bool,
}

#[derive(Debug, Deserialize)]
pub struct CancelOrderRequest {
    #[serde(rename = "clOrdID")]
    pub cl_ord_id: String,
    pub symbol: String,
}

#[derive(Debug, Serialize)]
pub struct OrderResponse {
    pub cl_ord_id: String,
    pub status: String,
    pub executions: Vec<ExecutionResponse>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderListResponse {
    pub username: String,
    pub total_orders: usize,
    pub orders: Vec<OrderDto>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderDto {
    pub cl_ord_id: String,
    pub symbol: String,
    pub side: String,
    pub ord_type: String,
    pub ord_status: String,
    pub order_px: Option<f64>,
    pub order_qty: f64,
    pub leaves_qty: f64,
    pub filled_qty: f64,
    pub tif: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Serialize)]
pub struct ExecutionResponse {
    pub exec_id: String,
    pub exec_status: String,
    pub last_px: f64,
    pub last_qty: f64,
    pub side: String,
}

#[derive(Debug, Serialize)]
pub struct MarketBoardResponse {
    pub symbol: String,
    pub bids: Vec<PriceLevel>,
    pub asks: Vec<PriceLevel>,
    #[serde(rename = "asOf")]
    pub as_of: i64, // Last order time in nanoseconds since epoch
}

#[derive(Debug, Clone, Serialize)]
pub struct PriceLevel {
    pub price: f64,
    pub quantity: f64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionResponse {
    pub username: String,
    pub symbol: String,
    pub unit: String,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_buy_qty: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_buy_amount: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_sell_qty: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_sell_amount: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub net_qty: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub average_buy_price: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub average_sell_price: f64,
    #[serde(rename = "realizedPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub realized_pnl: f64,
    #[serde(rename = "unrealizedPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub unrealized_pnl: f64,
    #[serde(rename = "totalPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_pnl: f64,
    pub last_updated: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PortfolioSummaryResponse {
    pub username: String,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub cash_balance: f64,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_value: f64,
    #[serde(rename = "totalRealizedPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_realized_pnl: f64,
    #[serde(rename = "totalUnrealizedPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_unrealized_pnl: f64,
    #[serde(rename = "totalPnL")]
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_pnl: f64,
    pub total_trade_count: i32,
    #[serde(serialize_with = "sanitize_f64_serialize")]
    pub total_trading_volume: f64,
    pub positions: Vec<PositionResponse>,
    pub symbol_trade_counts: std::collections::HashMap<String, i64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TradeHistoryResponse {
    pub username: String,
    pub total_count: i32,
    pub trades: Vec<TradeHistoryDto>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TradeHistoryDto {
    pub exec_id: String,
    pub symbol: String,
    pub side: String,
    pub quantity: f64,
    pub price: f64,
    pub amount: f64,
    pub counter_party_username: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub cl_ord_id: Option<String>,
    pub open_close: Option<String>,
    pub profit_loss: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct MarketMakeOrderRequest {
    pub symbol: String,
    pub bid_levels: Option<Vec<OrderLevel>>,
    pub ask_levels: Option<Vec<OrderLevel>>,
}

#[derive(Debug, Deserialize)]
pub struct OrderLevel {
    pub price: f64,
    pub quantity: f64,
    #[serde(default = "default_ord_type")]
    pub ord_type: String,
    #[serde(default = "default_tif")]
    pub tif: String,
}

fn default_ord_type() -> String {
    "LIMIT".to_string()
}

fn default_tif() -> String {
    "GTC".to_string()
}

#[derive(Debug, Serialize)]
pub struct MarketMakeOrderResponse {
    pub username: String,
    pub symbol: String,
    pub cancelled_orders_count: usize,
    pub new_bid_orders_count: usize,
    pub new_ask_orders_count: usize,
    pub bid_order_ids: Vec<String>,
    pub ask_order_ids: Vec<String>,
    pub message: String,
}

