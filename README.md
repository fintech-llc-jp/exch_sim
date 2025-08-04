# Exchange Simulator (exch_sim)

金融取引所システムのシミュレーターです。注文の発注・取消、約定処理、板情報取得、ポジション管理、MarketMaker機能などを提供します。

## 機能概要

- **注文管理**: 指値注文・成行注文の発注と取消
- **約定処理**: リアルタイムでの注文マッチング
- **板情報取得**: 買い注文・売り注文の価格・数量情報
- **約定結果配信**: ユーザー毎の約定結果ポーリング
- **約定履歴管理**: ページネーション付き約定履歴取得・H2データベース永続化
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
- **H2 Database**: 約定履歴永続化
- **Spring Data JPA**: データベースアクセス
- **Gradle**: ビルドツール
- **JUnit 5**: テストフレームワーク

## 商品タイプと取引制限

### Cash（現物）商品
- **空売り禁止**: 保有ポジション以上の売り注文は拒否
- **例**: G_BTCJPY, B_BTCJPY, TESTJPY

### FX（先物）商品
- **自由取引**: 売り・買いどちらからでも取引開始可能
- **例**: G_FX_BTCJPY, B_FX_BTCJPY

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

**Request Fields:**
- `username` (string, required): ユーザー名（3-50文字）
- `password` (string, required): パスワード（6文字以上）

**Response:**
```json
{
  "message": "User registered successfully",
  "username": "trader001"
}
```

**Validation Rules:**
- ユーザー名は3-50文字の間である必要があります
- パスワードは6文字以上である必要があります
- 既に存在するユーザー名は使用できません
- ユーザーは自動的に "USER" ロールが付与されます

**Error Responses:**
- `400 Bad Request`: バリデーションエラー
- `500 Internal Server Error`: BigQuery接続エラー

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

### 2. 約定結果取得 API

#### 約定履歴取得（ページネーション付き）- **推奨**
**GET** `/api/executions/history?page=0&size=20&symbol=B_FX_BTCJPY`

```bash
# 全銘柄の約定履歴（最新20件、FILLED/PARTIAL_FILLのみ）
curl -X GET "http://localhost:8080/api/executions/history?page=0&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 特定銘柄の約定履歴（最新10件）
curl -X GET "http://localhost:8080/api/executions/history?page=0&size=10&symbol=B_FX_BTCJPY" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 2ページ目（21-40件目）の約定履歴
curl -X GET "http://localhost:8080/api/executions/history?page=1&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**Query Parameters:**
- `page` (int, optional): ページ番号（0から開始）、デフォルト: 0
- `size` (int, optional): 1ページあたりの件数、デフォルト: 20
- `symbol` (string, optional): 銘柄フィルタ

**特徴:**
- ✅ **ページネーション対応**: 大量の約定履歴を効率的に取得
- ✅ **約定のみ表示**: `FILLED`と`PARTIAL_FILL`のみ（`NEW`は除外）
- ✅ **永続化**: H2データベースに保存された履歴データ
- ✅ **時系列ソート**: 最新の約定から降順で表示

#### 全体約定履歴取得（全ユーザー）
**GET** `/api/executions/all?page=0&size=20&symbol=B_FX_BTCJPY`

```bash
# 全ユーザーの約定履歴（最新20件、FILLED/PARTIAL_FILLのみ）
curl -X GET "http://localhost:8080/api/executions/all?page=0&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 特定銘柄の全ユーザー約定履歴（最新10件）
curl -X GET "http://localhost:8080/api/executions/all?page=0&size=10&symbol=B_FX_BTCJPY" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 2ページ目（21-40件目）の全ユーザー約定履歴
curl -X GET "http://localhost:8080/api/executions/all?page=1&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**Query Parameters:**
- `page` (int, optional): ページ番号（0から開始）、デフォルト: 0
- `size` (int, optional): 1ページあたりの件数、デフォルト: 20
- `symbol` (string, optional): 銘柄フィルタ

**特徴:**
- ✅ **全ユーザー対象**: システム全体の約定履歴を取得
- ✅ **ページネーション対応**: 大量の約定履歴を効率的に取得
- ✅ **約定のみ表示**: `FILLED`と`PARTIAL_FILL`のみ（`NEW`は除外）
- ✅ **永続化**: H2データベースに保存された履歴データ
- ✅ **時系列ソート**: 最新の約定から降順で表示

#### 約定量計算API
**GET** `/api/executions/volume?symbol=B_FX_BTCJPY&fromTime=2025-06-30T10:00:00&toTime=2025-06-30T12:00:00`

