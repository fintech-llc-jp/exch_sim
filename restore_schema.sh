#!/bin/bash
# exch_sim スキーマ復元スクリプト
# Docker削除後にスキーマのみ復元する場合に使用

CONTAINER_NAME="algo_trader_postgres"
DB_NAME="exch_sim"
DB_USER="postgres"
DB_PASSWORD="postgres123"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=========================================="
echo "exch_sim スキーマ復元"
echo "=========================================="
echo ""

# PostgreSQLコンテナの確認
if ! docker ps -a | grep -q "$CONTAINER_NAME"; then
    echo "PostgreSQLコンテナが見つかりません。"
    echo "先に以下を実行してコンテナを作成してください:"
    echo "  ./create_and_start_postgres.sh"
    echo ""
    echo "または start_postgres.sh を試してください。"
    exit 1
fi

# コンテナが停止している場合は起動
if ! docker ps | grep -q "$CONTAINER_NAME"; then
    echo "PostgreSQLコンテナを起動しています..."
    docker start $CONTAINER_NAME
    sleep 3
fi

# 接続確認
if ! docker exec $CONTAINER_NAME pg_isready -U $DB_USER >/dev/null 2>&1; then
    echo "❌ PostgreSQLに接続できません。"
    echo "   docker logs $CONTAINER_NAME でログを確認してください。"
    exit 1
fi

echo "PostgreSQLに接続しました。スキーマを復元します..."
echo ""

# スキーマ復元
docker exec -i $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME < "$SCRIPT_DIR/restore_schema.sql"

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ スキーマの復元が完了しました。"
    echo ""
    echo "テーブル一覧を確認:"
    docker exec $CONTAINER_NAME psql -U $DB_USER -d $DB_NAME -c "\dt"
else
    echo ""
    echo "❌ スキーマの復元に失敗しました。"
    exit 1
fi
