#!/bin/bash
# PostgreSQL設定ファイルの修正スクリプト
# postgresql.confから誤ったエントリを削除し、pg_hba.confに正しく追加

set -e

echo "=== PostgreSQL設定ファイルの修正 ==="
echo ""

# 1. postgresql.confから誤った行を削除
POSTGRESQL_CONF="/etc/postgresql/15/main/postgresql.conf"
echo "1. postgresql.confから誤った行を削除中..."
echo "ファイル: $POSTGRESQL_CONF"

if [ -f "$POSTGRESQL_CONF" ]; then
    # バックアップを作成
    sudo cp "$POSTGRESQL_CONF" "${POSTGRESQL_CONF}.backup.$(date +%Y%m%d_%H%M%S)"
    echo "バックアップ作成完了"
    
    # 838行目付近を確認
    echo ""
    echo "--- 838行目付近の内容 ---"
    sudo sed -n '835,840p' "$POSTGRESQL_CONF"
    echo ""
    
    # 誤った行を削除（838行目）
    echo "838行目を削除中..."
    sudo sed -i '838d' "$POSTGRESQL_CONF"
    echo "削除完了"
    
    # 確認
    echo ""
    echo "--- 削除後の838行目付近の内容 ---"
    sudo sed -n '835,840p' "$POSTGRESQL_CONF"
    echo ""
else
    echo "エラー: $POSTGRESQL_CONF が見つかりません"
    exit 1
fi

# 2. pg_hba.confに正しく追加
PG_HBA_CONF="/etc/postgresql/15/main/pg_hba.conf"
echo "2. pg_hba.confに正しいエントリを追加中..."
echo "ファイル: $PG_HBA_CONF"

if [ -f "$PG_HBA_CONF" ]; then
    # バックアップを作成
    sudo cp "$PG_HBA_CONF" "${PG_HBA_CONF}.backup.$(date +%Y%m%d_%H%M%S)"
    echo "バックアップ作成完了"
    
    # 既に存在するか確認
    if sudo grep -q "110.132.22.18" "$PG_HBA_CONF"; then
        echo "警告: 110.132.22.18のエントリが既に存在します"
        echo "--- 既存のエントリ ---"
        sudo grep "110.132.22.18" "$PG_HBA_CONF"
    else
        # エントリを追加
        NEW_ENTRY="host    exch_sim    exch_sim_user    110.132.22.18/32    md5"
        echo "$NEW_ENTRY" | sudo tee -a "$PG_HBA_CONF" > /dev/null
        echo "エントリを追加しました: $NEW_ENTRY"
    fi
    
    echo ""
    echo "--- pg_hba.confの最後の10行 ---"
    sudo tail -10 "$PG_HBA_CONF"
    echo ""
else
    echo "エラー: $PG_HBA_CONF が見つかりません"
    exit 1
fi

# 3. 設定ファイルの構文チェック
echo "3. 設定ファイルの構文チェック中..."
if sudo -u postgres psql -c "SHOW hba_file;" > /dev/null 2>&1; then
    echo "✓ postgresql.confの構文は正常です"
else
    echo "✗ postgresql.confにまだエラーがあります"
    echo "手動で確認してください:"
    echo "  sudo -u postgres psql -c \"SHOW hba_file;\""
    exit 1
fi

# 4. PostgreSQLをリロード
echo ""
echo "4. PostgreSQLをリロード中..."
if sudo systemctl reload postgresql; then
    echo "✓ PostgreSQLのリロードに成功しました"
else
    echo "✗ リロードに失敗しました。手動で再起動してください:"
    echo "  sudo systemctl restart postgresql"
    exit 1
fi

echo ""
echo "=== 完了 ==="
echo ""
echo "接続テスト:"
echo "  psql --host=77.42.74.155 --port=5432 --username=exch_sim_user --dbname=exch_sim"