```bash
# 特定銘柄の約定量計算（2時間分）
curl -X GET "http://localhost:8080/api/executions/volume?symbol=B_FX_BTCJPY&fromTime=2025-06-30T10:00:00&toTime=2025-06-30T12:00:00" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 全銘柄の約定量計算（1日分）
curl -X GET "http://localhost:8080/api/executions/volume?symbol=ALL&fromTime=2025-06-30T00:00:00&toTime=2025-06-30T23:59:59" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# 今日の約定量計算（特定銘柄）
curl -X GET "http://localhost:8080/api/executions/volume?symbol=G_FX_BTCJPY&fromTime=2025-06-30T00:00:00&toTime=2025-06-30T23:59:59" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**Query Parameters:**
- `symbol` (string, required): 銘柄名（"ALL"で全銘柄対象）
- `fromTime` (string, required): 開始時刻（yyyy-MM-ddTHH:mm:ss形式、UTC時刻）
- `toTime` (string, required): 終了時刻（yyyy-MM-ddTHH:mm:ss形式、UTC時刻）

**特徴:**
- ✅ **時間範囲指定**: 任意の期間での約定量集計
- ✅ **銘柄フィルタ**: 特定銘柄または全銘柄対応
- ✅ **約定のみ対象**: `FILLED`と`PARTIAL_FILL`のみ（`NEW`は除外）
- ✅ **MarketMaker除外**: 一般ユーザーの取引のみ集計
- ✅ **統計情報**: 総約定量と約定回数を提供
- ✅ **UTC時刻**: 全ての時刻はUTC基準で処理

**Response:**
```json
{
  "symbol": "B_FX_BTCJPY",
  "fromTime": "2025-06-30T10:00:00",
  "toTime": "2025-06-30T12:00:00",
  "totalVolume": 1.5,
  "executionCount": 12,
  "timeRangeDescription": "From 2025-06-30 10:00:00 to 2025-06-30 12:00:00"
}
```

**Response:**
```json
{
  "username": "testuser",
  "page": 0,
  "size": 5,
  "totalPages": 2,
  "totalElements": 8,
  "executions": [
    {
      "execID": "23fc010f-9b72-47e7-9505-c65fc61a3826",
      "clOrdID": "e5f41070-82f3-4daa-9333-5c3944481ce5",
      "symbol": "B_FX_BTCJPY",
      "execStatus": "FILLED",
      "lastPx": 157641.77,
      "lastQty": 0.01,
      "counterPartyUsername": "marketmaker1",
      "side": "SELL",
      "createdAt": "2025-06-25T22:46:48.540225"
    }
  ]
}
```

#### 約定結果ポーリング（リアルタイム用）
**GET** `/api/executions/poll?maxCount=10`

```bash
curl -X GET "http://localhost:8080/api/executions/poll?maxCount=5" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**注意:** ポーリングは一度取得すると消費されるため、履歴表示には**約定履歴API**の使用を推奨します。

#### 約定結果キューサイズ取得
**GET** `/api/executions/queue-size`

```bash
curl -X GET "http://localhost:8080/api/executions/queue-size" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 3. 板情報取得 API

#### 板情報取得
**GET** `/api/market/board/{symbol}?depth=10`
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
    "G_FX_BTCJPY": 10
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

### 5. Trade Insert API

#### トレード挿入
**POST** `/api/trade/insert`

板情報をチェックしてマッチする注文があれば注文を発注・約定させ、ない場合は約定を直接挿入します。

```bash
# BUY注文の場合：ASK側の板をチェックしてマッチング
curl -X POST http://localhost:8080/api/trade/insert \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "B_FX_BTCJPY",
    "price": 15525000,
    "quantity": 0.02,
    "side": "BUY"
  }'

# SELL注文の場合：BID側の板をチェックしてマッチング
curl -X POST http://localhost:8080/api/trade/insert \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "B_FX_BTCJPY",
    "price": 15520000,
    "quantity": 0.01,
    "side": "SELL"
  }'
```

**Request Fields:**
- `symbol` (string, required): 取引ペア
- `price` (number, required): 取引価格
- `quantity` (number, required): 取引数量
- `side` (string, required): 売買区分（BUY/SELL）

**動作ロジック:**
1. **BUY注文の場合**: ASK側の板をチェックし、指定価格以下のASK注文があれば自動マッチング
2. **SELL注文の場合**: BID側の板をチェックし、指定価格以上のBID注文があれば自動マッチング
3. **マッチする注文がある場合**: IOC（Immediate or Cancel）注文を発注して自然にマッチング
4. **マッチする注文がない場合**: 約定を直接データベースに挿入

**Response（マッチング成功時）:**
```json
{
  "type": "ORDER_PLACED",
  "symbol": "B_FX_BTCJPY",
  "side": "BUY",
  "price": 15525000.0,
  "quantity": 0.02,
  "message": "Order placed and matched against existing orders",
  "executions": [
    {
      "execID": "abc-123-def",
      "execStatus": "FILLED",
      "lastPx": 15524893.0,
      "lastQty": 0.02
    }
  ]
}
```

**Response（直接挿入時）:**
```json
{
  "type": "EXECUTION_INSERTED",
  "symbol": "B_FX_BTCJPY",
  "side": "BUY",
  "price": 15525000.0,
  "quantity": 0.02,
  "message": "Execution inserted directly (no matching orders found)",
  "executions": [
    {
      "execID": "xyz-456-ghi",
      "execStatus": "FILLED",
      "lastPx": 15525000.0,
      "lastQty": 0.02
    }
  ]
}
```

**特徴:**
- ✅ **自動マッチング**: 既存の板注文と価格が合えば自動的に注文マッチング
- ✅ **直接挿入**: マッチしない場合は約定を直接作成・永続化
- ✅ **板情報更新**: マッチング時は実際の板数量が正しく更新される
- ✅ **IOC注文**: マッチング時はIOC（即座に約定または取消）で処理
- ✅ **認証必須**: JWT認証が必要

### 6. MarketMaker API (MARKET_MAKER権限必要)

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
| G_BTCJPY | Cash | 現物ビットコイン | 1 | 1000 |
| G_FX_BTCJPY | FX | ビットコイン先物 | 1 | 1000 |
| B_BTCJPY | Cash | 現物ビットコイン | 1 | 1000 |
| B_FX_BTCJPY | FX | ビットコイン先物 | 1 | 1000 |
| TESTJPY | Cash | テスト用現物 | 1 | 1000 |

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

## 開発・テストツール

### quick_test.sh - テストスクリプト

プロジェクトルートの `quick_test.sh` を使用して様々な機能をテストできます：

```bash
# 基本的な使用方法
./quick_test.sh [コマンド] [オプション]

