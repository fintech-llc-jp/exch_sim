#!/bin/bash
# PostgreSQL pg_hba.conf 修正スクリプト
# 使用方法: このスクリプトをVPS上で実行してください

set -e

echo "=== PostgreSQL pg_hba.conf 修正スクリプト ==="
echo ""

# 1. pg_hba.confの場所を確認
echo "1. pg_hba.confの場所を確認中..."
PG_HBA_CONF=$(sudo -u postgres psql -t -c "SHOW hba_file;" 2>/dev/null | xargs)

if [ -z "$PG_HBA_CONF" ]; then
    # 代替方法: 一般的な場所を探す
    PG_HBA_CONF=$(sudo find /etc/postgresql -name pg_hba.conf 2>/dev/null | head -1)
fi

if [ -z "$PG_HBA_CONF" ] || [ ! -f "$PG_HBA_CONF" ]; then
    echo "エラー: pg_hba.confが見つかりません"
    echo "手動で場所を確認してください:"
    echo "  sudo find /etc -name pg_hba.conf"
    echo "  sudo find /var/lib/postgresql -name pg_hba.conf"
    exit 1
fi

echo "pg_hba.confの場所: $PG_HBA_CONF"
echo ""

# 2. バックアップを作成
echo "2. バックアップを作成中..."
BACKUP_FILE="${PG_HBA_CONF}.backup.$(date +%Y%m%d_%H%M%S)"
sudo cp "$PG_HBA_CONF" "$BACKUP_FILE"
echo "バックアップ作成完了: $BACKUP_FILE"
echo ""

# 3. 現在の設定を確認
echo "3. 現在の設定を確認中..."
echo "--- 現在のpg_hba.confの内容（最後の10行） ---"
sudo tail -10 "$PG_HBA_CONF"
echo ""

# 4. 接続元IPアドレスを確認
echo "4. 接続元IPアドレスを確認してください"
echo "エラーメッセージから: 110.132.22.18"
echo ""

# 5. 新しいエントリを追加するか確認
read -p "pg_hba.confに新しいエントリを追加しますか？ (y/n): " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "キャンセルされました"
    exit 0
fi

# 6. エントリを追加
echo "5. 新しいエントリを追加中..."
NEW_ENTRY="host    exch_sim    exch_sim_user    110.132.22.18/32    md5"

# 既に存在するか確認
if sudo grep -q "110.132.22.18" "$PG_HBA_CONF"; then
    echo "警告: 110.132.22.18のエントリが既に存在します"
    read -p "上書きしますか？ (y/n): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "キャンセルされました"
        exit 0
    fi
fi

# エントリを追加
echo "$NEW_ENTRY" | sudo tee -a "$PG_HBA_CONF" > /dev/null
echo "エントリを追加しました: $NEW_ENTRY"
echo ""

# 7. 設定を確認
echo "6. 追加後の設定を確認中..."
echo "--- 追加後のpg_hba.confの内容（最後の10行） ---"
sudo tail -10 "$PG_HBA_CONF"
echo ""

# 8. PostgreSQLをリロード
echo "7. PostgreSQLをリロード中..."
if sudo systemctl reload postgresql 2>/dev/null; then
    echo "PostgreSQLのリロードに成功しました"
elif sudo systemctl reload postgresql@* 2>/dev/null; then
    echo "PostgreSQLのリロードに成功しました"
else
    echo "警告: systemctlでのリロードに失敗しました"
    echo "手動でリロードしてください:"
    echo "  sudo systemctl reload postgresql"
    echo "または"
    echo "  sudo -u postgres pg_ctl reload"
fi
echo ""

# 9. 接続テスト
echo "8. 接続テスト..."
echo "以下のコマンドで接続をテストしてください:"
echo "  psql --host=77.42.74.155 --port=5432 --username=exch_sim_user --dbname=exch_sim"
echo ""

echo "=== 完了 ==="


