# Exchange Simulator (exch_sim)

金融取引所システムのシミュレーターです。注文の発注・取消、約定処理、板情報取得、ポジション管理、MarketMaker機能などを提供します。

## 機能概要

- **注文管理**: 指値注文・成行注文の発注と取消
- **約定処理**: リアルタイムでの注文マッチング
- **板情報取得**: 買い注文・売り注文の価格・数量情報
- **約定結果配信**: ユーザー毎の約定結果ポーリング
- **ポジション管理**: 取引履歴・損益計算・ポートフォリオ管理
- **MarketMaker機能**: MARKET_MAKER専用の一括注文機能
- **商品タイプ管理**: Cash（現物）とFX（先物）の取引制限
- **JWT認証**: セキュアなAPIアクセス
- **権限ベースアクセス制御**: 役割別API制限

## 技術スタック

- **Java**: 17+
- **Spring Boot**: 3.x
- **Spring Security**: JWT認証・権限管理
- **Spring AOP**: 権限チェック
- **Gradle**: ビルドツール
- **JUnit 5**: テストフレームワーク

## 商品タイプと取引制限

### Cash（現物）商品
- **空売り禁止**: 保有ポジション以上の売り注文は拒否
- **例**: G_BTCJPY, B_BTCJPY, TESTJPY

### FX（先物）商品
- **自由取引**: 売り・買いどちらからでも取引開始可能
- **例**: G_FX_BTCJPY, B_FX_BTCJPY, G_ETHJPY

## ユーザー権限

### ROLE_USER
- 基本的な注文・取引機能
- ポジション確認・取引履歴

### ROLE_MARKET_MAKER
- USER権限に加えて
- MarketMake一括注文機能
- 既存注文の一括キャンセル・再投入

## API仕様

### 認証

#### ユーザー登録
**POST** `/api/auth/signup`

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "username": "trader001",
    "password": "SecurePass123"
  }'
```

#### ログイン
**POST** `/api/auth/login`

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "trader001",
    "password": "SecurePass123"
  }'
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

### 1. 注文管理 API

#### 新規注文
**POST** `/api/orders/new`

```bash
curl -X POST http://localhost:8080/api/orders/new \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "G_FX_BTCJPY",
    "price": 5000000,
    "quantity": 1,
    "side": "BUY",
    "ordType": "LIMIT",
    "tif": "GTC"
  }'
```

**Request Fields:**
- `symbol` (string, required): 取引ペア
- `price` (number, required): 注文価格
- `quantity` (number, required): 注文数量
- `side` (string, required): 売買区分（BUY/SELL）
- `ordType` (string, required): 注文タイプ（LIMIT/MARKET）
- `tif` (string, required): 注文有効期限（GTC/IOC/FOK）

**Cash商品の制限:**
- 売り注文時は保有ポジションをチェック
- 不足時は`Insufficient position for cash sale`エラー

#### 注文取消
**POST** `/api/orders/cancel`

```bash
curl -X POST http://localhost:8080/api/orders/cancel \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "clOrdID": "order-uuid-string",
    "symbol": "G_FX_BTCJPY"
  }'
```

### 2. 約定結果ポーリング API

#### 約定結果取得
**GET** `/api/executions/poll?maxCount=10`

```bash
curl -X GET "http://localhost:8080/api/executions/poll?maxCount=5" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

#### 約定結果キューサイズ取得
**GET** `/api/executions/queue-size`

```bash
curl -X GET "http://localhost:8080/api/executions/queue-size" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 3. 板情報取得 API

#### 板情報取得
**GET** `/api/market/board/{symbol}?depth=10`

```bash
curl -X GET "http://localhost:8080/api/market/board/G_FX_BTCJPY?depth=10"
```

#### 簡易板情報取得
**GET** `/api/market/board/{symbol}/simple`

```bash
curl -X GET "http://localhost:8080/api/market/board/G_FX_BTCJPY/simple"
```

### 4. ポジション管理 API

#### ポートフォリオサマリー取得
**GET** `/api/positions/summary`

```bash
curl -X GET "http://localhost:8080/api/positions/summary" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**Response:**
```json
{
  "username": "trader001",
  "totalRealizedPnL": 1500.0,
  "totalUnrealizedPnL": -200.0,
  "totalPnL": 1300.0,
  "totalTradeCount": 15,
  "totalTradingVolume": 50000000.0,
  "positions": [
    {
      "symbol": "G_FX_BTCJPY",
      "netQty": 5,
      "averageBuyPrice": 4950000.0,
      "realizedPnL": 500.0,
      "unrealizedPnL": -200.0,
      "totalPnL": 300.0
    }
  ],
  "symbolTradeCounts": {
    "G_FX_BTCJPY": 10,
    "G_ETHJPY": 5
  }
}
```

