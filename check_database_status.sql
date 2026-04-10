-- データベースの状態を確認するSQL

-- 1. テーブルの件数を確認
SELECT 'market_board_snapshots' AS table_name, COUNT(*) AS row_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels' AS table_name, COUNT(*) AS row_count FROM market_board_price_levels;

-- 2. アクティブな接続を確認
SELECT pid, usename, application_name, state, query_start, state_change, wait_event_type, wait_event
FROM pg_stat_activity
WHERE datname = 'exch_sim'
ORDER BY query_start;

-- 3. ロック状態を確認
SELECT 
    l.locktype,
    l.database,
    l.relation::regclass,
    l.pid,
    l.mode,
    l.granted,
    a.usename,
    a.query,
    a.query_start
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE l.relation::regclass::text IN ('market_board_snapshots', 'market_board_price_levels')
ORDER BY l.granted, l.pid;

-- 4. テーブルサイズを確認
SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
    pg_total_relation_size(schemaname||'.'||tablename) AS size_bytes
FROM pg_tables
WHERE tablename IN ('market_board_snapshots', 'market_board_price_levels')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
















