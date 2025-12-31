use chrono::{DateTime, Utc};

#[derive(Debug, Clone)]
pub struct Position {
    pub username: String,
    pub symbol: String,
    pub total_buy_qty: f64,
    pub total_buy_amount: f64,
    pub total_sell_qty: f64,
    pub total_sell_amount: f64,
    pub net_qty: f64,
    pub average_buy_price: f64,
    pub average_sell_price: f64,
    pub realized_pnl: f64,
    pub last_updated: DateTime<Utc>,
}

impl Position {
    pub fn new(username: String, symbol: String) -> Self {
        Self {
            username,
            symbol,
            total_buy_qty: 0.0,
            total_buy_amount: 0.0,
            total_sell_qty: 0.0,
            total_sell_amount: 0.0,
            net_qty: 0.0,
            average_buy_price: 0.0,
            average_sell_price: 0.0,
            realized_pnl: 0.0,
            last_updated: Utc::now(),
        }
    }

    pub fn add_buy_trade(&mut self, quantity: f64, price: f64) {
        if quantity <= 0.0 || price <= 0.0 {
            return;
        }

        let previous_net_qty = self.net_qty;

        // Calculate realized P/L if closing short position
        if previous_net_qty < 0.0 {
            let realized_qty = quantity.min(previous_net_qty.abs());
            self.realized_pnl += realized_qty * (self.average_sell_price - price);
        }

        // Update buy position
        self.total_buy_amount += quantity * price;
        self.total_buy_qty += quantity;
        self.average_buy_price = if self.total_buy_qty > 0.0 {
            self.total_buy_amount / self.total_buy_qty
        } else {
            0.0
        };

        // Update net quantity
        self.net_qty = previous_net_qty + quantity;

        // Reset if position is flat
        if self.net_qty.abs() < 1e-10 {
            self.total_buy_qty = 0.0;
            self.total_buy_amount = 0.0;
            self.total_sell_qty = 0.0;
            self.total_sell_amount = 0.0;
            self.average_buy_price = 0.0;
            self.average_sell_price = 0.0;
        } else if previous_net_qty < 0.0 && self.net_qty > 0.0 {
            // Flipped from short to long
            self.total_sell_qty = 0.0;
            self.total_sell_amount = 0.0;
            self.average_sell_price = 0.0;
            self.total_buy_qty = self.net_qty;
            self.total_buy_amount = self.net_qty * price;
            self.average_buy_price = price;
        }

        self.last_updated = Utc::now();
    }

    pub fn add_sell_trade(&mut self, quantity: f64, price: f64) {
        if quantity <= 0.0 || price <= 0.0 {
            return;
        }

        let previous_net_qty = self.net_qty;

        // Calculate realized P/L if closing long position
        if previous_net_qty > 0.0 {
            let realized_qty = quantity.min(previous_net_qty);
            self.realized_pnl += realized_qty * (price - self.average_buy_price);
        }

        // Update sell position
        self.total_sell_amount += quantity * price;
        self.total_sell_qty += quantity;
        self.average_sell_price = if self.total_sell_qty > 0.0 {
            self.total_sell_amount / self.total_sell_qty
        } else {
            0.0
        };

        // Update net quantity
        self.net_qty = previous_net_qty - quantity;

        // Reset if position is flat
        if self.net_qty.abs() < 1e-10 {
            self.total_buy_qty = 0.0;
            self.total_buy_amount = 0.0;
            self.total_sell_qty = 0.0;
            self.total_sell_amount = 0.0;
            self.average_buy_price = 0.0;
            self.average_sell_price = 0.0;
        } else if previous_net_qty > 0.0 && self.net_qty < 0.0 {
            // Flipped from long to short
            self.total_buy_qty = 0.0;
            self.total_buy_amount = 0.0;
            self.average_buy_price = 0.0;
            self.total_sell_qty = self.net_qty.abs();
            self.total_sell_amount = self.net_qty.abs() * price;
            self.average_sell_price = price;
        }

        self.last_updated = Utc::now();
    }

