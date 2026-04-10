#!/bin/bash
# PostgreSQLデータディレクトリの詳細な場所と内容を表示するスクリプト

VOLUME_NAME="algo_trader_v1_postgres_data"

echo "=========================================="
echo "PostgreSQLデータディレクトリの詳細情報"
echo "=========================================="
echo ""

# ボリュームのマウントポイント
MOUNTPOINT=$(docker volume inspect $VOLUME_NAME --format '{{ .Mountpoint }}' 2>/dev/null)
echo "📁 データディレクトリの物理的な場所:"
echo "   $MOUNTPOINT"
echo ""
echo "⚠️  注意: macOSのDocker Desktopでは、このパスはDocker仮想マシン内のパスです。"
echo "   直接アクセスするには、Dockerコンテナ経由でアクセスする必要があります。"
echo ""

# ボリューム全体のサイズ
echo "📊 ボリューム全体のサイズ:"
TOTAL_SIZE=$(docker run --rm -v $VOLUME_NAME:/data alpine du -sh /data 2>/dev/null | awk '{print $1}')
echo "   $TOTAL_SIZE"
echo ""

# 各ディレクトリのサイズ
echo "📊 各ディレクトリのサイズ（大きい順）:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  du -sh /data/* 2>/dev/null | sort -h -r | while read size path; do
    dirname=\$(basename \$path)
    echo \"   \$size  -  \$dirname\"
  done
"
echo ""

# base/ ディレクトリの内容（データベースデータ）
echo "📁 base/ ディレクトリ（データベースの実際のデータ）:"
echo "   このディレクトリには各データベースのデータファイルが格納されています"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  if [ -d /data/base ]; then
    echo '   ディレクトリ数:'
    ls -1 /data/base | wc -l | xargs echo '   '
    echo '   サイズ:'
    du -sh /data/base 2>/dev/null | awk '{print \"   \" \$1}'
    echo '   主要なサブディレクトリ:'
    ls -1 /data/base | head -5 | while read dir; do
      size=\$(du -sh /data/base/\$dir 2>/dev/null | awk '{print \$1}')
      echo \"     \$dir (\$size)\"
    done
  else
    echo '   (存在しません)'
  fi
" 2>/dev/null
echo ""

# pg_wal/ ディレクトリの内容（WALログ）
echo "📁 pg_wal/ ディレクトリ（WALログ - 容量を多く使う可能性が高い）:"
docker run --rm -v $VOLUME_NAME:/data alpine sh -c "
  if [ -d /data/pg_wal ]; then
    echo '   サイズ:'
    du -sh /data/pg_wal 2>/dev/null | awk '{print \"   \" \$1}'
    echo '   ファイル数:'
    ls -1 /data/pg_wal 2>/dev/null | wc -l | xargs echo '   '
    echo '   最新のWALファイル（最大5つ）:'
    ls -lh /data/pg_wal 2>/dev/null | tail -6 | head -5 | awk '{print \"     \" \$9 \" (\" \$5 \")\"}'
  else
    echo '   (存在しません)'
  fi
" 2>/dev/null
echo ""

# データベースの情報（コンテナが起動している場合）
echo "📋 データベース情報（コンテナが起動している場合）:"
if docker ps | grep -q postgres; then
    CONTAINER=$(docker ps | grep postgres | awk '{print $1}' | head -1)
    echo "   コンテナID: $CONTAINER"
    echo ""
    echo "   データベース一覧:"
    docker exec $CONTAINER psql -U postgres -c "\l" 2>/dev/null | grep -E "(Name|exch_sim)" || echo "   接続できません"
    echo ""
    echo "   exch_simデータベースのテーブルサイズ:"
    docker exec $CONTAINER psql -U postgres -d exch_sim -c "
SELECT 
    tablename,
    pg_size_pretty(pg_total_relation_size('public.'||tablename)) AS total_size,
    pg_size_pretty(pg_relation_size('public.'||tablename)) AS table_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.'||tablename) DESC;
" 2>/dev/null | head -15 || echo "   接続できません"
else
    echo "   コンテナが起動していません"
fi
echo ""

echo "=========================================="
echo "データディレクトリへのアクセス方法:"
echo "=========================================="
echo ""
echo "1. インタラクティブシェルでアクセス:"
echo "   docker run --rm -it -v $VOLUME_NAME:/data alpine sh"
echo "   # コンテナ内で /data にアクセス"
echo ""
echo "2. 特定のディレクトリを確認:"
echo "   docker run --rm -v $VOLUME_NAME:/data alpine ls -lh /data/base"
echo "   docker run --rm -v $VOLUME_NAME:/data alpine ls -lh /data/pg_wal"
echo ""
echo "3. ファイルをバックアップ:"
echo "   docker run --rm -v $VOLUME_NAME:/data -v \$(pwd):/backup alpine tar czf /backup/postgres_backup.tar.gz /data"
echo ""
echo "4. データを削除（緊急時）:"
echo "   ./emergency_delete_data.sh"
echo ""









