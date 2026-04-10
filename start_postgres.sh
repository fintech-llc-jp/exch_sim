#!/bin/bash
# Docker DesktopからPostgreSQLを起動するスクリプト

CONTAINER_NAME="algo_trader_postgres"
VOLUME_NAME="algo_trader_v1_postgres_data"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"

echo "=========================================="
echo "PostgreSQL起動スクリプト"
echo "=========================================="
echo ""

# 既存のコンテナを確認
if docker ps -a | grep -q "$CONTAINER_NAME"; then
    echo "既存のコンテナが見つかりました。"
    echo ""
    echo "起動方法を選択してください:"
    echo "1. 既存のコンテナを起動"
    echo "2. 新しいコンテナを作成して起動"
    echo ""
    read -p "選択 (1 or 2): " choice
    
    if [ "$choice" = "1" ]; then
        echo ""
        echo "既存のコンテナを起動します..."
        docker start $CONTAINER_NAME
        
        echo "PostgreSQLの起動を待機します..."
        for i in {1..30}; do
            if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
                echo "✅ PostgreSQLが起動しました！"
                echo ""
                echo "接続情報:"
                echo "  コンテナ名: $CONTAINER_NAME"
                echo "  データベース: $DB_NAME"
                echo "  ユーザー: $DB_USER"
                echo "  パスワード: $DB_PASSWORD"
                echo ""
                echo "psqlで接続する場合:"
                echo "  docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
                exit 0
            fi
            echo -n "."
            sleep 1
        done
        echo ""
        echo "❌ PostgreSQLの起動に失敗しました。"
        exit 1
    fi
fi

# 新しいコンテナを作成
echo ""
echo "新しいコンテナを作成して起動します..."
echo ""

# 既存のコンテナを削除（オプション）
if docker ps -a | grep -q "$CONTAINER_NAME"; then
    read -p "既存のコンテナを削除しますか？ (y/n): " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker stop $CONTAINER_NAME 2>/dev/null
        docker rm $CONTAINER_NAME 2>/dev/null
        echo "既存のコンテナを削除しました。"
    fi
fi

# 新しいコンテナを作成して起動
docker run -d \
  --name $CONTAINER_NAME \
  -v $VOLUME_NAME:/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=$DB_PASSWORD \
  -e POSTGRES_DB=$DB_NAME \
  -p 5432:5432 \
  timescale/timescaledb:latest-pg14

echo "PostgreSQLの起動を待機します..."
for i in {1..60}; do
    if docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
        echo ""
        echo "✅ PostgreSQLが起動しました！"
        echo ""
        echo "接続情報:"
        echo "  コンテナ名: $CONTAINER_NAME"
        echo "  データベース: $DB_NAME"
        echo "  ユーザー: $DB_USER"
        echo "  パスワード: $DB_PASSWORD"
        echo "  ポート: 5432 (localhost:5432)"
        echo ""
        echo "psqlで接続する場合:"
        echo "  docker exec -it $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME"
        echo ""
        echo "ローカルから接続する場合:"
        echo "  psql -h localhost -p 5432 -U $DB_USER -d $DB_NAME"
        exit 0
    fi
    echo -n "."
    sleep 1
done

echo ""
echo "❌ PostgreSQLの起動に失敗しました。"
echo "ログを確認します..."
docker logs $CONTAINER_NAME | tail -20
exit 1









