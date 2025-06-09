# Exchange Simulator (exch_sim)

金融取引所システムのシミュレーターです。注文の発注・取消、約定処理、板情報取得などの機能を提供します。

## 機能概要

- **注文管理**: 指値注文・成行注文の発注と取消
- **約定処理**: リアルタイムでの注文マッチング
- **板情報取得**: 買い注文・売り注文の価格・数量情報
- **約定結果配信**: ユーザー毎の約定結果ポーリング
- **JWT認証**: セキュアなAPIアクセス

## 技術スタック

- **Java**: 17+
- **Spring Boot**: 3.x
- **Spring Security**: JWT認証
- **Gradle**: ビルドツール
- **JUnit 5**: テストフレームワーク

## API仕様

### 認証

すべてのAPIエンドポイント（板情報取得を除く）はJWT認証が必要です。

### 1. 注文管理 API

#### 新規注文

**POST** `/api/orders/new`

新しい注文を発注します。

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body:**
```json
{
  "symbol": "BTCJPY",
  "price": 100.5,
  "quantity": 10,
  "side": "BUY",
  "ordType": "LIMIT",
  "tif": "GTC"
}
```

**Request Fields:**
- `symbol` (string, required): 取引ペア（例: BTCJPY, ETHJPY）
- `price` (number, required): 注文価格
- `quantity` (number, required): 注文数量
- `side` (string, required): 売買区分
  - `BUY`: 買い注文
  - `SELL`: 売り注文
- `ordType` (string, required): 注文タイプ
  - `LIMIT`: 指値注文
  - `MARKET`: 成行注文
- `tif` (string, required): 注文有効期限
  - `GTC`: Good Till Cancel（取消まで有効）
  - `IOC`: Immediate Or Cancel（即時実行または取消）
  - `FOK`: Fill Or Kill（全量実行または取消）

**Response:**
```json
{
  "clOrdID": "uuid-string",
  "status": "NEW",
  "executions": [
    {
      "execID": "exec-uuid",
      "execStatus": "NEW",
      "lastPx": 0.0,
      "lastQty": 0
    }
  ]
}
```

**Response Codes:**
- `200 OK`: 注文処理成功
- `400 Bad Request`: 無効な注文パラメータ
- `401 Unauthorized`: 認証が必要
- `404 Not Found`: ユーザーが見つからない
- `500 Internal Server Error`: サーバーエラー

#### 注文取消

**POST** `/api/orders/cancel`

既存の注文を取り消します。

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body:**
```json
{
  "clOrdID": "uuid-string",
  "symbol": "BTCJPY"
}
```

**Request Fields:**
- `clOrdID` (string, required): 取消対象の注文ID
- `symbol` (string, required): 取引ペア

**Response:**
```json
{
  "clOrdID": "uuid-string",
  "status": "CANCELED",
  "executions": [
    {
      "execID": "exec-uuid",
      "execStatus": "CANCELED",
      "lastPx": 100.5,
      "lastQty": 10
    }
  ]
}
```

### 2. 約定結果ポーリング API

#### 約定結果取得

**GET** `/api/executions/poll?maxCount=10`

ユーザーの約定結果を取得します。

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters:**
- `maxCount` (integer, optional): 最大取得件数（デフォルト: 10）

**Response:**
```json
{
  "username": "user1",
  "executionCount": 2,
  "executions": [
    {
      "execID": "exec-uuid-1",
      "clOrdID": "order-uuid-1",
      "symbol": "BTCJPY",
      "execStatus": "FILLED",
      "lastPx": 100.5,
      "lastQty": 10,
      "counterPartyUsername": "user2",
      "side": "BUY"
    },
    {
      "execID": "exec-uuid-2",
      "clOrdID": "order-uuid-2",
      "symbol": "BTCJPY",
      "execStatus": "PARTIAL_FILL",
      "lastPx": 100.0,
      "lastQty": 5,
      "counterPartyUsername": "user3",
      "side": "SELL"
    }
  ]
}
```

**Response Codes:**
- `200 OK`: 取得成功
- `401 Unauthorized`: 認証が必要
- `404 Not Found`: ユーザーが見つからない
- `500 Internal Server Error`: サーバーエラー

#### 約定結果キューサイズ取得

**GET** `/api/executions/queue-size`

