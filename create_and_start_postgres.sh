#!/bin/bash
# PostgreSQLコンテナを作成して起動するスクリプト

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "PostgreSQLコンテナ作成・起動スクリプト"
echo "=========================================="
echo ""

# 既存のコンテナを確認
if docker ps -a | grep -q "$CONTAINER_NAME"; then
    echo "既存のコンテナが見つかりました:"
    docker ps -a | grep "$CONTAINER_NAME"
    echo ""
    read -p "既存のコンテナを削除して新規作成しますか？ (y/n): " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "既存のコンテナを削除します..."
        docker stop $CONTAINER_NAME 2>/dev/null
        docker rm $CONTAINER_NAME 2>/dev/null
        echo "削除完了"
    else
        echo "既存のコンテナを起動します..."
        docker start $CONTAINER_NAME
        echo "起動を待機します..."
        sleep 5
        if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
            echo "✅ PostgreSQLが起動しました！"
            echo ""
            echo "接続コマンド:"
            echo "  docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
            exit 0
        else
            echo "❌ 起動に失敗しました。"
            exit 1
        fi
    fi
fi

# ボリュームの確認
if ! docker volume ls | grep -q "$VOLUME_NAME"; then
    echo "ボリュームが見つかりません。作成します..."
    docker volume create $VOLUME_NAME
fi

# ディスク容量を確認
echo "ディスク容量を確認します..."
DISK_USAGE=$(docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1 | awk '{print \$5}' | sed 's/%//'")
echo "ディスク使用率: ${DISK_USAGE}%"
echo ""

if [ "$DISK_USAGE" -ge 95 ]; then
    echo "⚠️  警告: ディスク使用率が${DISK_USAGE}%です。"
    echo "   容量が不足している可能性があります。"
    echo ""
    read -p "続行しますか？ (y/n): " -r
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "キャンセルしました。"
        echo "容量を確保するには: ./delete_wal_and_data.sh"
        exit 1
    fi
fi

# 新しいコンテナを作成して起動
echo "PostgreSQLコンテナを作成して起動します..."
echo ""

docker run -d \
  --name $CONTAINER_NAME \
  -v $VOLUME_NAME:/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=$DB_PASSWORD \
  -e POSTGRES_DB=$DB_NAME \
  -p 5432:5432 \
  timescale/timescaledb:latest-pg14

if [ $? -ne 0 ]; then
    echo "❌ コンテナの作成に失敗しました。"
    exit 1
fi

echo "PostgreSQLの起動を待機します（最大60秒）..."
SUCCESS=false
for i in {1..60}; do
    if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo ""
        echo "✅ PostgreSQLが起動しました！"
        SUCCESS=true
        break
    fi
    if [ $((i % 10)) -eq 0 ]; then
        echo -n "[${i}秒}]"
    else
        echo -n "."
    fi
    sleep 1
done

if [ "$SUCCESS" = false ]; then
    echo ""
    echo "❌ PostgreSQLの起動に失敗しました。"
    echo ""
    echo "ログを確認します..."
    docker logs $CONTAINER_NAME | tail -30
    echo ""
    echo "コンテナを削除しますか？ (y/n)"
    read -r response
    if [ "$response" = "y" ]; then
        docker rm -f $CONTAINER_NAME
    fi
    exit 1
fi

echo ""
echo "=========================================="
echo "接続情報"
echo "=========================================="
echo "コンテナ名: $CONTAINER_NAME"
echo "データベース: $DB_NAME"
echo "ユーザー: $DB_USER"
echo "パスワード: $DB_PASSWORD"
echo "ポート: 5432 (localhost:5432)"
echo ""
echo "=========================================="
echo "接続コマンド"
echo "=========================================="
echo ""
echo "コンテナ内のpsqlで接続:"
echo "  docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
echo ""
echo "ローカルから接続（ポート5432が公開されている場合）:"
echo "  psql -h localhost -p 5432 -U $DB_USER -d $DB_NAME"
echo ""
echo "=========================================="









