#!/bin/bash

# testuserのパスワードをリセットするシンプルなスクリプト
# Rustアプリケーションを使ってパスワードハッシュを生成

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-exch_sim}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres123}"

export PGPASSWORD="$DB_PASSWORD"

echo "testuserのパスワードをリセットします..."
echo "新しいパスワード: testpass123"

# Rustでパスワードハッシュを生成
cd "$(dirname "$0")"
HASHED_PASSWORD=$(cargo run --quiet --bin reset_password 2>/dev/null)

if [ -z "$HASHED_PASSWORD" ] || [ "$HASHED_PASSWORD" = "Error" ]; then
    echo "エラー: パスワードのハッシュ化に失敗しました。"
    echo "Rust環境を確認してください。"
    exit 1
fi

echo "ハッシュ化されたパスワード: $HASHED_PASSWORD"

# データベースを更新
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" << EOF
UPDATE users 
SET password = '$HASHED_PASSWORD', updated_at = NOW()
WHERE username = 'testuser';
SELECT 'Password updated successfully' as result;
EOF

if [ $? -eq 0 ]; then
    echo "✓ testuserのパスワードをリセットしました。"
else
    echo "✗ パスワードのリセットに失敗しました。"
    exit 1
fi

unset PGPASSWORD


