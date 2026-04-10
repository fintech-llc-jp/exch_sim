-- 強制的にTRUNCATEを実行するSQL（ロックを解除してから実行）

-- 1. アクティブなトランザクションを確認してから、必要に応じて強制終了
-- 注意: アプリケーションの接続を切る可能性があります

-- まず、market_board_snapshotsにロックをかけているプロセスを確認
SELECT pid, usename, application_name, state, query
FROM pg_stat_activity
WHERE datname = 'exch_sim'
  AND state != 'idle'
  AND (query LIKE '%market_board_snapshots%' OR query LIKE '%market_board_price_levels%');

-- 2. ロックを解除してからTRUNCATE（必要に応じて実行）
-- 注意: 以下のコマンドはアプリケーションの接続を切る可能性があります
-- まずは上記のSELECTで確認してから実行してください

-- アプリケーションの接続を切る場合（PostgreSQLWriterなど）
-- SELECT pg_terminate_backend(pid) FROM pg_stat_activity 
-- WHERE datname = 'exch_sim' AND application_name LIKE '%PostgreSQLWriter%';

-- 3. TRUNCATEを実行（CASCADEで外部キー制約も無視）
TRUNCATE TABLE market_board_price_levels CASCADE;
TRUNCATE TABLE market_board_snapshots CASCADE;

-- 4. 確認
SELECT 'market_board_snapshots' AS table_name, COUNT(*) AS row_count FROM market_board_snapshots
UNION ALL
SELECT 'market_board_price_levels' AS table_name, COUNT(*) AS row_count FROM market_board_price_levels;
















