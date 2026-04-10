#!/bin/bash
# WALログを削除してから板データを削除する統合スクリプト

VOLUME_NAME="algo_trader_v1_postgres_data"
CONTAINER_NAME="algo_trader_postgres"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "WALログ削除 → 板データ削除スクリプト"
echo "=========================================="
echo ""

# 現在のディスク使用状況
echo "📊 現在のディスク使用状況:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1"
echo ""

# WALログのサイズ
WAL_SIZE=$(docker run --rm -v $VOLUME_NAME:/data alpine du -sh /data/pg_wal 2>/dev/null | awk '{print $1}')
WAL_COUNT=$(docker run --rm -v $VOLUME_NAME:/data alpine sh -c "ls -1 /data/pg_wal 2>/dev/null | wc -l" | tr -d ' ')
echo "📊 WALログ: $WAL_SIZE ($WAL_COUNT ファイル)"
echo ""

# 確認
echo "⚠️  ディスク容量が100%のため、PostgreSQLを起動できません。"
echo "   以下の操作を実行します："
echo "   1. WALログを削除（最新の5ファイルは保持）"
echo "   2. PostgreSQLを起動"
echo "   3. 板データ（market_board_snapshots, market_board_price_levels）を削除"
echo ""
read -p "実行しますか？ (y/n): " -r
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "キャンセルしました。"
    exit 1
fi

# WALログを削除
echo ""
echo "1. WALログを削除します..."
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  cd /data/pg_wal
  # 最新の5ファイル以外を削除
  TOTAL=\$(ls -1 | wc -l)
  if [ \$TOTAL -gt 5 ]; then
    ls -t | tail -n +\$((TOTAL - 4)) | xargs rm -f 2>/dev/null
    echo \"   WALログを削除しました（最新5ファイルを保持）\"
  else
    echo \"   WALログファイルが少ないため、削除をスキップします\"
  fi
  echo ''
  echo '   残りのWALファイル数:'
  ls -1 | wc -l | xargs echo '   '
  echo ''
  echo '   WALディレクトリのサイズ:'
  du -sh /data/pg_wal | awk '{print \"   \" \$1}'
"

echo ""
echo "📊 削除後のディスク使用状況:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1"
echo ""

# 既存の一時コンテナを削除
if docker ps -a | grep -q "${CONTAINER_NAME}_temp"; then
    echo "既存の一時コンテナを削除します..."
    docker rm -f ${CONTAINER_NAME}_temp 2>/dev/null
fi

# PostgreSQLを起動
echo ""
echo "2. PostgreSQLを起動します（軽量設定）..."
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
  -c min_wal_size=256MB \
  -c max_wal_size=1GB \
  -c max_connections=10 \
  -c autovacuum=off \
  -c checkpoint_timeout=30min

echo "PostgreSQLの起動を待機します（最大120秒）..."
SUCCESS=false
for i in {1..120}; do
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
    echo "❌ PostgreSQLの起動に失敗しました。"
    echo "ログを確認します..."
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
echo "3. 板データを削除します..."
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
echo "📊 最終的なディスク使用状況:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1"
echo ""
echo "完了しました。"