#### 銘柄別ポジション取得
**GET** `/api/positions/{symbol}`

```bash
curl -X GET "http://localhost:8080/api/positions/G_FX_BTCJPY" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

#### 取引履歴取得
**GET** `/api/positions/trades?limit=50&symbol=G_FX_BTCJPY`

```bash
curl -X GET "http://localhost:8080/api/positions/trades?limit=20&symbol=G_FX_BTCJPY" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 5. MarketMaker API (MARKET_MAKER権限必要)

#### 一括注文投入
**POST** `/api/market-make/orders`

```bash
curl -X POST http://localhost:8080/api/market-make/orders \
  -H "Authorization: Bearer <MARKET_MAKER_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "G_FX_BTCJPY",
    "bidLevels": [
      {"price": 4995000, "quantity": 1},
      {"price": 4990000, "quantity": 2},
      {"price": 4985000, "quantity": 3}
    ],
    "askLevels": [
      {"price": 5005000, "quantity": 1},
      {"price": 5010000, "quantity": 2},
      {"price": 5015000, "quantity": 3}
    ]
  }'
```

**Response:**
```json
{
  "username": "marketmaker1",
  "symbol": "G_FX_BTCJPY",
  "cancelledOrdersCount": 6,
  "newBidOrdersCount": 3,
  "newAskOrdersCount": 3,
  "bidOrderIds": ["bid-order-1", "bid-order-2", "bid-order-3"],
  "askOrderIds": ["ask-order-1", "ask-order-2", "ask-order-3"],
  "status": "SUCCESS",
  "message": "Market make orders processed successfully"
}
```

#### 注文一括キャンセル
**DELETE** `/api/market-make/orders/{symbol}`

```bash
curl -X DELETE http://localhost:8080/api/market-make/orders/G_FX_BTCJPY \
  -H "Authorization: Bearer <MARKET_MAKER_JWT_TOKEN>"
```

#### MarketMake注文状況確認
**GET** `/api/market-make/orders/{symbol}/status`

```bash
curl -X GET http://localhost:8080/api/market-make/orders/G_FX_BTCJPY/status \
  -H "Authorization: Bearer <MARKET_MAKER_JWT_TOKEN>"
```

## 設定されている商品

| 商品名 | タイプ | 説明 | 価格精度 | 数量精度 |
|--------|--------|------|----------|----------|
| G_BTCJPY | Cash | 現物ビットコイン | 100 | 1 |
| G_FX_BTCJPY | FX | ビットコイン先物 | 100 | 1 |
| B_BTCJPY | Cash | 現物ビットコイン | 100 | 1 |
| B_FX_BTCJPY | FX | ビットコイン先物 | 100 | 1 |
| G_ETHJPY | FX | イーサリアム先物 | 100 | 1 |
| TESTJPY | Cash | テスト用現物 | 100 | 1 |

## ユーザーデータ管理

ユーザー情報は `./users.json` ファイルに保存されます：

```json
{
  "users": [
    {
      "username": "trader001",
      "password": "$2a$10$...",
      "roles": ["ROLE_USER"]
    },
    {
      "username": "marketmaker1",
      "password": "$2a$10$...",
      "roles": ["ROLE_USER", "ROLE_MARKET_MAKER"]
    }
  ]
}
```

## 主要機能の特徴

### ポジション管理
- **自動計算**: 約定時にポジション・損益を自動更新
- **実現損益**: 売買確定時の損益
- **未実現損益**: 現在価格での含み損益
- **取引履歴**: 全約定の詳細記録

### MarketMaker機能
- **アトミック処理**: 既存注文キャンセル→新規注文を不可分で実行
- **シングルスレッド**: 銘柄別ロックでMarketBoardの整合性保証
- **一括管理**: 同一ユーザー・銘柄の注文を効率的に管理

### 商品タイプ制御
- **Cash**: 保有ポジション以上の売り注文を自動拒否
- **FX**: 制限なし、自由な売買が可能

## エラーレスポンス

### 権限エラー
```json
{
  "error": "MARKET_MAKER role required"
}
```

### ポジション不足エラー
```json
{
  "error": "Insufficient position for cash sale. Available: 0, Requested: 10"
}
```

