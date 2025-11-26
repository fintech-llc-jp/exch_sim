#!/bin/bash
# yukio004アカウントを完全に初期化するシェルスクリプト

USERNAME="yukio004"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_HOST="localhost"
DB_PORT="5432"

# PostgreSQLに接続してSQLを実行
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME <<EOF
-- 1. 約定履歴を削除
DELETE FROM executions WHERE username = '$USERNAME';

-- 2. 取引履歴を削除
DELETE FROM trade_history WHERE username = '$USERNAME';

-- 3. ポジションを削除
DELETE FROM positions WHERE username = '$USERNAME';

-- 4. 初期現金残高を設定（100万円）
INSERT INTO positions (id, username, symbol, total_buy_qty, total_buy_amount, total_sell_qty, total_sell_amount, net_qty, average_buy_price, average_sell_price, realized_pnl, last_updated)
VALUES (
    '${USERNAME}_JPY',
    '$USERNAME',
    'JPY',
    1000000,
    1000000,
    0,
    0,
    1000000,
    1.0,
    0.0,
    0.0,
    NOW()
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

SELECT 'User $USERNAME has been reset successfully' AS message;
EOF

echo "Reset completed for user: $USERNAME"