ユーザーの約定結果キューの件数を取得します。

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
```

**Response:**
```json
{
  "username": "user1",
  "queueSize": 3
}
```

### 3. 板情報取得 API

#### 板情報取得

**GET** `/api/market/board/{symbol}?depth=10`

指定された取引ペアの板情報を取得します。

**Path Parameters:**
- `symbol` (string, required): 取引ペア（例: BTCJPY）

**Query Parameters:**
- `depth` (integer, optional): 板の深度（1-100、デフォルト: 10）

**Response:**
```json
{
  "symbol": "BTCJPY",
  "bids": [
    {
      "price": 100.0,
      "quantity": 10
    },
    {
      "price": 99.5,
      "quantity": 15
    }
  ],
  "asks": [
    {
      "price": 100.5,
      "quantity": 8
    },
    {
      "price": 101.0,
      "quantity": 12
    }
  ]
}
```

**Response Fields:**
- `symbol`: 取引ペア
- `bids`: 買い注文リスト（価格降順）
- `asks`: 売り注文リスト（価格昇順）
- `price`: 価格
- `quantity`: 数量

**Response Codes:**
- `200 OK`: 取得成功
- `400 Bad Request`: 無効なパラメータ
- `500 Internal Server Error`: サーバーエラー

#### 簡易板情報取得

**GET** `/api/market/board/{symbol}/simple`

指定された取引ペアの簡易板情報（深度5）を取得します。

**Path Parameters:**
- `symbol` (string, required): 取引ペア

**Response:**
板情報取得APIと同じ形式で、深度5の情報を返します。

## 使用例

### 1. 買い注文の発注

```bash
curl -X POST http://localhost:8080/api/orders/new \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCJPY",
    "price": 100.0,
    "quantity": 10,
    "side": "BUY",
    "ordType": "LIMIT",
    "tif": "GTC"
  }'
```

### 2. 約定結果の確認

```bash
curl -X GET "http://localhost:8080/api/executions/poll?maxCount=5" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 3. 板情報の取得

```bash
curl -X GET "http://localhost:8080/api/market/board/BTCJPY?depth=10"
```

## エラーレスポンス

エラーが発生した場合、以下の形式でレスポンスが返されます：

```json
{
  "error": "エラーメッセージ"
}
```

または文字列メッセージ：

```
"Authentication required"
```

## 約定ステータス

- `NEW`: 新規注文（板に追加済み）
- `PARTIAL_FILL`: 部分約定
- `FILLED`: 全量約定
- `CANCELED`: 取消済み
- `REJECTED`: 拒否

## ビルドと実行

### 前提条件

- Java 17以上
- Gradle 8.x以上

### ビルド

```bash
./gradlew build
```

### テスト実行

```bash
./gradlew test
```

### アプリケーション起動

```bash
./gradlew bootRun
```

アプリケーションは `http://localhost:8080` で起動します。

## 開発

### テストカバレッジ

主要なテストケース：
- 注文処理ロジック
- 約定マッチング
- 板情報管理
- 約定結果配信
- エラーハンドリング

### アーキテクチャ

```
src/main/java/com/ys/exch_sim/
├── domain/
│   ├── controller/          # REST APIエンドポイント
│   ├── service/             # ビジネスロジック
│   ├── dto/                 # データ転送オブジェクト
│   ├── market_board/        # 板管理
│   ├── order_exec/          # 注文・約定管理
│   └── message/             # メッセージフィールド
├── security/                # 認証・認可
└── infra/                   # インフラストラクチャ
```

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。

---

# Exchange Simulator (exch_sim) - English Version

A financial exchange system simulator that provides order placement/cancellation, execution processing, order book information retrieval, and other features.

## Features

- **Order Management**: Place and cancel limit/market orders
- **Execution Processing**: Real-time order matching
- **Order Book Information**: Price and quantity information for buy/sell orders
- **Execution Result Distribution**: Per-user execution result polling
- **JWT Authentication**: Secure API access

## Technology Stack

- **Java**: 17+
- **Spring Boot**: 3.x
- **Spring Security**: JWT authentication
- **Gradle**: Build tool
- **JUnit 5**: Testing framework

## API Specification

### Authentication

All API endpoints (except order book retrieval) require JWT authentication.

### 1. Order Management API

#### New Order

**POST** `/api/orders/new`

Place a new order.

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body:**
```json
{
  "symbol": "BTCJPY",
  "price": 100.5,
  "quantity": 10,
  "side": "BUY",
  "ordType": "LIMIT",
  "tif": "GTC"
}
```

**Request Fields:**
- `symbol` (string, required): Trading pair (e.g., BTCJPY, ETHJPY)
- `price` (number, required): Order price
- `quantity` (number, required): Order quantity
- `side` (string, required): Buy/sell side
  - `BUY`: Buy order
  - `SELL`: Sell order
- `ordType` (string, required): Order type
  - `LIMIT`: Limit order
  - `MARKET`: Market order
- `tif` (string, required): Time in force
  - `GTC`: Good Till Cancel
  - `IOC`: Immediate Or Cancel
  - `FOK`: Fill Or Kill

**Response:**
```json
{
  "clOrdID": "uuid-string",
  "status": "NEW",
  "executions": [
    {
      "execID": "exec-uuid",
      "execStatus": "NEW",
      "lastPx": 0.0,
      "lastQty": 0
    }
  ]
}
```

**Response Codes:**
- `200 OK`: Order processed successfully
- `400 Bad Request`: Invalid order parameters
- `401 Unauthorized`: Authentication required
- `404 Not Found`: User not found
- `500 Internal Server Error`: Server error

#### Cancel Order

**POST** `/api/orders/cancel`

