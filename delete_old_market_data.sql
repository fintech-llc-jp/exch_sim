-- マーケットデータを全削除するSQLスクリプト（TRUNCATE使用）
-- market_board_price_levels と market_board_snapshots を全削除します
-- 使い方: psql -U postgres -d exch_sim -f delete_old_market_data.sql

-- 1. 板データの価格レベルを全削除
TRUNCATE TABLE market_board_price_levels;

-- 2. 板データのスナップショットを全削除
TRUNCATE TABLE market_board_snapshots;

