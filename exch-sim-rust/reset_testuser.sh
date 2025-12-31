#!/bin/bash

# testuserのパスワードをリセットするスクリプト

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-exch_sim}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres123}"

export PGPASSWORD="$DB_PASSWORD"

echo "testuserのパスワードをリセットします..."
echo "新しいパスワード: testpass123"

# bcryptでパスワードをハッシュ化（Rustで生成する必要があるため、一時的にPythonを使用）
HASHED_PASSWORD=$(python3 << 'EOF'
import bcrypt
password = "testpass123"
hashed = bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt())
print(hashed.decode('utf-8'))
EOF
)

if [ -z "$HASHED_PASSWORD" ]; then
    echo "エラー: パスワードのハッシュ化に失敗しました。"
    echo "Python3とbcryptモジュールが必要です: pip install bcrypt"
    exit 1
fi

echo "ハッシュ化されたパスワード: $HASHED_PASSWORD"

# データベースを更新
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" << EOF
UPDATE users 
SET password = '$HASHED_PASSWORD', updated_at = NOW()
WHERE username = 'testuser';
EOF

if [ $? -eq 0 ]; then
    echo "✓ testuserのパスワードをリセットしました。"
else
    echo "✗ パスワードのリセットに失敗しました。"
    exit 1
fi

unset PGPASSWORD


