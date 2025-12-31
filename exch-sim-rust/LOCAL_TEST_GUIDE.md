# ローカルテストガイド

## 前提条件

1. **PostgreSQLが起動していること**
   - デフォルト設定: `localhost:5432`
   - データベース: `exch_sim`
   - ユーザー: `postgres`
   - パスワード: `postgres123`

2. **必要なテーブルが作成されていること**
   - `users`
   - `user_roles`
   - `executions`
   - `positions`
   - `trade_history`

## セットアップ手順

### 1. 設定ファイルの作成

`config.toml.example`をコピーして`config.toml`を作成：

```bash
cd exch-sim-rust
cp config.toml.example config.toml
```

### 2. 設定ファイルの編集

`config.toml`を編集して、PostgreSQL接続情報を確認・修正：

```toml
[postgres]
host = "localhost"
port = 5432
database = "exch_sim"
user = "postgres"
password = "postgres123"  # 実際のパスワードに変更
max_connections = 5
```

### 3. WebSocketクライアントの無効化（オプション）

テスト時はWebSocketクライアントを無効化できます：

```toml
[websocket.bitflyer]
enabled = false  # trueからfalseに変更

[websocket.gmo]
enabled = false  # trueからfalseに変更
```

### 4. アプリケーションの起動

```bash
cd exch-sim-rust
cargo run
```

または、設定ファイルのパスを指定：

```bash
CONFIG_PATH=config.toml cargo run
```

### 5. 起動確認

ログに以下のメッセージが表示されれば成功：

```
Starting ExchSim Rust Service...
Configuration loaded from: config.toml
Connected to PostgreSQL: postgres@localhost
REST API server listening on port 8080
```

## APIテスト

### 方法1: テストスクリプトを使用（推奨）

```bash
cd exch-sim-rust
./test_api.sh
```

### 方法2: 手動でcurlコマンドを実行

#### 1. ユーザー登録

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123"
  }'
```

#### 2. ログイン

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123"
  }'
```

レスポンスから`token`を取得します。

#### 3. 注文（認証が必要）

```bash
TOKEN="<上記で取得したtoken>"

curl -X POST http://localhost:8080/api/orders/new \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "symbol": "B_BTCJPY",
    "side": "BUY",
    "ord_type": "LIMIT",
    "price": 10000000.0,
    "quantity": 0.01,
    "tif": "GTC"
  }'
```

#### 4. ポジション確認

```bash
curl -X GET http://localhost:8080/api/positions/summary \
  -H "Authorization: Bearer $TOKEN"
```

#### 5. 取引履歴確認

```bash
curl -X GET http://localhost:8080/api/trade-history \
  -H "Authorization: Bearer $TOKEN"
```

## トラブルシューティング

### PostgreSQL接続エラー

```bash
# PostgreSQLが起動しているか確認
psql -h localhost -U postgres -d exch_sim -c "SELECT 1;"
```

### ポートが既に使用されている

`config.toml`でポートを変更：

```toml
[server]
port = 8081  # 別のポートに変更
```

### WebSocket接続エラー

テスト時はWebSocketを無効化してください（上記参照）。

## 環境変数での設定

環境変数で設定を上書きできます：

```bash
# 設定ファイルのパス
CONFIG_PATH=config.toml cargo run

# ログレベル
RUST_LOG=debug cargo run
```

