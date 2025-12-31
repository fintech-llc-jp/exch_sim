use crate::config::Config;
use crate::database::DatabaseTrait;
use crate::models::{Execution, TradeHistory};
use crate::position::fifo::FifoPositionQueue;
use crate::position::position::Position;
use anyhow::Result;
use dashmap::DashMap;
use std::sync::Arc;
use tracing::{error, info};

const CASH_SYMBOL: &str = "JPY";

pub struct PositionManager {
    database: Arc<dyn DatabaseTrait>,
    positions_cache: Arc<DashMap<String, DashMap<String, Position>>>,
    fifo_queues: Arc<DashMap<String, DashMap<String, FifoPositionQueue>>>,
    config: Config,
}

impl PositionManager {
    pub fn new<D: DatabaseTrait + 'static>(database: D, config: Config) -> Self {
        Self {
            database: Arc::new(database),
            positions_cache: Arc::new(DashMap::new()),
            fifo_queues: Arc::new(DashMap::new()),
            config,
        }
    }

    pub async fn process_execution(&self, execution: &Execution) -> Result<()> {
        if execution.last_qty == 0 {
            return Ok(());
        }

        let username = &execution.username;
        let symbol = &execution.symbol;
        let side = &execution.side;

        // Convert raw values to actual values using symbol-specific multipliers
        let instrument = self.config.get_instrument(symbol)
            .ok_or_else(|| anyhow::anyhow!("Instrument not found: {}", symbol))?;
        let price_multiplier = instrument.price_multiplier as f64;
        let qty_multiplier = instrument.qty_multiplier as f64;

        let quantity = execution.last_qty as f64 / qty_multiplier;
        let price = execution.last_px as f64 / price_multiplier;

        info!(
            "Processing execution for user: {}, symbol: {}, side: {}, qty: {}, price: {}",
            username, symbol, side, quantity, price
        );

        // Get or create position
        let mut position = self.get_or_create_position(username, symbol).await?;
        let previous_net_qty = position.net_qty;

        // Update position
        if side == "BUY" {
            position.add_buy_trade(quantity, price);
            // 買い注文：現金を減らす
            let amount = quantity * price;
            self.update_cash_balance(username, -amount).await?;
        } else if side == "SELL" {
            position.add_sell_trade(quantity, price);
            // 売り注文：現金を増やす
            let amount = quantity * price;
            self.update_cash_balance(username, amount).await?;
        }

        // Save position to database
        // Note: JPY (cash) quantities are already in JPY units, so we don't multiply by 1000
        // Other symbols (BTC/JPY, etc.) need to multiply by 1000 when saving
        let position_id = format!("{}_{}", username, symbol.to_uppercase());
        let unit = if symbol == "JPY" {
            "JPY".to_string()
        } else if symbol.contains("BTC") {
            "BTC".to_string()
        } else if symbol.contains("ETH") {
            "ETH".to_string()
        } else {
            "UNIT".to_string()
        };

        // JPYは既にJPY単位なので1000倍しない、他のシンボルは1000倍する
        let (total_buy_qty, total_sell_qty, net_qty) = if symbol == "JPY" {
            (position.total_buy_qty, position.total_sell_qty, position.net_qty)
        } else {
            (position.total_buy_qty * 1000.0, position.total_sell_qty * 1000.0, position.net_qty * 1000.0)
        };

        let position_db = crate::models::Position {
            id: Some(position_id),
            username: position.username.clone(),
            symbol: position.symbol.clone(),
            unit: unit.clone(),
            total_buy_qty,
            total_buy_amount: position.total_buy_amount,
            total_sell_qty,
            total_sell_amount: position.total_sell_amount,
            net_qty,
            average_buy_price: position.average_buy_price,
            average_sell_price: position.average_sell_price,
            realized_pnl: position.realized_pnl,
            last_updated: position.last_updated,
        };

        if let Err(e) = self.database.upsert_position(&position_db).await {
            error!("Failed to save position: {}", e);
        }

        // Update cache
        self.positions_cache
            .entry(username.to_string())
            .or_insert_with(DashMap::new)
            .insert(symbol.to_string(), position.clone());

        // FIFO tracking: Determine OPEN/CLOSE and calculate P/L
        let open_close: String;
        let profit_loss: Option<f64>;
        let matched_open_exec_ids: Option<String>;

        let exec_id = &execution.exec_id;

        if side == "BUY" {
            if previous_net_qty < 0.0 {
                // Closing short position
                open_close = "CLOSE".to_string();
                let mut user_queues = self.fifo_queues
                    .entry(username.to_string())
                    .or_insert_with(DashMap::new);
                let mut fifo_queue = user_queues
                    .entry(symbol.to_string())
                    .or_insert_with(|| FifoPositionQueue::new());
                let matches = fifo_queue.match_close(quantity, price, "BUY");
                profit_loss = Some(matches.iter().map(|m| m.profit_loss).sum());
                let matched_ids: Vec<String> = matches.iter().map(|m| m.open_exec_id.clone()).collect();
                matched_open_exec_ids = Some(serde_json::to_string(&matched_ids)?);
                info!(
                    "CLOSE short position: user={}, symbol={}, qty={}, P/L={:?}",
                    username, symbol, quantity, profit_loss
                );
            } else {
                // Opening long position
                open_close = "OPEN".to_string();
                let mut user_queues = self.fifo_queues
                    .entry(username.to_string())
                    .or_insert_with(DashMap::new);
                let mut fifo_queue = user_queues
                    .entry(symbol.to_string())
                    .or_insert_with(|| FifoPositionQueue::new());
                fifo_queue.add_open(
                    exec_id.clone(),
                    quantity,
                    price,
                    execution.created_at,
                    "BUY".to_string(),
                );
                profit_loss = None;
                matched_open_exec_ids = None;
                info!("OPEN long position: user={}, symbol={}, qty={}", username, symbol, quantity);
            }
        } else {
            // SELL
            if previous_net_qty > 0.0 {
                // Closing long position
                open_close = "CLOSE".to_string();
                let mut user_queues = self.fifo_queues
                    .entry(username.to_string())
                    .or_insert_with(DashMap::new);
                let mut fifo_queue = user_queues
                    .entry(symbol.to_string())
                    .or_insert_with(|| FifoPositionQueue::new());
                let matches = fifo_queue.match_close(quantity, price, "SELL");
                profit_loss = Some(matches.iter().map(|m| m.profit_loss).sum());
                let matched_ids: Vec<String> = matches.iter().map(|m| m.open_exec_id.clone()).collect();
                matched_open_exec_ids = Some(serde_json::to_string(&matched_ids)?);
                info!(
                    "CLOSE long position: user={}, symbol={}, qty={}, P/L={:?}",
                    username, symbol, quantity, profit_loss
                );
            } else {
                // Opening short position
                open_close = "OPEN".to_string();
                let mut user_queues = self.fifo_queues
                    .entry(username.to_string())
                    .or_insert_with(DashMap::new);
                let mut fifo_queue = user_queues
                    .entry(symbol.to_string())
                    .or_insert_with(|| FifoPositionQueue::new());
                fifo_queue.add_open(
                    exec_id.clone(),
                    quantity,
                    price,
                    execution.created_at,
                    "SELL".to_string(),
                );
                profit_loss = None;
                matched_open_exec_ids = None;
                info!("OPEN short position: user={}, symbol={}, qty={}", username, symbol, quantity);
            }
        }

        // Create trade history
        let trade_history = TradeHistory {
            exec_id: exec_id.clone(),
            username: username.to_string(),
            symbol: symbol.clone(),
            side: side.clone(),
            quantity,
            price,
            amount: quantity * price,
            counter_party_username: Some(execution.counter_party_username.clone()),
            timestamp: execution.created_at,
            cl_ord_id: Some(execution.cl_ord_id.clone()),
            is_market_maker: execution.is_market_maker,
            open_close: Some(open_close),
            profit_loss,
            matched_open_exec_ids,
        };

        // Save trade history to database
        if let Err(e) = self.database.insert_trade_history(&trade_history).await {
            error!("Failed to save trade history: {}", e);
        }

        info!(
            "Position updated for user: {}, symbol: {}, netQty: {}, realizedPnL: {}",
            username, symbol, position.net_qty, position.realized_pnl
        );

        Ok(())
    }

    pub async fn get_position(&self, username: &str, symbol: &str) -> Result<Option<Position>> {
        // Check cache first
        if let Some(user_positions) = self.positions_cache.get(username) {
            if let Some(position) = user_positions.get(symbol) {
                return Ok(Some(position.clone()));
            }
        }

        // Check database
        if let Some(position_db) = self.database.query_position(username, symbol).await? {
            // Convert database entity to domain model
            // Note: JPY (cash) quantities are already in JPY units, so we don't divide by 1000
            // Other symbols (BTC/JPY, etc.) need to divide by 1000 when loading
            let (total_buy_qty, total_sell_qty, net_qty) = if symbol == "JPY" {
                // JPYは既にJPY単位なので1000で割らない
                (position_db.total_buy_qty, position_db.total_sell_qty, position_db.net_qty)
            } else {
                // 他のシンボルは1000で割る
                (position_db.total_buy_qty / 1000.0, position_db.total_sell_qty / 1000.0, position_db.net_qty / 1000.0)
            };

            let position = Position {
                username: position_db.username,
                symbol: position_db.symbol,
                total_buy_qty,
                total_buy_amount: position_db.total_buy_amount,
                total_sell_qty,
                total_sell_amount: position_db.total_sell_amount,
                net_qty,
                average_buy_price: position_db.average_buy_price,
                average_sell_price: position_db.average_sell_price,
                realized_pnl: position_db.realized_pnl,
                last_updated: position_db.last_updated,
            };

            // Update cache
            self.positions_cache
                .entry(username.to_string())
                .or_insert_with(DashMap::new)
                .insert(symbol.to_string(), position.clone());

            Ok(Some(position))
        } else {
            Ok(None)
        }
    }

    pub async fn get_all_positions(&self, username: &str) -> Result<Vec<Position>> {
        let positions_db = self.database.query_all_positions(username).await?;
        // Convert database entities to domain models
        // Note: JPY (cash) quantities are already in JPY units, so we don't divide by 1000
        // Other symbols (BTC/JPY, etc.) need to divide by 1000 when loading
        let positions: Vec<Position> = positions_db
            .into_iter()
            .map(|p_db| {
                // JPYは既にJPY単位なので1000で割らない
                let (total_buy_qty, total_sell_qty, net_qty) = if p_db.symbol == "JPY" {
                    (p_db.total_buy_qty, p_db.total_sell_qty, p_db.net_qty)
                } else {
                    // 他のシンボルは1000で割る
                    (p_db.total_buy_qty / 1000.0, p_db.total_sell_qty / 1000.0, p_db.net_qty / 1000.0)
                };

                Position {
                    username: p_db.username,
                    symbol: p_db.symbol,
                    total_buy_qty,
                    total_buy_amount: p_db.total_buy_amount,
                    total_sell_qty,
                    total_sell_amount: p_db.total_sell_amount,
                    net_qty,
                    average_buy_price: p_db.average_buy_price,
                    average_sell_price: p_db.average_sell_price,
                    realized_pnl: p_db.realized_pnl,
                    last_updated: p_db.last_updated,
                }
            })
            .collect();

        // Update cache
        for position in &positions {
            self.positions_cache
                .entry(username.to_string())
                .or_insert_with(DashMap::new)
                .insert(position.symbol.clone(), position.clone());
        }

        Ok(positions)
    }

    pub async fn get_trade_history(&self, username: &str) -> Result<Vec<TradeHistory>> {
        self.database.query_trade_history(username).await
    }

    pub async fn get_trade_history_by_symbol(
        &self,
        username: &str,
        symbol: &str,
    ) -> Result<Vec<TradeHistory>> {
        self.database.query_trade_history_by_symbol(username, symbol).await
    }

    pub async fn get_cash_balance(&self, username: &str) -> Result<f64> {
        let position = self.get_position(username, CASH_SYMBOL).await?;
        if let Some(pos) = position {
            // Cash balance = total_buy_amount - total_sell_amount
            Ok(pos.total_buy_amount - pos.total_sell_amount)
        } else {
            Ok(0.0)
        }
    }

    pub async fn get_total_realized_pnl(&self, username: &str) -> Result<f64> {
        let positions = self.get_all_positions(username).await?;
        Ok(positions
            .iter()
            .map(|p| p.realized_pnl) // Include all realized PnL (both positive and negative)
            .sum())
    }

    pub async fn get_total_unrealized_pnl(
        &self,
        username: &str,
        current_prices: &std::collections::HashMap<String, f64>,
    ) -> Result<f64> {
        let positions = self.get_all_positions(username).await?;
        Ok(positions
            .iter()
            .map(|p| {
                current_prices
                    .get(&p.symbol)
                    .map(|price| p.get_unrealized_pnl(*price))
                    .unwrap_or(0.0)
            })
            .sum())
    }

    pub async fn get_total_pnl(
        &self,
        username: &str,
        current_prices: &std::collections::HashMap<String, f64>,
    ) -> Result<f64> {
        let realized = self.get_total_realized_pnl(username).await?;
        let unrealized = self.get_total_unrealized_pnl(username, current_prices).await?;
        Ok(realized + unrealized)
    }

    pub async fn get_total_trade_count(&self, username: &str) -> Result<i32> {
        let trades = self.get_trade_history(username).await?;
        Ok(trades.len() as i32)
    }

    pub async fn get_total_trading_volume(&self, username: &str) -> Result<f64> {
        let trades = self.get_trade_history(username).await?;
        Ok(trades
            .iter()
            .map(|t| if t.amount > 0.0 { t.amount } else { 0.0 })
            .sum())
    }

    pub async fn get_symbol_trade_counts(
        &self,
        username: &str,
    ) -> Result<std::collections::HashMap<String, i64>> {
        let trades = self.get_trade_history(username).await?;
        let mut counts = std::collections::HashMap::new();
        for trade in trades {
            *counts.entry(trade.symbol.clone()).or_insert(0) += 1;
        }
        Ok(counts)
    }

    async fn get_or_create_position(&self, username: &str, symbol: &str) -> Result<Position> {
        if let Some(position) = self.get_position(username, symbol).await? {
            Ok(position)
        } else {
            // Check database
            if let Some(position_db) = self.database.query_position(username, symbol).await? {
                let position = Position {
                    username: position_db.username,
                    symbol: position_db.symbol,
                    total_buy_qty: position_db.total_buy_qty,
                    total_buy_amount: position_db.total_buy_amount,
                    total_sell_qty: position_db.total_sell_qty,
                    total_sell_amount: position_db.total_sell_amount,
                    net_qty: position_db.net_qty,
                    average_buy_price: position_db.average_buy_price,
                    average_sell_price: position_db.average_sell_price,
                    realized_pnl: position_db.realized_pnl,
                    last_updated: position_db.last_updated,
                };
                Ok(position)
            } else {
                // Create new position
                Ok(Position::new(username.to_string(), symbol.to_string()))
            }
        }
    }


    async fn update_cash_balance(&self, username: &str, amount: f64) -> Result<()> {
        let mut cash_position = self.get_or_create_position(username, CASH_SYMBOL).await?;

        if amount > 0.0 {
            // Increase cash
            cash_position.add_buy_trade(amount, 1.0);
        } else if amount < 0.0 {
            // Decrease cash
            let abs_amount = amount.abs();
            let current_balance = self.get_cash_balance(username).await?;
            if current_balance < abs_amount {
                return Err(anyhow::anyhow!(
                    "Insufficient cash balance. Required: {}, Available: {}",
                    abs_amount,
                    current_balance
                ));
            }
            cash_position.add_sell_trade(abs_amount, 1.0);
        }

        // Save to database
        // Note: JPY (cash) quantities are already in JPY units, so we don't multiply by 1000
        // Other symbols (BTC/JPY, etc.) need to multiply by 1000 when saving
        let cash_position_id = format!("{}_{}", username, CASH_SYMBOL.to_uppercase());
        let position_db = crate::models::Position {
            id: Some(cash_position_id),
            username: cash_position.username.clone(),
            symbol: cash_position.symbol.clone(),
            unit: "JPY".to_string(),
            total_buy_qty: cash_position.total_buy_qty, // JPYは既にJPY単位なので1000倍しない
            total_buy_amount: cash_position.total_buy_amount,
            total_sell_qty: cash_position.total_sell_qty, // JPYは既にJPY単位なので1000倍しない
            total_sell_amount: cash_position.total_sell_amount,
            net_qty: cash_position.net_qty, // JPYは既にJPY単位なので1000倍しない
            average_buy_price: cash_position.average_buy_price,
            average_sell_price: cash_position.average_sell_price,
            realized_pnl: cash_position.realized_pnl,
            last_updated: cash_position.last_updated,
        };

        if let Err(e) = self.database.upsert_position(&position_db).await {
            error!("Failed to save cash position: {}", e);
        }

        // Update cache
        self.positions_cache
            .entry(username.to_string())
            .or_insert_with(DashMap::new)
            .insert(CASH_SYMBOL.to_string(), cash_position);

        Ok(())
    }
}

