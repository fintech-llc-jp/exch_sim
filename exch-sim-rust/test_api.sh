#!/bin/bash

# APIテストスクリプト

BASE_URL="http://localhost:8080"

echo "ExchSim Rust API テスト"
echo "======================"
echo ""

# 1. ユーザー登録
echo "1. ユーザー登録..."
SIGNUP_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/signup" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "yukio004",
    "password": "yukio004"
  }')

echo "$SIGNUP_RESPONSE" | jq '.' 2>/dev/null || echo "$SIGNUP_RESPONSE"
echo ""

# トークンを取得
TOKEN=$(echo "$SIGNUP_RESPONSE" | jq -r '.token' 2>/dev/null)

if [ "$TOKEN" = "null" ] || [ -z "$TOKEN" ]; then
    echo "ユーザー登録に失敗したか、既に存在するユーザーです。ログインを試みます..."
    
    # 2. ログイン
    echo ""
    echo "2. ログイン..."
    LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      -d '{
        "username": "testuser",
        "password": "testpass123"
      }')
    
    echo "$LOGIN_RESPONSE" | jq '.' 2>/dev/null || echo "$LOGIN_RESPONSE"
    TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token' 2>/dev/null)
fi

if [ "$TOKEN" = "null" ] || [ -z "$TOKEN" ]; then
    echo "エラー: トークンの取得に失敗しました。"
    exit 1
fi

echo ""
echo "✓ 認証成功。トークン: ${TOKEN:0:20}..."
echo ""

# 3. ポジション確認
echo "3. ポジション確認..."
curl -s -X GET "$BASE_URL/api/positions/summary" \
  -H "Authorization: Bearer $TOKEN" | jq '.' 2>/dev/null || \
curl -s -X GET "$BASE_URL/api/positions/summary" \
  -H "Authorization: Bearer $TOKEN"
echo ""

# 4. 取引履歴確認
echo "4. 取引履歴確認..."
curl -s -X GET "$BASE_URL/api/trade-history" \
  -H "Authorization: Bearer $TOKEN" | jq '.' 2>/dev/null || \
curl -s -X GET "$BASE_URL/api/trade-history" \
  -H "Authorization: Bearer $TOKEN"
echo ""

echo "テスト完了！"


