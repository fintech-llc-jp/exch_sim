-- yukio004のポジションを初期化するSQLスクリプト
-- 使い方: psql -U postgres -d exch_sim -f reset_position_yukio004.sql

BEGIN;

-- 0. 現在のポジションを確認（削除前）
SELECT 'Current positions before deletion:' AS info;
SELECT id, username, symbol, unit, net_qty, total_buy_qty, total_sell_qty 
FROM positions 
WHERE username = 'yukio004'
ORDER BY symbol;

-- 1. 既存のポジションをすべて削除
DELETE FROM positions WHERE username = 'yukio004';

-- 1.5. 削除後の確認
SELECT 'Positions after deletion (should be empty):' AS info;
SELECT COUNT(*) AS remaining_positions 
FROM positions 
WHERE username = 'yukio004';

-- 2. 初期現金残高を設定（100万円）
-- 注意: total_buy_qty, total_sell_qty, net_qtyはLong型で、データベースでは1000倍して保存される可能性があります
-- 既存のreset_user.sqlに合わせて1000000を使用します
INSERT INTO positions (
    id, 
    username, 
    symbol, 
    unit,
    total_buy_qty, 
    total_buy_amount, 
    total_sell_qty, 
    total_sell_amount, 
    net_qty, 
    average_buy_price, 
    average_sell_price, 
    realized_pnl, 
    last_updated
)
VALUES (
    'yukio004_JPY',
    'yukio004',
    'JPY',
    'JPY',           -- unitカラム（必須）
    1000000,         -- total_buy_qty (100万円)
    1000000.0,       -- total_buy_amount (100万円)
    0,               -- total_sell_qty
    0.0,             -- total_sell_amount
    1000000,         -- net_qty
    1.0,             -- average_buy_price
    0.0,             -- average_sell_price
    0.0,             -- realized_pnl
    NOW()            -- last_updated
);

-- 3. 最終確認
SELECT 'Final positions after initialization:' AS info;
SELECT id, username, symbol, unit, net_qty, total_buy_qty, total_sell_qty 
FROM positions 
WHERE username = 'yukio004'
ORDER BY symbol;

COMMIT;

-- 完了メッセージ
SELECT 'Position for yukio004 has been initialized successfully' AS message;

