#!/bin/bash
# 直接SQLを実行して板データを削除するスクリプト（より軽量な方法）

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "板データ削除スクリプト（直接実行版）"
echo "削除対象: market_board_snapshots, market_board_price_levels"
echo "=========================================="
echo ""

# 既存の一時コンテナを削除
if docker ps -a | grep -q "${CONTAINER_NAME}_temp"; then
    echo "既存の一時コンテナを削除します..."
    docker rm -f ${CONTAINER_NAME}_temp 2>/dev/null
fi

# より軽量な設定でPostgreSQLを起動
echo "軽量設定でPostgreSQLを起動します..."
docker run -d \
  --name ${CONTAINER_NAME}_temp \
  -v $VOLUME_NAME:/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=$DB_PASSWORD \
  -e POSTGRES_DB=$DB_NAME \
  --memory="256m" \
  --memory-swap="512m" \
  --shm-size="128m" \
  timescale/timescaledb:latest-pg14 \
  postgres \
  -c shared_buffers=64MB \
  -c effective_cache_size=128MB \
  -c maintenance_work_mem=32MB \
  -c checkpoint_completion_target=0.9 \
  -c wal_buffers=8MB \
  -c default_statistics_target=100 \
  -c random_page_cost=1.1 \
  -c effective_io_concurrency=200 \
  -c work_mem=2MB \
  -c min_wal_size=512MB \
  -c max_wal_size=2GB \
  -c max_connections=10 \
  -c autovacuum=off

echo "PostgreSQLの起動を待機します（最大90秒）..."
SUCCESS=false
for i in {1..90}; do
    if docker exec ${CONTAINER_NAME}_temp pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo ""
        echo "✅ PostgreSQLが起動しました！"
        SUCCESS=true
        break
    fi
    if [ $((i % 10)) -eq 0 ]; then
        echo -n "[${i}秒]"
    else
        echo -n "."
    fi
    sleep 1
done

# ログを確認
if [ "$SUCCESS" = false ]; then
    echo ""
    echo ""
    echo "❌ PostgreSQLの起動に失敗しました。ログを確認します..."
    echo "=========================================="
    docker logs ${CONTAINER_NAME}_temp 2>&1 | tail -30
    echo "=========================================="
    echo ""
    echo "一時コンテナを削除します..."
    docker rm -f ${CONTAINER_NAME}_temp 2>/dev/null
    exit 1
fi

# データ削除を実行
echo ""
echo "板データを削除します..."
docker exec -i ${CONTAINER_NAME}_temp psql -U $DB_USER -d $DB_NAME <<EOF
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

-- VACUUM FULLで容量を解放（時間がかかる場合があります）
echo "VACUUM FULLを実行中..."
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
EOF

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 板データの削除が完了しました。"
else
    echo ""
    echo "❌ データ削除中にエラーが発生しました。"
fi

echo ""
echo "一時コンテナを削除します..."
docker stop ${CONTAINER_NAME}_temp 2>/dev/null
docker rm ${CONTAINER_NAME}_temp 2>/dev/null

echo ""
echo "完了しました。"









