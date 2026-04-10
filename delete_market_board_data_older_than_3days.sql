-- 3日以上前のマーケットボードデータのみを削除するSQL
-- market_board_snapshots の timestamp が 3日より古いレコードと、
-- それに紐づく market_board_price_levels を削除します。
--
-- 使い方:
--   psql -U postgres -d exch_sim -f delete_market_board_data_older_than_3days.sql
--   （または PGPASSWORD=xxx psql -h localhost -p 15432 -U exch_sim_user -d exch_sim -f delete_market_board_data_older_than_3days.sql）

-- 削除対象の件数確認（3日以上前のスナップショット）
SELECT '削除対象（3日以上前）' AS info, COUNT(*) AS snapshot_count
FROM market_board_snapshots
WHERE timestamp < (CURRENT_TIMESTAMP - INTERVAL '3 days');

-- 削除前の全レコード数
SELECT 'market_board_snapshots（削除前）' AS table_name, COUNT(*) AS record_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels（削除前）' AS table_name, COUNT(*) AS record_count FROM market_board_price_levels;

-- 1. 子テーブル: 3日以上前のスナップショットに紐づく price_levels を削除
DELETE FROM market_board_price_levels
WHERE snapshot_id IN (
  SELECT id FROM market_board_snapshots
  WHERE timestamp < (CURRENT_TIMESTAMP - INTERVAL '3 days')
);

-- 2. 親テーブル: 3日以上前のスナップショットを削除
DELETE FROM market_board_snapshots
WHERE timestamp < (CURRENT_TIMESTAMP - INTERVAL '3 days');

-- 削除後のレコード数
SELECT 'market_board_snapshots（削除後）' AS table_name, COUNT(*) AS record_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels（削除後）' AS table_name, COUNT(*) AS record_count FROM market_board_price_levels;

-- テーブルサイズを小さくする（任意・時間がかかる場合あり）
VACUUM ANALYZE market_board_price_levels;
VACUUM ANALYZE market_board_snapshots;
