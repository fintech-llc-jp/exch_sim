# クイックスタートガイド

## 最短で起動する方法

### 1. 設定ファイルの確認

`config.toml`が存在することを確認：

```bash
cd exch-sim-rust
ls -la config.toml
```

存在しない場合は、`config.toml.example`をコピー：

```bash
cp config.toml.example config.toml
```

### 2. PostgreSQL接続情報の確認

`config.toml`のPostgreSQL設定を確認・修正：

```toml
[postgres]
host = "localhost"
port = 5432
database = "exch_sim"
user = "postgres"
password = "postgres123"  # 実際のパスワードに変更
```

### 3. アプリケーション起動

```bash
cargo run
```

または、起動スクリプトを使用：

```bash
./start_local.sh
```

### 4. 別ターミナルでAPIテスト

```bash
./test_api.sh
```

## トラブルシューティング

### PostgreSQL接続エラー

```bash
# PostgreSQLが起動しているか確認
pg_isready -h localhost -p 5432

# データベースに接続できるか確認
psql -h localhost -U postgres -d exch_sim -c "SELECT 1;"
```

### ポート8080が使用中

`config.toml`でポートを変更：

```toml
[server]
port = 8081
```

### WebSocketエラー（テスト時は無視可）

テスト時はWebSocketを無効化：

```toml
[websocket.bitflyer]
enabled = false

[websocket.gmo]
enabled = false
```

詳細は `LOCAL_TEST_GUIDE.md` を参照してください。


