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


    /// 新規ユーザーの初期JPY残高を設定する。
    /// 既にJPYポジションが存在する場合は何もしない（冪等性保証）。
    pub async fn initialize_cash_balance(&self, username: &str, amount: f64) -> Result<()> {
        // 既にポジションがあれば何もしない
        if self.get_position(username, CASH_SYMBOL).await?.is_some() {
            return Ok(());
        }

        let mut cash_position = crate::position::position::Position::new(
            username.to_string(),
            CASH_SYMBOL.to_string(),
        );
        cash_position.add_buy_trade(amount, 1.0);

        let cash_position_id = format!("{}_{}", username, CASH_SYMBOL.to_uppercase());
        let position_db = crate::models::Position {
            id: Some(cash_position_id),
            username: cash_position.username.clone(),
            symbol: cash_position.symbol.clone(),
            unit: "JPY".to_string(),
            total_buy_qty: cash_position.total_buy_qty,
            total_buy_amount: cash_position.total_buy_amount,
            total_sell_qty: cash_position.total_sell_qty,
            total_sell_amount: cash_position.total_sell_amount,
            net_qty: cash_position.net_qty,
            average_buy_price: cash_position.average_buy_price,
            average_sell_price: cash_position.average_sell_price,
            realized_pnl: cash_position.realized_pnl,
            last_updated: cash_position.last_updated,
        };

        self.database
            .upsert_position(&position_db)
            .await
            .map_err(|e| anyhow::anyhow!("Failed to initialize cash balance: {}", e))?;

        self.positions_cache
            .entry(username.to_string())
            .or_insert_with(DashMap::new)
            .insert(CASH_SYMBOL.to_string(), cash_position);

        info!(
            "Initialized cash balance for new user: {}, amount: {}",
            username, amount
        );

        Ok(())
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::database::test_helpers::{create_mock_database, setup_mock_database_with_defaults};
    use crate::config::{
        Config, ServerConfig, PostgresConfig, WebSocketConfig,
        BitflyerWebSocketConfig, GmoWebSocketConfig, JwtConfig, InstrumentsConfig,
    };
    use mockall::predicate::*;
    use std::collections::HashMap;

    fn create_test_config() -> Config {
        Config {
            server: ServerConfig { port: 8080 },
            postgres: PostgresConfig {
                host: "localhost".to_string(),
                port: 5432,
                database: "test".to_string(),
                user: "test".to_string(),
                password: "test".to_string(),
                max_connections: 5,
            },
            websocket: WebSocketConfig {
                bitflyer: BitflyerWebSocketConfig {
                    enabled: false,
                    url: "ws://localhost".to_string(),
                    reconnect_delay_ms: 1000,
                    max_reconnect_attempts: 3,
                },
                gmo: GmoWebSocketConfig {
                    enabled: false,
                    url: "ws://localhost".to_string(),
                    reconnect_delay_ms: 1000,
                    max_reconnect_attempts: 3,
                },
            },
            jwt: JwtConfig {
                secret: "test_secret".to_string(),
                expiration_seconds: 3600,
            },
            instruments: InstrumentsConfig {
                instruments: HashMap::new(),
            },
        }
    }

    // ── initialize_cash_balance ──────────────────────────────────────────────

    /// 新規ユーザーに 1,000,000 JPY が付与されること
    #[tokio::test]
    async fn test_initialize_cash_balance_sets_initial_balance() {
        let mut mock = create_mock_database();
        setup_mock_database_with_defaults(&mut mock);

        // upsert_position が1回呼ばれることを期待
        mock.expect_upsert_position()
            .times(1)
            .returning(|_| Ok(()));

        let manager = PositionManager::new(mock, create_test_config());

        let result = manager
            .initialize_cash_balance("newuser", 1_000_000.0)
            .await;
        assert!(result.is_ok(), "initialize_cash_balance should succeed");

        // キャッシュ経由でポジションを確認
        let cash = manager.get_position("newuser", "JPY").await.unwrap();
        assert!(cash.is_some(), "JPY position should exist after initialization");
        let cash = cash.unwrap();
        assert_eq!(cash.username, "newuser");
        assert_eq!(cash.symbol, "JPY");
        // 初期残高 = total_buy_amount - total_sell_amount = 1,000,000
        let balance = cash.total_buy_amount - cash.total_sell_amount;
        assert!(
            (balance - 1_000_000.0).abs() < 1e-9,
            "Initial balance should be 1,000,000 JPY, got {}",
            balance
        );
    }

    /// 既にJPYポジションが存在する場合は upsert_position を呼ばず冪等に動作すること
    #[tokio::test]
    async fn test_initialize_cash_balance_idempotent_when_position_exists() {
        let mut mock = create_mock_database();

        // キャッシュにJPYポジションを事前に登録するため、query_position が
        // JPY に対して既存ポジションを返すよう設定する
        let existing_position = crate::models::Position {
            id: Some("newuser_JPY".to_string()),
            username: "newuser".to_string(),
            symbol: "JPY".to_string(),
            unit: "JPY".to_string(),
            total_buy_qty: 1_000_000.0,
            total_buy_amount: 1_000_000.0,
            total_sell_qty: 0.0,
            total_sell_amount: 0.0,
            net_qty: 1_000_000.0,
            average_buy_price: 1.0,
            average_sell_price: 0.0,
            realized_pnl: 0.0,
            last_updated: chrono::Utc::now(),
        };
        mock.expect_query_position()
            .with(eq("newuser"), eq("JPY"))
            .returning(move |_, _| Ok(Some(existing_position.clone())));
        mock.expect_query_position()
            .returning(|_, _| Ok(None));

        // upsert_position は呼ばれないこと
        mock.expect_upsert_position()
            .times(0);

        let manager = PositionManager::new(mock, create_test_config());

        let result = manager
            .initialize_cash_balance("newuser", 1_000_000.0)
            .await;
        assert!(result.is_ok(), "initialize_cash_balance should succeed even if position exists");
    }

    /// DBエラー時に Err が返ること
    #[tokio::test]
    async fn test_initialize_cash_balance_returns_err_on_db_failure() {
        let mut mock = create_mock_database();
        setup_mock_database_with_defaults(&mut mock);

        mock.expect_upsert_position()
            .times(1)
            .returning(|_| Err(anyhow::anyhow!("DB connection failed")));

        let manager = PositionManager::new(mock, create_test_config());

        let result = manager
            .initialize_cash_balance("newuser", 1_000_000.0)
            .await;
        assert!(result.is_err(), "Should return Err when DB fails");
        let err_msg = result.unwrap_err().to_string();
        assert!(
            err_msg.contains("Failed to initialize cash balance"),
            "Error message should describe the failure, got: {}",
            err_msg
        );
    }

    /// 異なる複数ユーザーへの初期化がそれぞれ独立して機能すること
    #[tokio::test]
    async fn test_initialize_cash_balance_multiple_users() {
        let mut mock = create_mock_database();
        setup_mock_database_with_defaults(&mut mock);

        mock.expect_upsert_position()
            .times(2)
            .returning(|_| Ok(()));

        let manager = PositionManager::new(mock, create_test_config());

        manager
            .initialize_cash_balance("user_a", 1_000_000.0)
            .await
            .unwrap();
        manager
            .initialize_cash_balance("user_b", 1_000_000.0)
            .await
            .unwrap();

        let cash_a = manager.get_position("user_a", "JPY").await.unwrap().unwrap();
        let cash_b = manager.get_position("user_b", "JPY").await.unwrap().unwrap();

        assert_eq!(cash_a.username, "user_a");
        assert_eq!(cash_b.username, "user_b");
        // 残高が互いに独立していること
        let balance_a = cash_a.total_buy_amount - cash_a.total_sell_amount;
        let balance_b = cash_b.total_buy_amount - cash_b.total_sell_amount;
        assert!((balance_a - 1_000_000.0).abs() < 1e-9);
        assert!((balance_b - 1_000_000.0).abs() < 1e-9);
    }
}
