#!/bin/bash

# 複数シナリオテスト設定
BASE_URL="http://localhost:8080"
USERNAME="testuser"
PASSWORD="password123"

# 色付きアウトプット用
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# JWTトークン取得関数
get_jwt_token() {
  LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{
      \"username\": \"${USERNAME}\",
      \"password\": \"${PASSWORD}\"
    }")
  
  JWT_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')
  
  if [ "$JWT_TOKEN" = "null" ] || [ -z "$JWT_TOKEN" ]; then
    echo -e "${RED}❌ ログイン失敗${NC}"
    exit 1
  fi
  
  echo "$JWT_TOKEN"
}

# 成行注文テスト関数
test_market_order() {
  local SYMBOL=$1
  local SIDE=$2
  local QUANTITY=$3
  local TEST_NAME=$4
  
  echo -e "\n${BLUE}=== ${TEST_NAME} ===${NC}"
  
  JWT_TOKEN=$(get_jwt_token)
  
  # 注文実行
  ORDER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/orders/new" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d "{
      \"symbol\": \"${SYMBOL}\",
      \"quantity\": ${QUANTITY},
      \"side\": \"${SIDE}\",
      \"ordType\": \"MARKET\",
      \"tif\": \"IOC\"
    }")
  
  ORDER_STATUS=$(echo "$ORDER_RESPONSE" | jq -r '.status')
  EXECUTIONS_COUNT=$(echo "$ORDER_RESPONSE" | jq '.executions | length')
  
  echo "注文ステータス: ${ORDER_STATUS}"
  echo "約定件数: ${EXECUTIONS_COUNT}"
  
  # 少し待機
  sleep 1
  
  # 約定ポーリング
  POLL_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/executions/poll?maxCount=10" \
    -H "Authorization: Bearer ${JWT_TOKEN}")
  
  POLLED_COUNT=$(echo "$POLL_RESPONSE" | jq '.executionCount')
  
  echo "ポーリング取得件数: ${POLLED_COUNT}"
  
  if [ "$POLLED_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✅ ${TEST_NAME} 成功${NC}"
    return 0
  else
    echo -e "${RED}❌ ${TEST_NAME} 失敗${NC}"
    return 1
  fi
}

# 指値注文テスト関数
test_limit_order() {
  local SYMBOL=$1
  local SIDE=$2
  local PRICE=$3
  local QUANTITY=$4
  local TEST_NAME=$5
  
  echo -e "\n${BLUE}=== ${TEST_NAME} ===${NC}"
  
  JWT_TOKEN=$(get_jwt_token)
  
  # 注文実行
  ORDER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/orders/new" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT_TOKEN}" \
    -d "{
      \"symbol\": \"${SYMBOL}\",
      \"price\": ${PRICE},
      \"quantity\": ${QUANTITY},
      \"side\": \"${SIDE}\",
      \"ordType\": \"LIMIT\",
      \"tif\": \"GTC\"
    }")
  
  ORDER_STATUS=$(echo "$ORDER_RESPONSE" | jq -r '.status')
  EXECUTIONS_COUNT=$(echo "$ORDER_RESPONSE" | jq '.executions | length')
  CL_ORD_ID=$(echo "$ORDER_RESPONSE" | jq -r '.clOrdID')
  
  echo "注文ステータス: ${ORDER_STATUS}"
  echo "注文ID: ${CL_ORD_ID}"
  echo "約定件数: ${EXECUTIONS_COUNT}"
  
  # 指値注文の場合、約定する場合としない場合がある
  if [ "$EXECUTIONS_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✅ ${TEST_NAME} 即座に約定${NC}"
    
    # 約定ポーリング
    sleep 1
    POLL_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/executions/poll?maxCount=10" \
      -H "Authorization: Bearer ${JWT_TOKEN}")
    
    POLLED_COUNT=$(echo "$POLL_RESPONSE" | jq '.executionCount')
    echo "ポーリング取得件数: ${POLLED_COUNT}"
    
    if [ "$POLLED_COUNT" -gt 0 ]; then
      echo -e "${GREEN}✅ ポーリング成功${NC}"
    else
      echo -e "${RED}❌ ポーリング失敗${NC}"
    fi
  else
    echo -e "${YELLOW}📝 ${TEST_NAME} 板に登録（未約定）${NC}"
    
    # 注文キャンセルテスト
    echo "注文キャンセル中..."
    CANCEL_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/orders/cancel" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d "{
        \"clOrdID\": \"${CL_ORD_ID}\",
        \"symbol\": \"${SYMBOL}\"
      }")
    
    CANCEL_STATUS=$(echo "$CANCEL_RESPONSE" | jq -r '.status')
    echo "キャンセルステータス: ${CANCEL_STATUS}"
    
    if [ "$CANCEL_STATUS" = "CANCELED" ]; then
      echo -e "${GREEN}✅ キャンセル成功${NC}"
    else
      echo -e "${RED}❌ キャンセル失敗${NC}"
    fi
  fi
}

echo -e "${BLUE}=== 複数シナリオテスト開始 ===${NC}"

# テストカウンター
TOTAL_TESTS=0
PASSED_TESTS=0

# シナリオ1: B_FX_BTCJPY 成行買い
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if test_market_order "B_FX_BTCJPY" "BUY" "0.001" "B_FX_BTCJPY 成行買いテスト"; then
  PASSED_TESTS=$((PASSED_TESTS + 1))
fi

# シナリオ2: B_FX_BTCJPY 成行売り
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if test_market_order "B_FX_BTCJPY" "SELL" "0.001" "B_FX_BTCJPY 成行売りテスト"; then
  PASSED_TESTS=$((PASSED_TESTS + 1))
fi

# シナリオ3: G_BTCJPY 成行買い
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if test_market_order "G_BTCJPY" "BUY" "0.001" "G_BTCJPY 成行買いテスト"; then
  PASSED_TESTS=$((PASSED_TESTS + 1))
fi

# シナリオ4: 指値買い注文（低い価格で未約定）
test_limit_order "B_FX_BTCJPY" "BUY" "1000.0" "0.001" "指値買い注文テスト（低価格）"

# シナリオ5: 指値売り注文（高い価格で未約定）
test_limit_order "B_FX_BTCJPY" "SELL" "20000.0" "0.001" "指値売り注文テスト（高価格）"

# 結果サマリー
echo -e "\n${BLUE}=== テスト結果サマリー ===${NC}"
echo -e "成行注文テスト: ${PASSED_TESTS}/${TOTAL_TESTS} 成功"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
  echo -e "${GREEN}🎉 全ての成行注文テストが成功しました！${NC}"
  exit 0
else
  echo -e "${RED}❌ 一部のテストが失敗しました${NC}"
  exit 1
fi