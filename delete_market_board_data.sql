-- market_board_snapshots と market_board_price_levels のデータのみを削除するSQL
-- 使い方: docker exec -i <container_name> psql -U postgres -d exch_sim < delete_market_board_data.sql

-- 削除前のレコード数を確認
SELECT 'market_board_snapshots' AS table_name, COUNT(*) AS record_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels' AS table_name, COUNT(*) AS record_count FROM market_board_price_levels;

-- 削除前のテーブルサイズを確認
SELECT 
    'market_board_snapshots' AS table_name,
    pg_size_pretty(pg_total_relation_size('public.market_board_snapshots')) AS total_size,
    pg_size_pretty(pg_relation_size('public.market_board_snapshots')) AS table_size
UNION ALL
SELECT 
    'market_board_price_levels' AS table_name,
    pg_size_pretty(pg_total_relation_size('public.market_board_price_levels')) AS total_size,
    pg_size_pretty(pg_relation_size('public.market_board_price_levels')) AS table_size;

-- データを削除
TRUNCATE TABLE market_board_price_levels CASCADE;
TRUNCATE TABLE market_board_snapshots CASCADE;

-- 削除後のレコード数を確認
SELECT 'market_board_snapshots' AS table_name, COUNT(*) AS record_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels' AS table_name, COUNT(*) AS record_count FROM market_board_price_levels;

-- VACUUM FULLで容量を解放
VACUUM FULL market_board_snapshots;
VACUUM FULL market_board_price_levels;

-- 削除後のテーブルサイズを確認
SELECT 
    'market_board_snapshots' AS table_name,
    pg_size_pretty(pg_total_relation_size('public.market_board_snapshots')) AS total_size,
    pg_size_pretty(pg_relation_size('public.market_board_snapshots')) AS table_size
UNION ALL
SELECT 
    'market_board_price_levels' AS table_name,
    pg_size_pretty(pg_total_relation_size('public.market_board_price_levels')) AS total_size,
    pg_size_pretty(pg_relation_size('public.market_board_price_levels')) AS table_size;









