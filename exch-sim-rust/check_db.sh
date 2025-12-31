#!/bin/bash

# データベースの状態を確認するスクリプト

echo "データベースの状態を確認します..."
echo ""

# PostgreSQL接続情報（config.tomlから読み取るか、環境変数から）
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-exch_sim}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres123}"

export PGPASSWORD="$DB_PASSWORD"

echo "1. ユーザー一覧:"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT username, created_at FROM users ORDER BY created_at DESC LIMIT 10;" 2>&1

echo ""
echo "2. testuserの存在確認:"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT EXISTS(SELECT 1 FROM users WHERE username = 'testuser') as user_exists;" 2>&1

echo ""
echo "3. testuserの詳細:"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT username, password IS NOT NULL as has_password, created_at, updated_at FROM users WHERE username = 'testuser';" 2>&1

echo ""
echo "4. testuserのロール:"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT username, role FROM user_roles WHERE username = 'testuser';" 2>&1

echo ""
echo "5. テーブル構造確認 (users):"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "\d users" 2>&1

echo ""
echo "6. テーブル構造確認 (user_roles):"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "\d user_roles" 2>&1

unset PGPASSWORD


