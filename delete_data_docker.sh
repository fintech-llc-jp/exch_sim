#!/bin/bash
# PostgreSQL Dockerコンテナのデータを削除するスクリプト
# 容量オーバーでコンテナが起動できない場合の緊急用

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "PostgreSQLデータ削除スクリプト"
echo "=========================================="

# 方法1: コンテナが起動できる場合（推奨）
echo "方法1: コンテナを起動してSQLで削除を試みます..."

# コンテナを起動（一時的に）
if docker start $CONTAINER_NAME 2>/dev/null; then
    echo "コンテナを起動しました。5秒待機します..."
    sleep 5
    
    # 接続確認
    if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo "PostgreSQLに接続できました。データを削除します..."
        
        # データ削除SQLを実行
        docker exec -i $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME <<EOF
-- 板データを削除（容量を多く使う可能性が高い）
TRUNCATE TABLE market_board_price_levels CASCADE;
TRUNCATE TABLE market_board_snapshots CASCADE;

-- 約定履歴を削除
TRUNCATE TABLE executions CASCADE;

-- 取引履歴を削除
TRUNCATE TABLE trade_history CASCADE;

-- ポジション情報を削除
TRUNCATE TABLE positions CASCADE;

-- テーブルサイズの確認
SELECT 
    tablename,
    pg_size_pretty(pg_total_relation_size('public.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.'||tablename) DESC;
EOF
        
        echo "データ削除が完了しました。"
        echo "コンテナを停止します..."
        docker stop $CONTAINER_NAME
        exit 0
    else
        echo "PostgreSQLに接続できませんでした。"
        docker stop $CONTAINER_NAME 2>/dev/null
    fi
else
    echo "コンテナを起動できませんでした（容量オーバーの可能性）。"
fi

# 方法2: 単一ユーザーモードで起動（容量オーバーでも動作する可能性がある）
echo ""
echo "方法2: 単一ユーザーモードで起動を試みます..."
echo "注意: この方法はPostgreSQLのデータディレクトリに直接アクセスします"

# ボリュームのマウントポイントを取得
MOUNTPOINT=$(docker volume inspect $VOLUME_NAME --format '{{ .Mountpoint }}' 2>/dev/null)

if [ -n "$MOUNTPOINT" ]; then
    echo "ボリュームのマウントポイント: $MOUNTPOINT"
    echo "ボリュームサイズを確認します..."
    docker run --rm -v $VOLUME_NAME:/data alpine du -sh /data 2>/dev/null || echo "サイズ確認に失敗しました"
else
    echo "ボリュームのマウントポイントを取得できませんでした。"
fi

echo ""
echo "=========================================="
echo "手動での削除方法:"
echo "=========================================="
echo "1. コンテナを一時的に起動:"
echo "   docker start $CONTAINER_NAME"
echo ""
echo "2. 接続してデータを削除:"
echo "   docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
echo "   その後、以下のSQLを実行:"
echo "   TRUNCATE TABLE market_board_snapshots CASCADE;"
echo "   TRUNCATE TABLE market_board_price_levels CASCADE;"
echo "   TRUNCATE TABLE executions CASCADE;"
echo "   TRUNCATE TABLE trade_history CASCADE;"
echo "   TRUNCATE TABLE positions CASCADE;"
echo ""
echo "3. または、delete_all_data.sqlを使用:"
echo "   docker exec -i $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME < delete_all_data.sql"
echo ""
echo "=========================================="









