#!/bin/bash

# テスト設定
BASE_URL="http://localhost:8080"
USERNAME="testuser"
PASSWORD="password123"
SYMBOL="B_FX_BTCJPY"

# 色付きアウトプット用
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== 約定フローテスト開始 ===${NC}"

# 1. ログインしてJWTトークンを取得
echo -e "\n${YELLOW}1. ログイン中...${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"username\": \"${USERNAME}\",
    \"password\": \"${PASSWORD}\"
  }")

# JWTトークンを抽出
JWT_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')

if [ "$JWT_TOKEN" = "null" ] || [ -z "$JWT_TOKEN" ]; then
  echo -e "${RED}❌ ログイン失敗: $LOGIN_RESPONSE${NC}"
  exit 1
fi

echo -e "${GREEN}✅ ログイン成功${NC}"
echo "JWT Token: ${JWT_TOKEN:0:50}..."

# 2. 初期状態のキューサイズ確認
echo -e "\n${YELLOW}2. 初期キューサイズ確認...${NC}"
QUEUE_SIZE_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/executions/queue-size" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

echo "初期キューサイズ: $QUEUE_SIZE_RESPONSE"

# 3. マーケットボード確認
echo -e "\n${YELLOW}3. マーケットボード確認 (${SYMBOL})...${NC}"
MARKET_BOARD_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/market/board/${SYMBOL}" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

echo "マーケットボード: $MARKET_BOARD_RESPONSE" | jq '.'

# asks（売り気配）が存在するかチェック
ASKS_COUNT=$(echo "$MARKET_BOARD_RESPONSE" | jq '.asks | length')
if [ "$ASKS_COUNT" -eq 0 ]; then
  echo -e "${RED}❌ 売り気配が存在しません。成行買い注文はマッチしません。${NC}"
  exit 1
fi

echo -e "${GREEN}✅ 売り気配 ${ASKS_COUNT}件 確認${NC}"

# 4. 成行買い注文実行
echo -e "\n${YELLOW}4. 成行買い注文実行...${NC}"
ORDER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/orders/new" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -d "{
    \"symbol\": \"${SYMBOL}\",
    \"quantity\": 0.001,
    \"side\": \"BUY\",
    \"ordType\": \"MARKET\",
    \"tif\": \"IOC\"
  }")

echo "注文レスポンス: $ORDER_RESPONSE" | jq '.'

# 注文ステータス確認
ORDER_STATUS=$(echo "$ORDER_RESPONSE" | jq -r '.status')
if [ "$ORDER_STATUS" != "FILLED" ]; then
  echo -e "${RED}❌ 注文が約定しませんでした。ステータス: ${ORDER_STATUS}${NC}"
  exit 1
fi

EXECUTIONS_COUNT=$(echo "$ORDER_RESPONSE" | jq '.executions | length')
echo -e "${GREEN}✅ 注文約定成功: ${EXECUTIONS_COUNT}件の約定${NC}"

# 5. 少し待機（非同期処理のため）
echo -e "\n${YELLOW}5. 処理待機中...${NC}"
sleep 2

# 6. 約定後のキューサイズ確認
echo -e "\n${YELLOW}6. 約定後キューサイズ確認...${NC}"
QUEUE_SIZE_AFTER=$(curl -s -X GET "${BASE_URL}/api/executions/queue-size" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

echo "約定後キューサイズ: $QUEUE_SIZE_AFTER"

# 7. 約定ポーリング
echo -e "\n${YELLOW}7. 約定ポーリング実行...${NC}"
POLL_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/executions/poll?maxCount=10" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

echo "ポーリングレスポンス: $POLL_RESPONSE" | jq '.'

# 結果確認
POLLED_EXECUTIONS_COUNT=$(echo "$POLL_RESPONSE" | jq '.executionCount')
if [ "$POLLED_EXECUTIONS_COUNT" -gt 0 ]; then
  echo -e "\n${GREEN}🎉 テスト成功！${NC}"
  echo -e "${GREEN}   - 注文約定件数: ${EXECUTIONS_COUNT}${NC}"
  echo -e "${GREEN}   - ポーリング取得件数: ${POLLED_EXECUTIONS_COUNT}${NC}"
else
  echo -e "\n${RED}❌ テスト失敗: 約定がポーリングで取得できませんでした${NC}"
  exit 1
fi

# 8. ポーリング後のキューサイズ確認（空になっているはず）
echo -e "\n${YELLOW}8. ポーリング後キューサイズ確認...${NC}"
QUEUE_SIZE_FINAL=$(curl -s -X GET "${BASE_URL}/api/executions/queue-size" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

echo "最終キューサイズ: $QUEUE_SIZE_FINAL"

# 9. マーケットボード再確認（気配の変化を確認）
echo -e "\n${YELLOW}9. 約定後マーケットボード確認...${NC}"
MARKET_BOARD_FINAL=$(curl -s -X GET "${BASE_URL}/api/market/board/${SYMBOL}" \
  -H "Authorization: Bearer ${JWT_TOKEN}")

FINAL_ASKS_COUNT=$(echo "$MARKET_BOARD_FINAL" | jq '.asks | length')
echo "約定後の売り気配件数: ${FINAL_ASKS_COUNT}"

if [ "$FINAL_ASKS_COUNT" -lt "$ASKS_COUNT" ]; then
  echo -e "${GREEN}✅ 売り気配が消費されました (${ASKS_COUNT} → ${FINAL_ASKS_COUNT})${NC}"
else
  echo -e "${YELLOW}⚠️  売り気配に変化がありません${NC}"
fi

echo -e "\n${BLUE}=== テスト完了 ===${NC}"