use crate::config::Config;
use crate::database::trait::DatabaseTrait;
use crate::models::{Execution, Position, TradeHistory, User, UserWithRoles};
use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use sqlx::PgPool;
use std::sync::Arc;

#[derive(Clone)]
pub struct DatabaseImpl {
    pool: Arc<PgPool>,
}

impl DatabaseImpl {
    pub async fn new(config: &Config) -> Result<Self> {
        let database_url = config.get_database_url();
        let pool = PgPool::connect(&database_url)
            .await
            .context("Failed to connect to PostgreSQL")?;
        Ok(Self {
            pool: Arc::new(pool),
        })
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }
}

#[async_trait::async_trait]
impl DatabaseTrait for DatabaseImpl {
    async fn insert_execution(&self, execution: &Execution) -> Result<()> {
        sqlx::query(
            r#"
            INSERT INTO executions (
                exec_id, order_id, username, symbol, exec_status,
                last_px, last_qty, counter_party_username, created_at,
                is_market_maker, side
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            ON CONFLICT (exec_id) DO NOTHING
            "#,
        )
        .bind(&execution.exec_id)
        .bind(&execution.order_id)
        .bind(&execution.username)
        .bind(&execution.symbol)
        .bind(execution.exec_status.to_string())
        .bind(execution.last_px)
        .bind(execution.last_qty)
        .bind(&execution.counter_party_username)
        .bind(execution.created_at)
        .bind(execution.is_market_maker)
        .bind(&execution.side)
        .execute(&*self.pool)
        .await
        .with_context(|| format!(
            "Failed to insert execution: exec_id={}, username={}, symbol={}, exec_status={:?}, last_px={}, last_qty={}",
            execution.exec_id,
            execution.username,
            execution.symbol,
            execution.exec_status,
            execution.last_px,
            execution.last_qty
        ))?;
        Ok(())
    }

    async fn query_recent_executions(
        &self,
        from_time: DateTime<Utc>,
    ) -> Result<Vec<Execution>> {
        let rows = sqlx::query(
            r#"
            SELECT
                exec_id, order_id, username, symbol, exec_status,
                last_px, last_qty, counter_party_username, created_at,
                is_market_maker, side
            FROM executions
            WHERE created_at >= $1
            ORDER BY created_at DESC
            "#,
        )
        .bind(from_time)
        .fetch_all(&*self.pool)
        .await
        .context("Failed to query recent executions")?;

        use sqlx::Row;
        let executions: Vec<Execution> = rows
            .into_iter()
            .map(|row| {
                let exec_status_str: String = row.get("exec_status");
                let exec_status = match exec_status_str.as_str() {
                    "NEW" => crate::models::ExecStatus::New,
                    "PARTIAL_FILL" | "PARTIALLY_FILLED" => crate::models::ExecStatus::PartiallyFilled,
                    "FILLED" => crate::models::ExecStatus::Filled,
                    "CANCELED" => crate::models::ExecStatus::Canceled,
                    "REJECTED" => crate::models::ExecStatus::Rejected,
                    _ => crate::models::ExecStatus::New,
                };

                let order_id: String = row.get("order_id");
                Execution {
                    exec_id: row.get("exec_id"),
                    order_id: order_id.clone(),
                    cl_ord_id: order_id, // order_idにcl_ord_idの値が格納されている（Java版と同じ）
                    username: row.get("username"),
                    symbol: row.get("symbol"),
                    exec_status,
                    last_px: row.get("last_px"),
                    last_qty: row.get("last_qty"),
                    counter_party_username: row.get("counter_party_username"),
                    created_at: row.get("created_at"),
                    is_market_maker: row.get("is_market_maker"),
                    side: row.get("side"),
                }
            })
            .collect();
        Ok(executions)
    }

    async fn upsert_position(&self, position: &Position) -> Result<()> {
        let position_id = position.id.as_ref()
            .ok_or_else(|| anyhow::anyhow!("Position id is required for upsert"))?;
        
        sqlx::query(
            r#"
            INSERT INTO positions (
                id, username, symbol, unit, total_buy_qty, total_buy_amount,
                total_sell_qty, total_sell_amount, net_qty,
                average_buy_price, average_sell_price, realized_pnl, last_updated
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
            ON CONFLICT (id)
            DO UPDATE SET
                total_buy_qty = EXCLUDED.total_buy_qty,
                total_buy_amount = EXCLUDED.total_buy_amount,
                total_sell_qty = EXCLUDED.total_sell_qty,
                total_sell_amount = EXCLUDED.total_sell_amount,
                net_qty = EXCLUDED.net_qty,
                average_buy_price = EXCLUDED.average_buy_price,
                average_sell_price = EXCLUDED.average_sell_price,
                realized_pnl = EXCLUDED.realized_pnl,
                last_updated = EXCLUDED.last_updated
            "#,
        )
        .bind(position_id)
        .bind(&position.username)
        .bind(&position.symbol)
        .bind(&position.unit)
        .bind(position.total_buy_qty)
        .bind(position.total_buy_amount)
        .bind(position.total_sell_qty)
        .bind(position.total_sell_amount)
        .bind(position.net_qty)
        .bind(position.average_buy_price)
        .bind(position.average_sell_price)
        .bind(position.realized_pnl)
        .bind(position.last_updated)
        .execute(&*self.pool)
        .await
        .context("Failed to upsert position")?;
        Ok(())
    }

    async fn query_position(
        &self,
        username: &str,
        symbol: &str,
    ) -> Result<Option<Position>> {
        let row = sqlx::query(
            r#"
            SELECT
                id, username, symbol, unit,
                total_buy_qty::double precision as total_buy_qty, 
                total_buy_amount,
                total_sell_qty::double precision as total_sell_qty, 
                total_sell_amount, 
                net_qty::double precision as net_qty,
                average_buy_price, average_sell_price, realized_pnl, 
                last_updated::timestamptz as last_updated
            FROM positions
            WHERE username = $1 AND symbol = $2
            "#,
        )
        .bind(username)
        .bind(symbol)
        .fetch_optional(&*self.pool)
        .await
        .context("Failed to query position")?;

        if let Some(row) = row {
            use sqlx::Row;
            Ok(Some(Position {
                id: row.get("id"),
                username: row.get("username"),
                symbol: row.get("symbol"),
                unit: row.get("unit"),
                total_buy_qty: row.get("total_buy_qty"),
                total_buy_amount: row.get("total_buy_amount"),
                total_sell_qty: row.get("total_sell_qty"),
                total_sell_amount: row.get("total_sell_amount"),
                net_qty: row.get("net_qty"),
                average_buy_price: row.get("average_buy_price"),
                average_sell_price: row.get("average_sell_price"),
                realized_pnl: row.get("realized_pnl"),
                last_updated: row.get("last_updated"),
            }))
        } else {
            Ok(None)
        }
    }

    async fn query_all_positions(&self, username: &str) -> Result<Vec<Position>> {
        let rows = sqlx::query(
            r#"
            SELECT
                id, username, symbol, unit,
                total_buy_qty::double precision as total_buy_qty, 
                total_buy_amount,
                total_sell_qty::double precision as total_sell_qty, 
                total_sell_amount, 
                net_qty::double precision as net_qty,
                average_buy_price, average_sell_price, realized_pnl, 
                last_updated::timestamptz as last_updated
            FROM positions
            WHERE username = $1
            ORDER BY symbol
            "#,
        )
        .bind(username)
        .fetch_all(&*self.pool)
        .await
        .context("Failed to query all positions")?;

        use sqlx::Row;
        let positions: Vec<Position> = rows
            .into_iter()
            .map(|row| Position {
                id: row.get("id"),
                username: row.get("username"),
                symbol: row.get("symbol"),
                unit: row.get("unit"),
                total_buy_qty: row.get("total_buy_qty"),
                total_buy_amount: row.get("total_buy_amount"),
                total_sell_qty: row.get("total_sell_qty"),
                total_sell_amount: row.get("total_sell_amount"),
                net_qty: row.get("net_qty"),
                average_buy_price: row.get("average_buy_price"),
                average_sell_price: row.get("average_sell_price"),
                realized_pnl: row.get("realized_pnl"),
                last_updated: row.get("last_updated"),
            })
            .collect();

        Ok(positions)
    }

    async fn insert_trade_history(&self, trade_history: &TradeHistory) -> Result<()> {
        sqlx::query(
            r#"
            INSERT INTO trade_history (
                exec_id, username, symbol, side, quantity, price, amount,
                counter_party_username, timestamp, cl_ord_id, is_market_maker,
                open_close, profit_loss, matched_open_exec_ids
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
            ON CONFLICT (exec_id) DO NOTHING
            "#,
        )
        .bind(&trade_history.exec_id)
        .bind(&trade_history.username)
        .bind(&trade_history.symbol)
        .bind(&trade_history.side)
        .bind(trade_history.quantity)
        .bind(trade_history.price)
        .bind(trade_history.amount)
        .bind(&trade_history.counter_party_username)
        .bind(trade_history.timestamp)
        .bind(&trade_history.cl_ord_id)
        .bind(trade_history.is_market_maker)
        .bind(&trade_history.open_close)
        .bind(trade_history.profit_loss)
        .bind(&trade_history.matched_open_exec_ids)
        .execute(&*self.pool)
        .await
        .context("Failed to insert trade history")?;
        Ok(())
    }

    async fn query_trade_history(&self, username: &str) -> Result<Vec<TradeHistory>> {
        let trades = sqlx::query_as::<_, TradeHistory>(
            r#"
            SELECT
                exec_id, username, symbol, side, quantity, price, amount,
                counter_party_username, timestamp, cl_ord_id, is_market_maker,
                open_close, profit_loss, matched_open_exec_ids
            FROM trade_history
            WHERE username = $1
            ORDER BY timestamp DESC
            "#,
        )
        .bind(username)
        .fetch_all(&*self.pool)
        .await
        .context("Failed to query trade history")?;
        Ok(trades)
    }

    async fn query_trade_history_by_symbol(
        &self,
        username: &str,
        symbol: &str,
    ) -> Result<Vec<TradeHistory>> {
        let trades = sqlx::query_as::<_, TradeHistory>(
            r#"
            SELECT
                exec_id, username, symbol, side, quantity, price, amount,
                counter_party_username, timestamp, cl_ord_id, is_market_maker,
                open_close, profit_loss, matched_open_exec_ids
            FROM trade_history
            WHERE username = $1 AND symbol = $2
            ORDER BY timestamp DESC
            "#,
        )
        .bind(username)
        .bind(symbol)
        .fetch_all(&*self.pool)
        .await
        .context("Failed to query trade history by symbol")?;
        Ok(trades)
    }

    async fn query_trade_history_by_cl_ord_id(
        &self,
        cl_ord_id: &str,
    ) -> Result<Vec<TradeHistory>> {
        let trades = sqlx::query_as::<_, TradeHistory>(
            r#"
            SELECT
                exec_id, username, symbol, side, quantity, price, amount,
                counter_party_username, timestamp, cl_ord_id, is_market_maker,
                open_close, profit_loss, matched_open_exec_ids
            FROM trade_history
            WHERE cl_ord_id = $1
            ORDER BY timestamp ASC
            "#,
        )
        .bind(cl_ord_id)
        .fetch_all(&*self.pool)
        .await
        .context("Failed to query trade history by cl_ord_id")?;
        Ok(trades)
    }

    async fn register_user(
        &self,
        username: &str,
        encoded_password: &str,
        roles: &[String],
    ) -> Result<()> {
        let mut tx = self.pool.begin().await.context("Failed to begin transaction")?;

        // Insert user
        sqlx::query(
            r#"
            INSERT INTO users (username, password, created_at, updated_at)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (username) DO NOTHING
            "#,
        )
        .bind(username)
        .bind(encoded_password)
        .bind(Utc::now())
        .bind(Utc::now())
        .execute(&mut *tx)
        .await
        .context("Failed to insert user")?;

        // Insert roles
        for role in roles {
            sqlx::query(
                r#"
                INSERT INTO user_roles (username, role)
                VALUES ($1, $2)
                ON CONFLICT (username, role) DO NOTHING
                "#,
            )
            .bind(username)
            .bind(role)
            .execute(&mut *tx)
            .await
            .context("Failed to insert user role")?;
        }

        tx.commit().await.context("Failed to commit transaction")?;
        Ok(())
    }

    async fn user_exists(&self, username: &str) -> Result<bool> {
        let exists = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM users WHERE username = $1)",
        )
        .bind(username)
        .fetch_one(&*self.pool)
        .await
        .context("Failed to check if user exists")?;
        Ok(exists)
    }

    async fn load_user(&self, username: &str) -> Result<Option<UserWithRoles>> {
        let user = sqlx::query_as::<_, User>(
            "SELECT username, password, created_at, updated_at FROM users WHERE username = $1",
        )
        .bind(username)
        .fetch_optional(&*self.pool)
        .await
        .context("Failed to load user")?;

        if let Some(user) = user {
            let roles = sqlx::query_scalar::<_, String>(
                "SELECT role FROM user_roles WHERE username = $1",
            )
            .bind(&user.username)
            .fetch_all(&*self.pool)
            .await
            .context("Failed to load user roles")?;

            Ok(Some(UserWithRoles {
                username: user.username,
                password: user.password,
                roles,
                created_at: user.created_at,
                updated_at: user.updated_at,
            }))
        } else {
            Ok(None)
        }
    }
}

// Implement FromRow for Execution
impl<'r> sqlx::FromRow<'r, sqlx::postgres::PgRow> for Execution {
    fn from_row(row: &'r sqlx::postgres::PgRow) -> Result<Self, sqlx::Error> {
        use sqlx::Row;
        Ok(Execution {
            exec_id: row.get("exec_id"),
            order_id: row.get("order_id"),
            cl_ord_id: row.get("cl_ord_id"),
            username: row.get("username"),
            symbol: row.get("symbol"),
            exec_status: parse_exec_status(row.get::<String, _>("exec_status")),
            last_px: row.get("last_px"),
            last_qty: row.get("last_qty"),
            counter_party_username: row.get("counter_party_username"),
            created_at: row.get("created_at"),
            is_market_maker: row.get("is_market_maker"),
            side: row.get("side"),
        })
    }
}

fn parse_exec_status(s: String) -> crate::models::ExecStatus {
    match s.as_str() {
        "NEW" => crate::models::ExecStatus::New,
        "PARTIALLY_FILLED" => crate::models::ExecStatus::PartiallyFilled,
        "FILLED" => crate::models::ExecStatus::Filled,
        "CANCELED" => crate::models::ExecStatus::Canceled,
        "REJECTED" => crate::models::ExecStatus::Rejected,
        _ => crate::models::ExecStatus::New,
    }
}

impl crate::models::ExecStatus {
    pub fn to_string(&self) -> String {
        match self {
            Self::New => "NEW".to_string(),
            Self::PartiallyFilled => "PARTIALLY_FILLED".to_string(),
            Self::Filled => "FILLED".to_string(),
            Self::Canceled => "CANCELED".to_string(),
            Self::Rejected => "REJECTED".to_string(),
        }
    }
}


