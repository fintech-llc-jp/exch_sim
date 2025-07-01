#!/bin/bash

# クイックテスト - デバッグ用
BASE_URL="http://localhost:8080"
USERNAME="testuser"
PASSWORD="password123"

# JWTトークン取得
echo "🔐 ログイン中..."
JWT_TOKEN=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"${USERNAME}\", \"password\": \"${PASSWORD}\"}" | \
  jq -r '.token')

if [ "$JWT_TOKEN" = "null" ]; then
  echo "❌ ログイン失敗"
  exit 1
fi

echo "✅ ログイン成功"
echo "JWT: ${JWT_TOKEN:0:50}..."

# 引数に応じて処理を分岐
case "$1" in
  "market-buy")
    echo "📈 成行買い注文実行..."
    curl -s -X POST "${BASE_URL}/api/orders/new" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d '{
        "symbol": "B_FX_BTCJPY",
        "quantity": 0.02,
        "side": "BUY",
        "ordType": "MARKET",
        "tif": "IOC"
      }' | jq '.'
    ;;
  "market-sell")
    echo "📉 成行売り注文実行..."
    curl -s -X POST "${BASE_URL}/api/orders/new" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d '{
        "symbol": "B_FX_BTCJPY",
        "quantity": 0.01,
        "side": "SELL",
        "ordType": "MARKET",
        "tif": "IOC"
      }' | jq '.'
    ;;
  "poll")
    echo "📥 約定ポーリング..."
    curl -s -X GET "${BASE_URL}/api/executions/poll?maxCount=10" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "queue-size")
    echo "📊 キューサイズ確認..."
    curl -s -X GET "${BASE_URL}/api/executions/queue-size" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "board")
    SYMBOL=${2:-"B_FX_BTCJPY"}
    echo "📋 マーケットボード確認 (${SYMBOL})..."
    curl -s -X GET "${BASE_URL}/api/market/board/${SYMBOL}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "limit-buy")
    PRICE=${2:-"1000.0"}
    echo "📈 指値買い注文実行 (価格: ${PRICE})..."
    curl -s -X POST "${BASE_URL}/api/orders/new" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d "{
        \"symbol\": \"B_FX_BTCJPY\",
        \"price\": ${PRICE},
        \"quantity\": 0.01,
        \"side\": \"BUY\",
        \"ordType\": \"LIMIT\",
        \"tif\": \"GTC\"
      }" | jq '.'
    ;;
  "limit-sell")
    PRICE=${2:-"20000.0"}
    echo "📉 指値売り注文実行 (価格: ${PRICE})..."
    curl -s -X POST "${BASE_URL}/api/orders/new" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d "{
        \"symbol\": \"B_FX_BTCJPY\",
        \"price\": ${PRICE},
        \"quantity\": 0.01,
        \"side\": \"SELL\",
        \"ordType\": \"LIMIT\",
        \"tif\": \"GTC\"
      }" | jq '.'
    ;;
  "history")
    PAGE=${2:-"0"}
    SIZE=${3:-"10"}
    SYMBOL=${4}
    echo "📜 約定履歴取得 (page: ${PAGE}, size: ${SIZE}, symbol: ${SYMBOL})..."
    if [ -n "$SYMBOL" ]; then
      curl -s -X GET "${BASE_URL}/api/executions/history?page=${PAGE}&size=${SIZE}&symbol=${SYMBOL}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    else
      curl -s -X GET "${BASE_URL}/api/executions/history?page=${PAGE}&size=${SIZE}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    fi
    ;;
  "history-all")
    PAGE=${2:-"0"}
    SIZE=${3:-"10"}
    SYMBOL=${4}
    echo "📜 全約定履歴取得（デバッグ用） (page: ${PAGE}, size: ${SIZE}, symbol: ${SYMBOL})..."
    if [ -n "$SYMBOL" ]; then
      curl -s -X GET "${BASE_URL}/api/executions/history?page=${PAGE}&size=${SIZE}&symbol=${SYMBOL}&filledOnly=false" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    else
      curl -s -X GET "${BASE_URL}/api/executions/history?page=${PAGE}&size=${SIZE}&filledOnly=false" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    fi
    ;;
  "debug")
    echo "🔍 デバッグ情報取得..."
    curl -s -X GET "${BASE_URL}/api/executions/debug" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "db-info")
    echo "💽 データベース情報取得..."
    curl -s -X GET "${BASE_URL}/api/executions/db-info" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "trade-insert")
    SYMBOL=${2:-"B_FX_BTCJPY"}
    PRICE=${3:-"1000.0"}
    QUANTITY=${4:-"0.01"}
    SIDE=${5:-"BUY"}
    echo "💱 トレード挿入 (symbol: ${SYMBOL}, price: ${PRICE}, quantity: ${QUANTITY}, side: ${SIDE})..."
    echo "📋 実行前の板状態:"
    curl -s -X GET "${BASE_URL}/api/market/board/${SYMBOL}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.asks[0:3], .bids[0:3]'
    echo ""
    echo "🔄 トレード挿入実行中..."
    TRADE_RESULT=$(curl -s -X POST "${BASE_URL}/api/trade/insert" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d "{
        \"symbol\": \"${SYMBOL}\",
        \"price\": ${PRICE},
        \"quantity\": ${QUANTITY},
        \"side\": \"${SIDE}\"
      }")
    echo "$TRADE_RESULT" | jq '.'
    echo ""
    echo "📋 実行後の板状態:"
    curl -s -X GET "${BASE_URL}/api/market/board/${SYMBOL}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.asks[0:3], .bids[0:3]'
    ;;
  "all-history")
    PAGE=${2:-"0"}
    SIZE=${3:-"10"}
    SYMBOL=${4}
    echo "🌍 全体約定履歴取得 (page: ${PAGE}, size: ${SIZE}, symbol: ${SYMBOL})..."
    if [ -n "$SYMBOL" ]; then
      curl -s -X GET "${BASE_URL}/api/executions/all?page=${PAGE}&size=${SIZE}&symbol=${SYMBOL}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    else
      curl -s -X GET "${BASE_URL}/api/executions/all?page=${PAGE}&size=${SIZE}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    fi
    ;;
  "volume")
    SYMBOL=${2:-"B_FX_BTCJPY"}
    FROM_TIME=${3:-"2025-06-29T08:00:00"}
    TO_TIME=${4:-"2025-06-30T08:59:59"}
    echo "📊 約定量計算 (symbol: ${SYMBOL}, from: ${FROM_TIME}, to: ${TO_TIME})..."
    curl -s -X GET "${BASE_URL}/api/executions/volume?symbol=${SYMBOL}&fromTime=${FROM_TIME}&toTime=${TO_TIME}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "full-test")
    echo "🔄 フルテスト実行..."
    echo "1️⃣ 初期キューサイズ:"
    curl -s -X GET "${BASE_URL}/api/executions/queue-size" -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    
    echo "2️⃣ 成行買い注文:"
    curl -s -X POST "${BASE_URL}/api/orders/new" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -d '{
        "symbol": "B_FX_BTCJPY",
        "quantity": 0.01,
        "side": "BUY",
        "ordType": "MARKET",
        "tif": "IOC"
      }' | jq '.'
    
    echo "3️⃣ 2秒待機..."
    sleep 2
    
    echo "4️⃣ 約定後キューサイズ:"
    curl -s -X GET "${BASE_URL}/api/executions/queue-size" -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    
    echo "5️⃣ 約定ポーリング:"
    curl -s -X GET "${BASE_URL}/api/executions/poll?maxCount=10" -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    
    echo "6️⃣ 最終キューサイズ:"
    curl -s -X GET "${BASE_URL}/api/executions/queue-size" -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  *)
    echo "使用方法:"
    echo "  $0 market-buy      - 成行買い注文"
    echo "  $0 market-sell     - 成行売り注文"
    echo "  $0 poll            - 約定ポーリング"
    echo "  $0 queue-size      - キューサイズ確認"
    echo "  $0 board [SYMBOL]  - マーケットボード確認"
    echo "  $0 limit-buy [PRICE] - 指値買い注文"
    echo "  $0 limit-sell [PRICE] - 指値売り注文"
    echo "  $0 history [PAGE] [SIZE] [SYMBOL] - 約定履歴取得（FILLED/PARTIAL_FILLのみ）"
    echo "  $0 history-all [PAGE] [SIZE] [SYMBOL] - 全約定履歴取得（デバッグ用）"
    echo "  $0 all-history [PAGE] [SIZE] [SYMBOL] - 全体約定履歴取得（全ユーザー）"
    echo "  $0 volume [SYMBOL] [FROM_TIME] [TO_TIME] - 約定量計算"
    echo "  $0 debug           - デバッグ情報取得"
    echo "  $0 db-info         - データベース情報とタイムゾーン確認"
    echo "  $0 trade-insert [SYMBOL] [PRICE] [QUANTITY] [SIDE] - トレード挿入"
    echo "  $0 full-test       - フルテスト実行"
    ;;
esac
