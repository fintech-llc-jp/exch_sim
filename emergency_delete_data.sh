#!/bin/bash
# 緊急時：PostgreSQLコンテナが起動できない場合のデータ削除スクリプト
# 最小限のメモリでPostgreSQLを起動してデータを削除

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "緊急データ削除スクリプト"
echo "=========================================="

# 既存のコンテナを停止・削除（念のため）
echo "既存のコンテナを停止します..."
docker stop $CONTAINER_NAME 2>/dev/null
docker rm $CONTAINER_NAME 2>/dev/null

# 最小限の設定でPostgreSQLを起動
echo "最小限の設定でPostgreSQLを起動します..."
docker run -d \
  --name ${CONTAINER_NAME}_temp \
  -v $VOLUME_NAME:/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=$DB_PASSWORD \
  -e POSTGRES_DB=$DB_NAME \
  --memory="512m" \
  --memory-swap="1g" \
  --shm-size="256m" \
  timescale/timescaledb:latest-pg14 \
  postgres \
  -c shared_buffers=128MB \
  -c effective_cache_size=256MB \
  -c maintenance_work_mem=64MB \
  -c checkpoint_completion_target=0.9 \
  -c wal_buffers=16MB \
  -c default_statistics_target=100 \
  -c random_page_cost=1.1 \
  -c effective_io_concurrency=200 \
  -c work_mem=4MB \
  -c min_wal_size=1GB \
  -c max_wal_size=4GB

echo "PostgreSQLの起動を待機します（最大60秒）..."
for i in {1..60}; do
    if docker exec ${CONTAINER_NAME}_temp pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo "PostgreSQLが起動しました！"
        break
    fi
    echo -n "."
    sleep 1
done

# データ削除を実行
if docker exec ${CONTAINER_NAME}_temp pg_isready -U $DB_USER >/dev/null 2>&1; then
    echo ""
    echo "データを削除します..."
    
    docker exec -i ${CONTAINER_NAME}_temp psql -U $DB_USER -d $DB_NAME <<EOF
-- 板データを削除（容量を多く使う可能性が高い）
TRUNCATE TABLE market_board_price_levels CASCADE;
TRUNCATE TABLE market_board_snapshots CASCADE;

-- 約定履歴を削除
TRUNCATE TABLE executions CASCADE;

-- 取引履歴を削除
TRUNCATE TABLE trade_history CASCADE;

-- ポジション情報を削除
TRUNCATE TABLE positions CASCADE;

-- VACUUM FULLで容量を解放
VACUUM FULL;

-- テーブルサイズの確認
SELECT 
    tablename,
    pg_size_pretty(pg_total_relation_size('public.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.'||tablename) DESC;

-- データベース全体のサイズ確認
SELECT pg_size_pretty(pg_database_size('$DB_NAME')) AS database_size;
EOF
    
    echo ""
    echo "データ削除が完了しました。"
    echo "一時コンテナを停止します..."
    docker stop ${CONTAINER_NAME}_temp
    docker rm ${CONTAINER_NAME}_temp
    
    echo ""
    echo "元のコンテナ名で再起動する場合は:"
    echo "  docker start $CONTAINER_NAME"
    echo "  または docker-compose up -d"
else
    echo ""
    echo "エラー: PostgreSQLに接続できませんでした。"
    echo "一時コンテナを削除します..."
    docker stop ${CONTAINER_NAME}_temp 2>/dev/null
    docker rm ${CONTAINER_NAME}_temp 2>/dev/null
    
    echo ""
    echo "代替方法: ボリュームを直接操作する必要があるかもしれません。"
    echo "ボリュームの場所: /var/lib/docker/volumes/$VOLUME_NAME/_data"
fi









