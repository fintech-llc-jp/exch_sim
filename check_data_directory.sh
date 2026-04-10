#!/bin/bash
# PostgreSQLデータディレクトリの情報を確認するスクリプト

VOLUME_NAME="algo_trader_v1_postgres_data"

echo "=========================================="
echo "PostgreSQLデータディレクトリ情報"
echo "=========================================="
echo ""

# ボリュームのマウントポイント
MOUNTPOINT=$(docker volume inspect $VOLUME_NAME --format '{{ .Mountpoint }}' 2>/dev/null)
echo "📁 データディレクトリの場所:"
echo "   $MOUNTPOINT"
echo ""
echo "注意: macOSのDocker Desktopでは、このパスは仮想マシン内のパスです。"
echo "直接アクセスするには、Dockerコンテナ経由でアクセスする必要があります。"
echo ""

# ボリューム全体のサイズ
echo "📊 ボリューム全体のサイズ:"
docker run --rm -v $VOLUME_NAME:/data alpine du -sh /data 2>/dev/null
echo ""

# 主要なディレクトリのサイズ
echo "📊 主要なディレクトリのサイズ:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  echo '  base/ (データベースデータ):'
  du -sh /data/base 2>/dev/null || echo '    (アクセス不可)'
  echo '  pg_wal/ (WALログ):'
  du -sh /data/pg_wal 2>/dev/null || echo '    (アクセス不可)'
  echo '  pg_stat/ (統計情報):'
  du -sh /data/pg_stat 2>/dev/null || echo '    (アクセス不可)'
" 2>/dev/null
echo ""

# データベースの一覧（コンテナが起動している場合）
echo "📋 データベース一覧（コンテナが起動している場合）:"
if docker ps | grep -q postgres; then
    CONTAINER=$(docker ps | grep postgres | awk '{print $1}' | head -1)
    docker exec $CONTAINER psql -U postgres -c "\l" 2>/dev/null || echo "  接続できません"
else
    echo "  コンテナが起動していません"
fi
echo ""

# テーブルサイズ（コンテナが起動している場合）
echo "📊 テーブルサイズ（コンテナが起動している場合）:"
if docker ps | grep -q postgres; then
    CONTAINER=$(docker ps | grep postgres | awk '{print $1}' | head -1)
    docker exec $CONTAINER psql -U postgres -d exch_sim -c "
SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
    pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename) - pg_relation_size(schemaname||'.'||tablename)) AS indexes_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
" 2>/dev/null || echo "  接続できません"
else
    echo "  コンテナが起動していません"
fi
echo ""

echo "=========================================="
echo "データディレクトリへのアクセス方法:"
echo "=========================================="
echo ""
echo "1. Dockerコンテナ経由でアクセス:"
echo "   docker run --rm -it -v $VOLUME_NAME:/data alpine sh"
echo "   # コンテナ内で /data にアクセス"
echo ""
echo "2. ファイルをコピー:"
echo "   docker run --rm -v $VOLUME_NAME:/data -v \$(pwd):/backup alpine tar czf /backup/postgres_backup.tar.gz /data"
echo ""
echo "3. データを削除（緊急時）:"
echo "   ./emergency_delete_data.sh"
echo ""









