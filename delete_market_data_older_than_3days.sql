-- DBのマーケットデータ：3日より前のデータを削除するSQL
--
-- 対象テーブル:
--   market_board_snapshots  … 板スナップショット（timestamp で日時）
--   market_board_price_levels … 価格帯ごとの数量（snapshot_id でスナップショットに紐づく）
--
-- 実行例:
--   psql -h localhost -p 15432 -U exch_sim_user -d exch_sim -f delete_market_data_older_than_3days.sql
--   PGPASSWORD=xxx psql -h HOST -p PORT -U USER -d exch_sim -f delete_market_data_older_than_3days.sql

-- 1. 子テーブル: 3日より前のスナップショットに紐づく price_levels を削除
DELETE FROM market_board_price_levels
WHERE snapshot_id IN (
  SELECT id FROM market_board_snapshots
  WHERE timestamp < (CURRENT_TIMESTAMP - INTERVAL '3 days')
);

-- 2. 親テーブル: 3日より前のスナップショットを削除
DELETE FROM market_board_snapshots
WHERE timestamp < (CURRENT_TIMESTAMP - INTERVAL '3 days');
