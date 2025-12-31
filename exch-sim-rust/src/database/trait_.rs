use crate::models::{Execution, Position, TradeHistory, UserWithRoles};
use anyhow::Result;
use chrono::{DateTime, Utc};

#[async_trait::async_trait]
pub trait DatabaseTrait: Send + Sync {
    async fn insert_execution(&self, execution: &Execution) -> Result<()>;
    async fn query_recent_executions(&self, from_time: DateTime<Utc>) -> Result<Vec<Execution>>;
    async fn query_executions_paginated(
        &self,
        username: &str,
        page: i32,
        size: i32,
        symbol: Option<&str>,
    ) -> Result<Vec<Execution>>;
    async fn count_executions(&self, username: &str, symbol: Option<&str>) -> Result<i64>;
    async fn query_executions_by_symbol_all_users(
        &self,
        symbol: &str,
        page: i32,
        size: i32,
    ) -> Result<Vec<Execution>>;
    async fn count_executions_by_symbol_all_users(&self, symbol: &str) -> Result<i64>;
    async fn calculate_volume_by_symbol(
        &self,
        symbol: &str,
        from_time: DateTime<Utc>,
        to_time: DateTime<Utc>,
    ) -> Result<(i64, i64)>;
    async fn calculate_total_volume(
        &self,
        from_time: DateTime<Utc>,
        to_time: DateTime<Utc>,
    ) -> Result<(i64, i64)>;
    async fn upsert_position(&self, position: &Position) -> Result<()>;
    async fn query_position(&self, username: &str, symbol: &str) -> Result<Option<Position>>;
    async fn query_all_positions(&self, username: &str) -> Result<Vec<Position>>;
    async fn insert_trade_history(&self, trade_history: &TradeHistory) -> Result<()>;
    async fn query_trade_history(&self, username: &str) -> Result<Vec<TradeHistory>>;
    async fn query_trade_history_by_symbol(&self, username: &str, symbol: &str) -> Result<Vec<TradeHistory>>;
    async fn query_trade_history_by_cl_ord_id(&self, cl_ord_id: &str) -> Result<Vec<TradeHistory>>;
    async fn register_user(&self, username: &str, encoded_password: &str, roles: &[String]) -> Result<()>;
    async fn user_exists(&self, username: &str) -> Result<bool>;
    async fn load_user(&self, username: &str) -> Result<Option<UserWithRoles>>;
}

