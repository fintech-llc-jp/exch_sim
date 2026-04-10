#!/bin/bash
# market_board_snapshots と market_board_price_levels のデータのみを削除するスクリプト

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "板データ削除スクリプト"
echo "削除対象: market_board_snapshots, market_board_price_levels"
echo "=========================================="
echo ""

# 方法1: 既存のコンテナが起動している場合
if docker ps | grep -q "$CONTAINER_NAME"; then
    echo "既存のコンテナが起動しています。データを削除します..."
    
    docker exec -i $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME <<EOF
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
EOF
    
    echo ""
    echo "✅ 板データの削除が完了しました。"
    exit 0
fi

# 方法2: コンテナが停止している場合、起動を試みる
echo "コンテナが停止しています。起動を試みます..."
if docker start $CONTAINER_NAME 2>/dev/null; then
    echo "コンテナを起動しました。5秒待機します..."
    sleep 5
    
    # 接続確認
    if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo "PostgreSQLに接続できました。データを削除します..."
        
        docker exec -i $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME <<EOF
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
EOF
        
        echo ""
        echo "✅ 板データの削除が完了しました。"
        echo "コンテナを停止しますか？ (y/n)"
        read -r response
        if [ "$response" = "y" ]; then
            docker stop $CONTAINER_NAME
            echo "コンテナを停止しました。"
        fi
        exit 0
    else
        echo "PostgreSQLに接続できませんでした。"
        docker stop $CONTAINER_NAME 2>/dev/null
    fi
else
    echo "コンテナを起動できませんでした（容量オーバーの可能性）。"
fi

# 方法3: 最小限の設定で一時コンテナを起動
echo ""
echo "最小限の設定で一時コンテナを起動して削除を試みます..."

# 既存の一時コンテナを削除
if docker ps -a | grep -q "${CONTAINER_NAME}_temp"; then
    echo "既存の一時コンテナを削除します..."
    docker rm -f ${CONTAINER_NAME}_temp 2>/dev/null
fi

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
EOF
    
    echo ""
    echo "✅ 板データの削除が完了しました。"
    echo "一時コンテナを停止します..."
    docker stop ${CONTAINER_NAME}_temp
    docker rm ${CONTAINER_NAME}_temp
else
    echo ""
    echo "❌ エラー: PostgreSQLに接続できませんでした。"
    echo "一時コンテナを削除します..."
    docker stop ${CONTAINER_NAME}_temp 2>/dev/null
    docker rm ${CONTAINER_NAME}_temp 2>/dev/null
    exit 1
fi