# 利用可能なコマンド
./quick_test.sh market-buy                    # 成行買い注文
./quick_test.sh market-sell                   # 成行売り注文
./quick_test.sh limit-buy [PRICE]             # 指値買い注文
./quick_test.sh poll                          # 約定ポーリング
./quick_test.sh queue-size                    # 約定キューサイズ確認
./quick_test.sh board [SYMBOL]                # 板情報取得
./quick_test.sh history [PAGE] [SIZE] [SYMBOL] # 約定履歴取得（FILLED/PARTIAL_FILLのみ）
./quick_test.sh history-all [PAGE] [SIZE] [SYMBOL] # 全約定履歴取得（デバッグ用）
./quick_test.sh all-history [PAGE] [SIZE] [SYMBOL] # 全体約定履歴取得（全ユーザー）
./quick_test.sh volume [SYMBOL] [FROM_TIME] [TO_TIME] # 約定量計算
./quick_test.sh trade-insert [SYMBOL] [PRICE] [QUANTITY] [SIDE] # トレード挿入
./quick_test.sh full-test                     # フルテスト実行
```

**約定履歴テストの例:**
```bash
# 最新10件の約定履歴
./quick_test.sh history

# 最新5件の約定履歴
./quick_test.sh history 0 5

# B_FX_BTCJPYの最新3件
./quick_test.sh history 0 3 B_FX_BTCJPY

# 2ページ目（6-10件目）
./quick_test.sh history 1 5
```

**約定量計算テストの例:**
```bash
# 特定銘柄の今日の約定量
./quick_test.sh volume B_FX_BTCJPY 2025-06-30T00:00:00 2025-06-30T23:59:59

# 過去2時間の約定量
./quick_test.sh volume G_FX_BTCJPY 2025-06-30T10:00:00 2025-06-30T12:00:00

# 全銘柄の約定量（今日）
./quick_test.sh volume ALL 2025-06-30T00:00:00 2025-06-30T23:59:59

# デフォルトパラメータで実行
./quick_test.sh volume
```

**トレード挿入テストの例:**
```bash
# BUY注文でのトレード挿入（板マッチング確認）
./quick_test.sh trade-insert B_FX_BTCJPY 15525000 0.02 BUY

# SELL注文でのトレード挿入
./quick_test.sh trade-insert B_FX_BTCJPY 15520000 0.01 SELL

# 実行前後の板状態を比較表示
./quick_test.sh trade-insert G_FX_BTCJPY 5000000 0.1 BUY

# デフォルトパラメータで実行
./quick_test.sh trade-insert
```

### データベース管理

**H2データベース:**
- **ファイル**: `./data/executions.mv.db`
- **Console**: http://localhost:8080/h2-console
- **接続設定**:
  - JDBC URL: `jdbc:h2:file:./data/executions`
  - Username: `sa`
  - Password: (空白)

### テストカバレッジ

主要なテストケース：
- 注文処理ロジック
- 約定マッチング
- 板情報管理
- 約定結果配信
- 約定履歴永続化
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
- **Execution History Management**: Paginated execution history with H2 database persistence
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
- **H2 Database**: Execution history persistence
- **Spring Data JPA**: Database access layer
- **Gradle**: Build tool
- **JUnit 5**: Testing framework

## Instrument Types and Trading Restrictions

### Cash (Spot) Instruments
- **Short Selling Prohibited**: Sell orders exceeding held positions are rejected
- **Examples**: G_BTCJPY, B_BTCJPY, TESTJPY

### FX (Futures) Instruments
- **Free Trading**: Trading can start from either buy or sell side
- **Examples**: G_FX_BTCJPY, B_FX_BTCJPY

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
| G_BTCJPY | Cash | Bitcoin Spot | 1 | 1000 |
| G_FX_BTCJPY | FX | Bitcoin Futures | 1 | 1000 |
| B_BTCJPY | Cash | Bitcoin Spot | 1 | 1000 |
| B_FX_BTCJPY | FX | Bitcoin Futures | 1 | 1000 |
| TESTJPY | Cash | Test Spot | 1 | 1000 |

## License

This project is released under the MIT License.