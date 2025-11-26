-- yukio004アカウントを完全に初期化するSQLスクリプト
-- 使い方: psql -U postgres -d exch_sim -f reset_user.sql

-- 1. 約定履歴を削除
DELETE FROM executions WHERE username = 'yukio004';

-- 2. 取引履歴を削除
DELETE FROM trade_history WHERE username = 'yukio004';

-- 3. ポジションを削除
DELETE FROM positions WHERE username = 'yukio004';

-- 4. 初期現金残高を設定（100万円）
INSERT INTO positions (id, username, symbol, total_buy_qty, total_buy_amount, total_sell_qty, total_sell_amount, net_qty, average_buy_price, average_sell_price, realized_pnl, last_updated)
VALUES (
    'yukio004_JPY',
    'yukio004',
    'JPY',
    1000000,  -- total_buy_qty (100万円)
    1000000,  -- total_buy_amount (100万円)
    0,        -- total_sell_qty
    0,        -- total_sell_amount
    1000000,  -- net_qty
    1.0,      -- average_buy_price
    0.0,      -- average_sell_price
    0.0,      -- realized_pnl
    NOW()     -- last_updated
)
ON CONFLICT (id) DO UPDATE SET
    total_buy_qty = 1000000,
    total_buy_amount = 1000000,
    total_sell_qty = 0,
    total_sell_amount = 0,
    net_qty = 1000000,
    average_buy_price = 1.0,
    average_sell_price = 0.0,
    realized_pnl = 0.0,
    last_updated = NOW();

-- 完了メッセージ
SELECT 'User yukio004 has been reset successfully' AS message;

