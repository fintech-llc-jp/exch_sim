-- 全データを削除するSQLスクリプト（容量オーバー時の緊急用）
-- 使い方: docker exec -i <container_name> psql -U postgres -d exch_sim < delete_all_data.sql
-- または: docker run --rm -v algo_trader_v1_postgres_data:/var/lib/postgresql/data -e POSTGRES_PASSWORD=postgres123 timescale/timescaledb:latest-pg14 psql -U postgres -d exch_sim -f /path/to/delete_all_data.sql

-- 注意: このスクリプトはすべてのデータを削除します
-- ユーザー情報は保持したい場合は、usersテーブルの削除をスキップしてください

-- 1. 板データ（容量を多く使う可能性が高い）
TRUNCATE TABLE market_board_price_levels CASCADE;
TRUNCATE TABLE market_board_snapshots CASCADE;

-- 2. 約定履歴
TRUNCATE TABLE executions CASCADE;

-- 3. 取引履歴
TRUNCATE TABLE trade_history CASCADE;

-- 4. ポジション情報
TRUNCATE TABLE positions CASCADE;

-- 5. ユーザー情報（保持したい場合はこの行をコメントアウト）
-- TRUNCATE TABLE users CASCADE;
-- TRUNCATE TABLE user_roles CASCADE;

-- 6. テーブルサイズの確認
SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- 7. データベース全体のサイズ確認
SELECT pg_size_pretty(pg_database_size('exch_sim')) AS database_size;









