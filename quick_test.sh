#!/bin/bash

# クイックテスト - デバッグ用
#BASE_URL="https://exch-sim-953974838707.asia-northeast1.run.app"
BASE_URL="http://localhost:8080"
USERNAME="yukio004"
PASSWORD="yukio004"
JWT_CACHE_FILE="/tmp/quick_test_jwt_token"

# JWT有効性チェック関数
check_jwt_validity() {
  local token="$1"
  if [ -z "$token" ] || [ "$token" = "null" ]; then
    return 1
  fi
  
  # JWTの有効性をシンプルなテストで確認
  local test_response=$(curl -s -o /dev/null -w "%{http_code}" -X GET "${BASE_URL}/actuator/health" \
    -H "Authorization: Bearer ${token}")
  
  # actuator/healthは認証不要なので、別の方法でJWTをテスト
  # 代わりにJWTの期限をローカルでチェック
  local jwt_payload=$(echo "$token" | cut -d'.' -f2)
  # Base64パディングを追加
  local padded_payload="${jwt_payload}$(printf '%*s' $(((4 - ${#jwt_payload} % 4) % 4)) '' | tr ' ' '=')"
  
  # JWTの有効期限をチェック（簡易版）
  if command -v base64 &> /dev/null && command -v date &> /dev/null; then
    local decoded=$(echo "$padded_payload" | base64 -d 2>/dev/null)
    if [[ $? -eq 0 ]]; then
      # JWTが正しい形式なら有効とみなす（簡易チェック）
      return 0
    fi
  fi
  
  # フォールバック: 常に新しいトークンを取得
  return 1
}

# JWT取得またはキャッシュから読み込み
get_jwt_token() {
  local cached_token=""

  # キャッシュファイルから既存トークンを読み込み
  if [ -f "$JWT_CACHE_FILE" ]; then
    cached_token=$(cat "$JWT_CACHE_FILE" 2>/dev/null)
    echo "🔍 キャッシュされたトークンをチェック中..." >&2

    if check_jwt_validity "$cached_token"; then
      echo "✅ キャッシュされたトークンが有効です" >&2
      echo "$cached_token"
      return 0
    else
      echo "⚠️ キャッシュされたトークンが無効です。新しいトークンを取得します。" >&2
      rm -f "$JWT_CACHE_FILE"
    fi
  fi

  # 新しいトークンを取得
  echo "🔐 ログイン中..." >&2
  local login_response=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"${USERNAME}\", \"password\": \"${PASSWORD}\"}")

  echo "Login response: $login_response" >&2

  local jwt_token=$(echo "$login_response" | jq -r '.token')

  if [ "$jwt_token" = "null" ] || [ -z "$jwt_token" ]; then
    echo "❌ ログイン失敗" >&2
    return 1
  fi

  # トークンをキャッシュファイルに保存
  echo "$jwt_token" > "$JWT_CACHE_FILE"
  echo "✅ ログイン成功 - トークンをキャッシュしました" >&2
  echo "JWT: ${jwt_token}" >&2
  echo "$jwt_token"
  return 0
}

# JWTトークン取得
JWT_TOKEN=$(get_jwt_token)
if [ $? -ne 0 ]; then
  echo "❌ JWT取得に失敗しました"
  exit 1
fi

echo "🔑 Using JWT Token: ${JWT_TOKEN:0:50}..." # Show first 50 chars

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
    RESPONSE=$(curl -s -X GET "${BASE_URL}/api/executions/queue-size" \
      -H "Authorization: Bearer ${JWT_TOKEN}")
    echo "Raw response: $RESPONSE"
    echo "Formatted response:"
    echo "$RESPONSE" | jq '.' 2>/dev/null || echo "❌ Invalid JSON response"
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
    FROM_TIME=${3:-"2025-07-23T06:00:00"}
    TO_TIME=${4:-"2025-07-25T18:59:59"}
    echo "📊 約定量計算 (symbol: ${SYMBOL}, from: ${FROM_TIME}, to: ${TO_TIME})..."
    curl -s -X GET "${BASE_URL}/api/executions/volume?symbol=${SYMBOL}&fromTime=${FROM_TIME}&toTime=${TO_TIME}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "position-summary")
    echo "📊 ポートフォリオサマリー取得..."
    # JWT トークンのデバッグ出力
    echo "Debug - JWT Token length: ${#JWT_TOKEN}"
    echo "Debug - JWT Token (first 50 chars): ${JWT_TOKEN:0:50}"

    RESPONSE=$(curl -s -X GET "${BASE_URL}/api/positions/summary" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" \
      -w "\nHTTP_CODE:%{http_code}")

    HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d':' -f2)
    BODY=$(echo "$RESPONSE" | sed '$d')

    echo "HTTP Status Code: $HTTP_CODE"
    echo "Raw response: $BODY"
    echo "Formatted response:"
    echo "$BODY" | jq '.' 2>/dev/null || echo "❌ Invalid JSON response"
    ;;
  "position")
    SYMBOL=${2:-"B_FX_BTCJPY"}
    echo "📈 銘柄別ポジション取得 (symbol: ${SYMBOL})..."
    curl -s -X GET "${BASE_URL}/api/positions/${SYMBOL}" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    ;;
  "trade-history")
    LIMIT=${2:-"20"}
    SYMBOL=${3}
    echo "📜 取引履歴取得 (limit: ${LIMIT}, symbol: ${SYMBOL})..."
    if [ -n "$SYMBOL" ]; then
      curl -s -X GET "${BASE_URL}/api/positions/trades?limit=${LIMIT}&symbol=${SYMBOL}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    else
      curl -s -X GET "${BASE_URL}/api/positions/trades?limit=${LIMIT}" \
        -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
    fi
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
  "position-test")
    echo "📊 ポジション関連テスト実行..."
    echo "1️⃣ ポートフォリオサマリー:"
    curl -s -X GET "${BASE_URL}/api/positions/summary" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'

    echo "2️⃣ B_FX_BTCJPYのポジション:"
    curl -s -X GET "${BASE_URL}/api/positions/B_FX_BTCJPY" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'

    echo "3️⃣ 取引履歴（最新10件）:"
    curl -s -X GET "${BASE_URL}/api/positions/trades?limit=10" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JWT_TOKEN}" | jq '.'
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
    echo "  $0 position-summary - ポートフォリオサマリー取得"
    echo "  $0 position [SYMBOL] - 銘柄別ポジション取得"
    echo "  $0 trade-history [LIMIT] [SYMBOL] - 取引履歴取得"
    echo "  $0 debug           - デバッグ情報取得"
    echo "  $0 db-info         - データベース情報とタイムゾーン確認"
    echo "  $0 trade-insert [SYMBOL] [PRICE] [QUANTITY] [SIDE] - トレード挿入"
    echo "  $0 full-test       - フルテスト実行"
    echo "  $0 position-test   - ポジション関連テスト実行"
    ;;
esac
