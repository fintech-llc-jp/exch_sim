#!/bin/bash
# VPS のマーケットデータ・約定データの直近1時間の状況確認

SSH_HOST="vps"
DB="exch_sim"

echo "=========================================="
echo "VPS データ状況確認: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
echo "=========================================="

ssh "$SSH_HOST" "sudo -u postgres psql -d $DB" <<'SQL'

\echo ''
\echo '=== 板スナップショット（直近1時間） ==='
\echo '※ スナップショットはインメモリ板をタイマーで保存するため、'
\echo '  WebSocket切断後も古いデータが書き込まれ続けます。'
\echo '  板の鮮度は約定データの有無で判断してください。'
SELECT
    symbol,
    COUNT(*)                            AS count,
    MIN(timestamp)                      AS oldest,
    MAX(timestamp)                      AS latest,
    ROUND(COUNT(*) / 60.0, 1)          AS per_min,
    CASE
        WHEN MAX(timestamp) > NOW() - INTERVAL '1 minute' THEN '✅ 正常'
        WHEN MAX(timestamp) > NOW() - INTERVAL '5 minutes' THEN '⚠️  遅延'
        ELSE '❌ 停止疑い'
    END                                 AS status
FROM market_board_snapshots
WHERE timestamp > NOW() - INTERVAL '1 hour'
GROUP BY symbol
ORDER BY symbol;

\echo ''
\echo '=== 約定データ（直近1時間）— シンボル別（欠落検出付き） ==='
\echo '期待シンボル: B_BTCJPY, B_FX_BTCJPY, G_BTCJPY, G_FX_BTCJPY'
WITH expected_symbols(symbol) AS (
    VALUES ('B_BTCJPY'), ('B_FX_BTCJPY'), ('G_BTCJPY'), ('G_FX_BTCJPY')
),
recent_exec AS (
    SELECT
        symbol,
        COUNT(*)        AS count,
        MAX(created_at) AS latest
    FROM executions
    WHERE username = 'EXTERNAL_FEED'
      AND created_at > NOW() - INTERVAL '1 hour'
    GROUP BY symbol
)
SELECT
    e.symbol,
    COALESCE(r.count, 0)    AS count,
    r.latest,
    CASE
        WHEN r.latest IS NULL                                   THEN '❌ データなし（WebSocket切断疑い）'
        WHEN r.latest > NOW() - INTERVAL '5 minutes'           THEN '✅ 正常'
        WHEN r.latest > NOW() - INTERVAL '15 minutes'          THEN '⚠️  遅延'
        ELSE '❌ 停止疑い'
    END                     AS status
FROM expected_symbols e
LEFT JOIN recent_exec r USING (symbol)
ORDER BY e.symbol;

\echo ''
\echo '=== 約定データ（直近1時間）— シンボル×サイド詳細 ==='
SELECT
    symbol,
    side,
    COUNT(*)                            AS count,
    MIN(created_at)                     AS oldest,
    MAX(created_at)                     AS latest,
    CASE
        WHEN MAX(created_at) > NOW() - INTERVAL '5 minutes' THEN '✅ 正常'
        WHEN MAX(created_at) > NOW() - INTERVAL '15 minutes' THEN '⚠️  遅延'
        ELSE '❌ 停止疑い'
    END                                 AS status
FROM executions
WHERE username = 'EXTERNAL_FEED'
  AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY symbol, side
ORDER BY symbol, side;

\echo ''
\echo '=== テーブルサイズ ==='
SELECT
    relname                                                         AS table_name,
    pg_size_pretty(pg_total_relation_size('public.' || relname))   AS total_size,
    n_live_tup                                                      AS live_rows
FROM pg_stat_user_tables
WHERE relname IN ('market_board_snapshots', 'market_board_price_levels', 'executions')
ORDER BY pg_total_relation_size('public.' || relname) DESC;

SQL