    pub fn get_unrealized_pnl(&self, current_price: f64) -> f64 {
        if self.net_qty.abs() < 1e-10 || current_price <= 0.0 {
            return 0.0;
        }

        if self.net_qty > 0.0 {
            // Long position
            (current_price - self.average_buy_price) * self.net_qty
        } else {
            // Short position
            (self.average_sell_price - current_price) * self.net_qty.abs()
        }
    }

    pub fn is_flat(&self) -> bool {
        self.net_qty.abs() < 1e-10
    }

    pub fn is_long_position(&self) -> bool {
        self.net_qty > 1e-10
    }

    pub fn is_short_position(&self) -> bool {
        self.net_qty < -1e-10
    }

    pub fn get_total_pnl(&self, current_price: f64) -> f64 {
        self.realized_pnl + self.get_unrealized_pnl(current_price)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_position() -> Position {
        Position::new("testuser".to_string(), "BTCJPY".to_string())
    }

    #[test]
    fn test_initial_position() {
        let position = create_test_position();
        assert_eq!(position.username, "testuser");
        assert_eq!(position.symbol, "BTCJPY");
        assert_eq!(position.total_buy_qty, 0.0);
        assert_eq!(position.total_sell_qty, 0.0);
        assert_eq!(position.net_qty, 0.0);
        assert_eq!(position.realized_pnl, 0.0);
        assert!(position.is_flat());
        assert!(!position.is_long_position());
        assert!(!position.is_short_position());
    }

    #[test]
    fn test_add_buy_trade() {
        let mut position = create_test_position();
        position.add_buy_trade(10.0, 100.0);

        assert_eq!(position.total_buy_qty, 10.0);
        assert_eq!(position.total_buy_amount, 1000.0);
        assert_eq!(position.average_buy_price, 100.0);
        assert_eq!(position.net_qty, 10.0);
        assert!(position.is_long_position());
    }

    #[test]
    fn test_add_sell_trade() {
        let mut position = create_test_position();
        position.add_sell_trade(5.0, 110.0);

        assert_eq!(position.total_sell_qty, 5.0);
        assert_eq!(position.total_sell_amount, 550.0);
        assert_eq!(position.average_sell_price, 110.0);
        assert_eq!(position.net_qty, -5.0);
        assert!(position.is_short_position());
    }

    #[test]
    fn test_realized_pnl_calculation() {
        let mut position = create_test_position();
        // 100で10枚買い
        position.add_buy_trade(10.0, 100.0);
        assert_eq!(position.realized_pnl, 0.0);

        // 110で5枚売り（利益50）
        position.add_sell_trade(5.0, 110.0);
        assert!((position.realized_pnl - 50.0).abs() < 1e-10); // 5 * (110 - 100)
        assert_eq!(position.net_qty, 5.0);
    }

    #[test]
    fn test_multiple_buy_trades() {
        let mut position = create_test_position();
        position.add_buy_trade(10.0, 100.0);  // 10 * 100 = 1000
        position.add_buy_trade(5.0, 120.0);   // 5 * 120 = 600

        assert_eq!(position.total_buy_qty, 15.0);
        assert_eq!(position.total_buy_amount, 1600.0);
        assert!((position.average_buy_price - (1600.0 / 15.0)).abs() < 1e-10);
    }

    #[test]
    fn test_unrealized_pnl() {
        let mut position = create_test_position();
        position.add_buy_trade(10.0, 100.0);

        // 現在価格110の場合、含み益は10 * (110 - 100) = 100
        assert!((position.get_unrealized_pnl(110.0) - 100.0).abs() < 1e-10);

        // 現在価格90の場合、含み損は10 * (90 - 100) = -100
        assert!((position.get_unrealized_pnl(90.0) - (-100.0)).abs() < 1e-10);
    }

    #[test]
    fn test_total_pnl() {
        let mut position = create_test_position();
        position.add_buy_trade(10.0, 100.0);
        position.add_sell_trade(5.0, 110.0); // 実現損益 50

        // 残り5枚、現在価格105の場合
        // 実現損益50 + 含み損益25 = 75
        assert!((position.get_total_pnl(105.0) - 75.0).abs() < 1e-10);
    }

    #[test]
    fn test_invalid_trade_parameters() {
        let mut position = create_test_position();
        let initial_state = position.clone();

        // 無効なパラメータは無視される（エラーを投げない）
        position.add_buy_trade(0.0, 100.0);
        assert_eq!(position.total_buy_qty, initial_state.total_buy_qty);

        position.add_buy_trade(10.0, 0.0);
        assert_eq!(position.total_buy_qty, initial_state.total_buy_qty);

        position.add_buy_trade(-5.0, 100.0);
        assert_eq!(position.total_buy_qty, initial_state.total_buy_qty);

        position.add_sell_trade(0.0, 100.0);
        assert_eq!(position.total_sell_qty, initial_state.total_sell_qty);

        position.add_sell_trade(10.0, -100.0);
        assert_eq!(position.total_sell_qty, initial_state.total_sell_qty);
    }

    #[test]
    fn test_short_position_unrealized_pnl() {
        let mut position = create_test_position();
        position.add_sell_trade(10.0, 100.0); // ショートポジション

        // 現在価格が90に下がった場合、利益 = 10 * (100 - 90) = 100
        assert!((position.get_unrealized_pnl(90.0) - 100.0).abs() < 1e-10);

        // 現在価格が110に上がった場合、損失 = 10 * (100 - 110) = -100
        assert!((position.get_unrealized_pnl(110.0) - (-100.0)).abs() < 1e-10);
    }

    #[test]
    fn test_complex_trading_scenario() {
        let mut position = create_test_position();
        // 複数回の売買を行うシナリオ
        position.add_buy_trade(10.0, 100.0);   // 10枚@100で買い, 平均100.0
        position.add_sell_trade(3.0, 110.0);   // 3枚@110で売り（実現損益: 3 * (110 - 100) = 30）

        // この時点で: 買い10枚, 売り3枚, ネット7枚, 実現損益30
        assert!((position.net_qty - 7.0).abs() < 1e-10);
        assert!((position.realized_pnl - 30.0).abs() < 1e-10);

        position.add_buy_trade(5.0, 105.0);    // 5枚@105で買い
        // 平均買値 = (10*100 + 5*105) / 15 = 1525/15 = 101.67
        let new_average_buy_price = (10.0 * 100.0 + 5.0 * 105.0) / 15.0;

        position.add_sell_trade(2.0, 115.0);   // 2枚@115で売り
        // 実現損益 += 2 * (115 - 101.67) = 2 * 13.33 = 26.67
        // 総実現損益 = 30 + 26.67 = 56.67

        assert_eq!(position.total_buy_qty, 15.0);
        assert_eq!(position.total_sell_qty, 5.0);
        assert_eq!(position.net_qty, 10.0);

        // 実現損益の確認
        let expected_realized_pnl = 30.0 + 2.0 * (115.0 - new_average_buy_price);
        assert!((position.realized_pnl - expected_realized_pnl).abs() < 0.01);
    }

    #[test]
    fn test_position_reset_after_flat() {
        let mut position = create_test_position();
        // ポジションがフラットになった後、平均価格がリセットされることを確認

        // 1. 100円で1BTC買う
        position.add_buy_trade(1.0, 100.0);
        assert_eq!(position.net_qty, 1.0);
        assert_eq!(position.average_buy_price, 100.0);
        assert_eq!(position.realized_pnl, 0.0);

        // 2. 110円で1BTC売る（ポジションフラット）
        position.add_sell_trade(1.0, 110.0);
        assert_eq!(position.net_qty, 0.0);
        assert!((position.realized_pnl - 10.0).abs() < 1e-10); // 1 * (110 - 100)

        // フラット後は累積値がリセットされる
        assert_eq!(position.total_buy_qty, 0.0);
        assert_eq!(position.total_sell_qty, 0.0);
        assert_eq!(position.average_buy_price, 0.0);
        assert_eq!(position.average_sell_price, 0.0);

        // 3. 120円で1BTC買う（新しいポジション開始）
        position.add_buy_trade(1.0, 120.0);
        assert_eq!(position.net_qty, 1.0);
        assert_eq!(position.average_buy_price, 120.0); // 過去の100円は影響しない
        assert_eq!(position.total_buy_qty, 1.0);

        // 4. 130円で1BTC売る
        position.add_sell_trade(1.0, 130.0);
        assert_eq!(position.net_qty, 0.0);
        assert!((position.realized_pnl - 20.0).abs() < 1e-10); // 10 + 1 * (130 - 120)
    }

    #[test]
    fn test_long_to_short_reversal() {
        let mut position = create_test_position();
        // ロングからショートへの反転時に買いの累積値がリセットされることを確認

        // 1. 100円で2BTC買う
        position.add_buy_trade(2.0, 100.0);
        assert_eq!(position.net_qty, 2.0);
        assert_eq!(position.average_buy_price, 100.0);

        // 2. 110円で3BTC売る（ロング→ショートに反転）
        position.add_sell_trade(3.0, 110.0);
        assert_eq!(position.net_qty, -1.0); // ショートポジション
        assert!((position.realized_pnl - 20.0).abs() < 1e-10); // 2 * (110 - 100)

        // 反転後、買いの累積値はリセットされる
        assert_eq!(position.total_buy_qty, 0.0);
        assert_eq!(position.total_buy_amount, 0.0);
        assert_eq!(position.average_buy_price, 0.0);

        // 売りの累積値は新しいショートポジションのサイズに調整される
        assert_eq!(position.total_sell_qty, 1.0); // 反転後のnetQtyに合わせる
        assert_eq!(position.average_sell_price, 110.0); // 反転を引き起こしたトレードの価格

        // 3. 105円で1BTC買い戻す（ショートポジション決済）
        position.add_buy_trade(1.0, 105.0);
        assert_eq!(position.net_qty, 0.0);
        assert!((position.realized_pnl - 25.0).abs() < 1e-10); // 20 + 1 * (110 - 105)
    }

    #[test]
    fn test_short_to_long_reversal() {
        let mut position = create_test_position();
        // ショートからロングへの反転時に売りの累積値がリセットされることを確認

        // 1. 100円で2BTC売る（ショートポジション）
        position.add_sell_trade(2.0, 100.0);
        assert_eq!(position.net_qty, -2.0);
        assert_eq!(position.average_sell_price, 100.0);

        // 2. 90円で3BTC買う（ショート→ロングに反転）
        position.add_buy_trade(3.0, 90.0);
        assert_eq!(position.net_qty, 1.0); // ロングポジション
        assert!((position.realized_pnl - 20.0).abs() < 1e-10); // 2 * (100 - 90)

        // 反転後、売りの累積値はリセットされる
        assert_eq!(position.total_sell_qty, 0.0);
        assert_eq!(position.total_sell_amount, 0.0);
        assert_eq!(position.average_sell_price, 0.0);

        // 買いの累積値は新しいロングポジションのサイズに調整される
        assert_eq!(position.total_buy_qty, 1.0); // 反転後のnetQtyに合わせる
        assert_eq!(position.average_buy_price, 90.0); // 反転を引き起こしたトレードの価格

        // 3. 95円で1BTC売る（ロングポジション決済）
        position.add_sell_trade(1.0, 95.0);
        assert_eq!(position.net_qty, 0.0);
        assert!((position.realized_pnl - 25.0).abs() < 1e-10); // 20 + 1 * (95 - 90)
    }
}