### 無効商品エラー
```json
{
  "error": "Invalid symbol: INVALID_SYMBOL"
}
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
- ポジション管理
- MarketMaker機能
- 権限制御
- Cash/FX取引制限
- エラーハンドリング

### アーキテクチャ

```
src/main/java/com/ys/exch_sim/
├── domain/
│   ├── controller/          # REST APIエンドポイント
│   ├── service/             # ビジネスロジック
│   ├── dto/                 # データ転送オブジェクト
│   ├── config/              # 設定管理
│   ├── market_board/        # 板管理
│   ├── order_exec/          # 注文・約定管理
│   ├── position/            # ポジション・損益管理
│   └── message/             # メッセージフィールド
├── security/                # 認証・認可・権限制御
└── infra/                   # インフラストラクチャ
```

## 使用例シナリオ

### 1. 一般トレーダーの取引
```bash
# 1. ユーザー登録
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username": "trader001", "password": "pass123"}'

# 2. ログイン
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "trader001", "password": "pass123"}'

# 3. FX商品で買い注文
curl -X POST http://localhost:8080/api/orders/new \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"symbol": "G_FX_BTCJPY", "price": 5000000, "quantity": 1, "side": "BUY", "ordType": "LIMIT", "tif": "GTC"}'

# 4. ポジション確認
curl -X GET http://localhost:8080/api/positions/summary \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 2. MarketMakerの流動性提供
```bash
# 1. MarketMaker権限でログイン
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "marketmaker1", "password": "mmpass123"}'

# 2. 一括注文投入（既存注文を自動キャンセル）
curl -X POST http://localhost:8080/api/market-make/orders \
  -H "Authorization: Bearer <MARKET_MAKER_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "G_FX_BTCJPY",
    "bidLevels": [
      {"price": 4995000, "quantity": 1},
      {"price": 4990000, "quantity": 2}
    ],
    "askLevels": [
      {"price": 5005000, "quantity": 1},
      {"price": 5010000, "quantity": 2}
    ]
  }'
```

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。

---

# Exchange Simulator (exch_sim) - English Version

A comprehensive financial exchange system simulator that provides order placement/cancellation, execution processing, position management, and market making capabilities.

## Features

- **Order Management**: Place and cancel limit/market orders
- **Execution Processing**: Real-time order matching
- **Order Book Information**: Price and quantity information for buy/sell orders
- **Execution Result Distribution**: Per-user execution result polling
- **Position Management**: Trade history, P&L calculation, and portfolio management
- **Market Making**: MARKET_MAKER exclusive bulk order functionality
- **Instrument Type Management**: Cash (spot) and FX (futures) trading restrictions
- **JWT Authentication**: Secure API access
- **Role-based Access Control**: API restrictions by user roles

## Technology Stack

- **Java**: 17+
- **Spring Boot**: 3.x
- **Spring Security**: JWT authentication & authorization
- **Spring AOP**: Permission checking
- **Gradle**: Build tool
- **JUnit 5**: Testing framework

## Instrument Types and Trading Restrictions

### Cash (Spot) Instruments
- **Short Selling Prohibited**: Sell orders exceeding held positions are rejected
- **Examples**: G_BTCJPY, B_BTCJPY, TESTJPY

### FX (Futures) Instruments
- **Free Trading**: Trading can start from either buy or sell side
- **Examples**: G_FX_BTCJPY, B_FX_BTCJPY, G_ETHJPY

## User Roles

### ROLE_USER
- Basic order and trading functions
- Position checking and trade history

### ROLE_MARKET_MAKER
- All USER permissions plus:
- Market make bulk order functionality
- Bulk cancellation and re-placement of existing orders

## Key Features

### Position Management
- **Automatic Calculation**: Positions and P&L automatically updated on execution
- **Realized P&L**: Profit/loss from completed trades
- **Unrealized P&L**: Mark-to-market P&L based on current prices
- **Trade History**: Detailed record of all executions

### Market Making Functionality
- **Atomic Processing**: Cancel existing orders → place new orders atomically
- **Single-threaded**: Symbol-level locking ensures MarketBoard consistency
- **Bulk Management**: Efficient management of orders by user and symbol

### Instrument Type Control
- **Cash**: Automatically rejects sell orders exceeding held positions
- **FX**: No restrictions, free buying and selling

## Available Instruments

| Symbol | Type | Description | Price Precision | Quantity Precision |
|--------|------|-------------|-----------------|-------------------|
| G_BTCJPY | Cash | Bitcoin Spot | 100 | 1 |
| G_FX_BTCJPY | FX | Bitcoin Futures | 100 | 1 |
| B_BTCJPY | Cash | Bitcoin Spot | 100 | 1 |
| B_FX_BTCJPY | FX | Bitcoin Futures | 100 | 1 |
| G_ETHJPY | FX | Ethereum Futures | 100 | 1 |
| TESTJPY | Cash | Test Spot | 100 | 1 |

## License

This project is released under the MIT License.