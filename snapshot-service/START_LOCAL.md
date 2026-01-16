# ローカルテスト用起動ガイド

## 1. 設定ファイルの準備

まず、設定ファイルの例をコピーして編集します：

```bash
cd snapshot-service
cp config.toml.example config.toml
```

`config.toml`を編集して、PostgreSQL接続情報などを設定してください：

```toml
[postgres]
host = "localhost"  # ローカルのPostgreSQL
port = 5432
database = "exch_sim"
user = "exch_sim_user"  # 実際のユーザー名に変更
password = "your_password"  # 実際のパスワードに変更
max_connections = 5
```

## 2. 起動コマンド

### 基本的な起動コマンド

```bash
cd snapshot-service
source "$HOME/.cargo/env"  # Rust環境を読み込む（初回のみ）
CONFIG_PATH=config.toml ./target/release/snapshot-service
```

### ログレベルを変更する場合

```bash
RUST_LOG=snapshot_service=debug CONFIG_PATH=config.toml ./target/release/snapshot-service
```

### より詳細なログ

```bash
RUST_LOG=snapshot_service=trace,websocket=debug CONFIG_PATH=config.toml ./target/release/snapshot-service
```

## 3. 起動前の確認事項

### PostgreSQLが起動しているか確認

```bash
# PostgreSQLが起動しているか確認
psql -U exch_sim_user -d exch_sim -c "SELECT 1;"
```

### 必要なテーブルが存在するか確認

```bash
psql -U exch_sim_user -d exch_sim -c "\d market_board_snapshots"
psql -U exch_sim_user -d exch_sim -c "\d market_board_price_levels"
```

## 4. 起動後の確認

### 正常に起動しているか確認

以下のようなログが表示されれば正常です：

```
Starting Snapshot Service...
Configuration loaded from: config.toml
Connected to PostgreSQL: exch_sim_user@localhost
WebSocket clients started
Snapshot collector started
PostgreSQL writer initialized
Snapshot Service is running. Press Ctrl+C to stop.
```

### WebSocket接続の確認

```
Connecting to Bitflyer WebSocket: wss://ws.lightstream.bitflyer.com/json-rpc
Bitflyer WebSocket connection established
Subscribed to Bitflyer channel: lightning_board_snapshot_BTC_JPY
```

### スナップショット収集の確認

```
Collected snapshot for symbol: B_BTCJPY
Collected snapshot for symbol: B_FX_BTCJPY
Successfully wrote batch of 4 snapshots
```

## 5. トラブルシューティング

### PostgreSQL接続エラー

```
Failed to connect to PostgreSQL
```

**対処法**:
- PostgreSQLが起動しているか確認
- `config.toml`の接続情報が正しいか確認
- ファイアウォール設定を確認

### WebSocket接続エラー

```
Failed to connect to Bitflyer WebSocket
```

**対処法**:
- インターネット接続を確認
- WebSocket URLが正しいか確認
- プロキシ設定が必要な場合は環境変数を設定

### データが保存されない

**確認事項**:
- ログで「Successfully wrote batch」が表示されているか
- PostgreSQLでデータを確認：
  ```sql
  SELECT COUNT(*) FROM market_board_snapshots;
  SELECT * FROM market_board_snapshots ORDER BY timestamp DESC LIMIT 10;
  ```

## 6. 停止方法

`Ctrl+C`を押すと正常にシャットダウンします：

```
Shutdown signal received
WebSocket clients stopped
Snapshot Service stopped
```

## 7. デバッグモードでの起動

開発中はデバッグビルドを使用することもできます：

```bash
# デバッグビルド
cargo build

# デバッグビルドで起動
RUST_LOG=snapshot_service=debug CONFIG_PATH=config.toml ./target/debug/snapshot-service
```

デバッグビルドは最適化されていないため、実行速度は遅いですが、コンパイルが速く、デバッグ情報が豊富です。

