#!/bin/bash
# ディスク容量を確保してからデータを削除するスクリプト

VOLUME_NAME="algo_trader_v1_postgres_data"
CONTAINER_NAME="algo_trader_postgres"

echo "=========================================="
echo "ディスク容量確保 → 板データ削除スクリプト"
echo "=========================================="
echo ""

# 現在のディスク使用状況を確認
echo "📊 現在のディスク使用状況:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1"
echo ""

# WALログのサイズを確認
echo "📊 WALログのサイズ:"
WAL_SIZE=$(docker run --rm -v $VOLUME_NAME:/data alpine du -sh /data/pg_wal 2>/dev/null | awk '{print $1}')
echo "   pg_wal/: $WAL_SIZE"
echo ""

# 既存の一時コンテナを削除
if docker ps -a | grep -q "${CONTAINER_NAME}_temp"; then
    echo "既存の一時コンテナを削除します..."
    docker rm -f ${CONTAINER_NAME}_temp 2>/dev/null
fi

echo "⚠️  注意: ディスク容量が100%のため、PostgreSQLを起動できません。"
echo "   まず、WALログを削除して容量を確保します。"
echo ""
read -p "WALログを削除して容量を確保しますか？ (y/n): " -r
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "キャンセルしました。"
    exit 1
fi

# WALログを削除（古いWALファイルのみ、最新の数個は保持）
echo ""
echo "WALログを削除します（最新の5ファイルは保持）..."
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  cd /data/pg_wal
  # 最新の5ファイル以外を削除
  ls -t | tail -n +6 | xargs rm -f 2>/dev/null
  echo 'WALログの削除が完了しました。'
  echo ''
  echo '残りのWALファイル:'
  ls -lh | wc -l | xargs echo '   '
  echo ''
  echo 'WALディレクトリのサイズ:'
  du -sh /data/pg_wal
"

echo ""
echo "📊 削除後のディスク使用状況:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "df -h /data | tail -1"
echo ""

# PostgreSQLを起動してデータを削除
echo "PostgreSQLを起動して板データを削除します..."
./delete_market_board_direct.sh









