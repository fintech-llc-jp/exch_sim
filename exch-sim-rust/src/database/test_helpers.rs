#[cfg(test)]
use crate::database::mock::MockMockDatabaseTrait;
#[cfg(test)]
use crate::models::Position;
#[cfg(test)]
use chrono::Utc;
#[cfg(test)]
use mockall::predicate::*;

#[cfg(test)]
pub fn create_mock_database() -> MockMockDatabaseTrait {
    MockMockDatabaseTrait::new()
}

#[cfg(test)]
pub fn setup_mock_database_with_defaults(mock: &mut MockMockDatabaseTrait) {
    // デフォルトの動作を設定
    // ポジションクエリは空を返す
    mock.expect_query_position()
        .returning(|_, _| Ok(None));

    // 全ポジションクエリは空のベクターを返す
    mock.expect_query_all_positions()
        .returning(|_| Ok(Vec::new()));

    // 取引履歴クエリは空のベクターを返す
    mock.expect_query_trade_history()
        .returning(|_| Ok(Vec::new()));

    // 取引履歴（シンボル別）は空のベクターを返す
    mock.expect_query_trade_history_by_symbol()
        .returning(|_, _| Ok(Vec::new()));

    // ユーザー存在チェックはfalseを返す
    mock.expect_user_exists()
        .returning(|_| Ok(false));

    // ユーザーロードはNoneを返す
    mock.expect_load_user()
        .returning(|_| Ok(None));
}

#[cfg(test)]
pub fn setup_mock_database_with_cash_balance(
    mock: &mut MockMockDatabaseTrait,
    username: &str,
    initial_cash: f64,
) {
    let username_owned = username.to_string();
    let cash_position = Position {
        id: Some(format!("{}_JPY", username_owned)),
        username: username_owned.clone(),
        symbol: "JPY".to_string(),
        unit: "JPY".to_string(),
        total_buy_qty: initial_cash,
        total_buy_amount: initial_cash,
        total_sell_qty: 0.0,
        total_sell_amount: 0.0,
        net_qty: initial_cash,
        average_buy_price: 1.0,
        average_sell_price: 0.0,
        realized_pnl: 0.0,
        last_updated: Utc::now(),
    };

    // JPYポジションを返す
    mock.expect_query_position()
        .with(eq(username_owned.clone()), eq("JPY"))
        .returning(move |_, _| Ok(Some(cash_position.clone())));

    // その他のシンボルはNoneを返す
    mock.expect_query_position()
        .returning(|_, _| Ok(None));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::database::trait_::DatabaseTrait;

    #[tokio::test]
    async fn test_mock_database_creation() {
        let mut mock = create_mock_database();
        
        // デフォルトの動作を設定
        setup_mock_database_with_defaults(&mut mock);
        
        // テスト: ポジションクエリがNoneを返すことを確認
        let result = mock.query_position("testuser", "BTCJPY").await;
        assert!(result.is_ok());
        assert!(result.unwrap().is_none());
    }

    #[tokio::test]
    async fn test_mock_database_with_cash_balance() {
        let mut mock = create_mock_database();
        
        // 現金残高を設定
        setup_mock_database_with_cash_balance(&mut mock, "testuser", 1000000.0);
        
        // テスト: JPYポジションが返されることを確認
        let result = mock.query_position("testuser", "JPY").await;
        assert!(result.is_ok());
        let position = result.unwrap();
        assert!(position.is_some());
        let pos = position.unwrap();
        assert_eq!(pos.username, "testuser");
        assert_eq!(pos.symbol, "JPY");
        assert_eq!(pos.net_qty, 1000000.0);
    }
}
