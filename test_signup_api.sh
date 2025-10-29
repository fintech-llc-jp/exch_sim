#!/bin/bash

# ユーザー登録APIテストスクリプト

API_URL="http://localhost:8080/api/auth/signup"

echo "=== ユーザー登録APIテスト ==="

# テストケース1: 正常なユーザー登録
echo "1. 正常なユーザー登録テスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser001",
    "password": "SecurePass123"
  }' \
  -w "\nStatus: %{http_code}\n\n"

# テストケース2: 既存ユーザーの重複登録
echo "2. 重複ユーザー登録テスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser001",
    "password": "SecurePass123"
  }' \
  -w "\nStatus: %{http_code}\n\n"

# テストケース3: 短すぎるユーザー名
echo "3. 短すぎるユーザー名テスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "ab",
    "password": "SecurePass123"
  }' \
  -w "\nStatus: %{http_code}\n\n"

# テストケース4: 短すぎるパスワード
echo "4. 短すぎるパスワードテスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser002",
    "password": "123"
  }' \
  -w "\nStatus: %{http_code}\n\n"

# テストケース5: 空のユーザー名
echo "5. 空のユーザー名テスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "",
    "password": "SecurePass123"
  }' \
  -w "\nStatus: %{http_code}\n\n"

# テストケース6: 空のパスワード
echo "6. 空のパスワードテスト"
curl -X POST $API_URL \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser003",
    "password": ""
  }' \
  -w "\nStatus: %{http_code}\n\n"

echo "=== テスト完了 ===" 