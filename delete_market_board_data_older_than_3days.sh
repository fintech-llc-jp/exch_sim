#!/bin/bash
# 3日以上前のマーケットボードデータのみを削除するスクリプト
# market_board_snapshots と market_board_price_levels のうち、
# timestamp が 3日より古いレコードを削除します。

set -e

# このスクリプトのディレクトリに移動
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SQL_FILE="delete_market_board_data_older_than_3days.sql"

# 接続先のデフォルト（環境変数で上書き可能）
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:=postgres}"
: "${PGDATABASE:=exch_sim}"
: "${PGPASSWORD:=}"

# 例: VPSでexch_sim_userを使う場合
# PGPASSWORD="sr3110ysi" PGHOST=localhost PGPORT=15432 PGUSER=exch_sim_user PGDATABASE=exch_sim ./delete_market_board_data_older_than_3days.sh

echo "=========================================="
echo "3日以上前のマーケットボードデータ削除"
echo "=========================================="
echo "接続先: $PGUSER@$PGHOST:$PGPORT/$PGDATABASE"
echo "SQL: $SQL_FILE"
echo ""

if [ ! -f "$SQL_FILE" ]; then
    echo "エラー: $SQL_FILE が見つかりません。"
    exit 1
fi

export PGHOST PGPORT PGUSER PGDATABASE
if [ -n "$PGPASSWORD" ]; then
    export PGPASSWORD
fi

psql -d "$PGDATABASE" -f "$SQL_FILE"

echo ""
echo "完了しました。"