Cancel an existing order.

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body:**
```json
{
  "clOrdID": "uuid-string",
  "symbol": "BTCJPY"
}
```

**Request Fields:**
- `clOrdID` (string, required): Order ID to cancel
- `symbol` (string, required): Trading pair

**Response:**
```json
{
  "clOrdID": "uuid-string",
  "status": "CANCELED",
  "executions": [
    {
      "execID": "exec-uuid",
      "execStatus": "CANCELED",
      "lastPx": 100.5,
      "lastQty": 10
    }
  ]
}
```

### 2. Execution Polling API

#### Get Execution Results

**GET** `/api/executions/poll?maxCount=10`

Retrieve user's execution results.

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters:**
- `maxCount` (integer, optional): Maximum number of results to retrieve (default: 10)

**Response:**
```json
{
  "username": "user1",
  "executionCount": 2,
  "executions": [
    {
      "execID": "exec-uuid-1",
      "clOrdID": "order-uuid-1",
      "symbol": "BTCJPY",
      "execStatus": "FILLED",
      "lastPx": 100.5,
      "lastQty": 10,
      "counterPartyUsername": "user2",
      "side": "BUY"
    },
    {
      "execID": "exec-uuid-2",
      "clOrdID": "order-uuid-2",
      "symbol": "BTCJPY",
      "execStatus": "PARTIAL_FILL",
      "lastPx": 100.0,
      "lastQty": 5,
      "counterPartyUsername": "user3",
      "side": "SELL"
    }
  ]
}
```

**Response Codes:**
- `200 OK`: Retrieved successfully
- `401 Unauthorized`: Authentication required
- `404 Not Found`: User not found
- `500 Internal Server Error`: Server error

#### Get Execution Queue Size

**GET** `/api/executions/queue-size`

Get the size of user's execution result queue.

**Headers:**
```
Authorization: Bearer <JWT_TOKEN>
```

**Response:**
```json
{
  "username": "user1",
  "queueSize": 3
}
```

### 3. Market Data API

#### Get Order Book

**GET** `/api/market/board/{symbol}?depth=10`

Retrieve order book information for the specified trading pair.

**Path Parameters:**
- `symbol` (string, required): Trading pair (e.g., BTCJPY)

**Query Parameters:**
- `depth` (integer, optional): Order book depth (1-100, default: 10)

**Response:**
```json
{
  "symbol": "BTCJPY",
  "bids": [
    {
      "price": 100.0,
      "quantity": 10
    },
    {
      "price": 99.5,
      "quantity": 15
    }
  ],
  "asks": [
    {
      "price": 100.5,
      "quantity": 8
    },
    {
      "price": 101.0,
      "quantity": 12
    }
  ]
}
```

**Response Fields:**
- `symbol`: Trading pair
- `bids`: Buy orders list (price descending order)
- `asks`: Sell orders list (price ascending order)
- `price`: Price
- `quantity`: Quantity

**Response Codes:**
- `200 OK`: Retrieved successfully
- `400 Bad Request`: Invalid parameters
- `500 Internal Server Error`: Server error

#### Get Simple Order Book

**GET** `/api/market/board/{symbol}/simple`

Retrieve simple order book information (depth 5) for the specified trading pair.

**Path Parameters:**
- `symbol` (string, required): Trading pair

**Response:**
Same format as the order book API, returning depth 5 information.

## Usage Examples

### 1. Place a Buy Order

```bash
curl -X POST http://localhost:8080/api/orders/new \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCJPY",
    "price": 100.0,
    "quantity": 10,
    "side": "BUY",
    "ordType": "LIMIT",
    "tif": "GTC"
  }'
```

### 2. Check Execution Results

```bash
curl -X GET "http://localhost:8080/api/executions/poll?maxCount=5" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 3. Get Order Book Information

```bash
curl -X GET "http://localhost:8080/api/market/board/BTCJPY?depth=10"
```

## Error Responses

When an error occurs, responses are returned in the following format:

```json
{
  "error": "Error message"
}
```

Or as a string message:

```
"Authentication required"
```

## Execution Status

- `NEW`: New order (added to order book)
- `PARTIAL_FILL`: Partially filled
- `FILLED`: Fully filled
- `CANCELED`: Canceled
- `REJECTED`: Rejected

## Build and Run

### Prerequisites

- Java 17 or higher
- Gradle 8.x or higher

### Build

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Start Application

```bash
./gradlew bootRun
```

The application starts at `http://localhost:8080`.

## Development

### Test Coverage

Main test cases:
- Order processing logic
- Execution matching
- Order book management
- Execution result distribution
- Error handling

### Architecture

```
src/main/java/com/ys/exch_sim/
├── domain/
│   ├── controller/          # REST API endpoints
│   ├── service/             # Business logic
│   ├── dto/                 # Data transfer objects
│   ├── market_board/        # Order book management
│   ├── order_exec/          # Order/execution management
│   └── message/             # Message fields
├── security/                # Authentication/authorization
└── infra/                   # Infrastructure
```

## License

This project is released under the MIT License. 