# ExchSim スナップショット機能無効化デプロイ手順

## 概要

Rust Snapshot Serviceが正常に動作しているため、Javaアプリケーション（ExchSim）のスナップショット機能を無効化してデプロイします。

## 前提条件

- Rust Snapshot Serviceが正常に動作していること
- データベースへの書き込みが確認できていること

## デプロイ手順

### 1. ローカルで設定を確認・変更

```bash
# application.propertiesを確認
cat src/main/resources/application.properties | grep -A 5 "app.market-board.snapshot"
```

スナップショット機能を無効化する設定：

```properties
# スナップショット機能を無効化（Rustサービスで処理）
app.market-board.snapshot.enabled=false
```

### 2. 設定ファイルを編集

```bash
# application.propertiesを編集
nano src/main/resources/application.properties
```

以下の設定を確認・変更：

```properties
# スナップショット機能を無効化
app.market-board.snapshot.enabled=false
```

### 3. ビルド

```bash
# クリーンビルド
./gradlew clean bootJar

# ビルド確認
ls -lh build/libs/exch_sim-0.0.1-SNAPSHOT.jar
```

### 4. VPSへのデプロイ

#### 方法1: jarファイルのみをコピー

```bash
# jarファイルをコピー
scp build/libs/exch_sim-0.0.1-SNAPSHOT.jar root@your-vps-ip:/opt/exch_sim/

# 設定ファイルも更新する場合
scp src/main/resources/application.properties root@your-vps-ip:/opt/exch_sim/
```

#### 方法2: デプロイスクリプトを使用

既存の`deploy.sh`がある場合は使用：

```bash
./deploy.sh
```

### 5. VPS上でサービスを再起動

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# サービスを再起動
sudo systemctl restart exch-sim

# ステータス確認
sudo systemctl status exch-sim

# ログ確認
sudo journalctl -u exch-sim -f
```

### 6. 動作確認

#### スナップショット機能が無効化されているか確認

```bash
# ログでスナップショット関連のメッセージを確認
sudo journalctl -u exch-sim | grep -i snapshot

# スナップショットサービスが起動していないことを確認（MarketBoardSnapshotServiceが無効化されている）
sudo journalctl -u exch-sim | grep -i "MarketBoardSnapshotService"
```

#### データベースへの書き込み確認

```bash
# Rust Snapshot Serviceがデータを書き込んでいることを確認
psql -U postgres -d exch_sim -c "
SELECT 
    symbol, 
    COUNT(*) as snapshot_count,
    MAX(timestamp) as latest_snapshot
FROM market_board_snapshots
WHERE timestamp > NOW() - INTERVAL '1 hour'
GROUP BY symbol
ORDER BY symbol;
"
```

## 設定ファイルの完全な例

`application.properties`の関連設定：

```properties
# スナップショット機能を無効化（Rustサービスで処理）
app.market-board.snapshot.enabled=false

# その他の設定は変更不要
# app.market-board.snapshot.interval-ms=30000
# app.market-board.snapshot.max-levels=3
```

## デプロイ後の確認項目

### 1. Javaアプリケーションのログ確認

```bash
# リアルタイムログ
sudo journalctl -u exch-sim -f

# エラーログのみ
sudo journalctl -u exch-sim -p err
```

### 2. スナップショット機能が無効化されているか確認

ログに以下のようなメッセージが**表示されない**ことを確認：

- `MarketBoardSnapshotService`の起動メッセージ
- スナップショット収集のログ
- `PostgreSQLWriter`のスナップショット関連ログ

### 3. Rust Snapshot Serviceの動作確認

```bash
# Rustサービスのステータス
sudo systemctl status snapshot-service

# Rustサービスのログ
sudo journalctl -u snapshot-service -f
```

### 4. データベースへの書き込み確認

```bash
# 最新のスナップショットを確認
psql -U postgres -d exch_sim -c "
SELECT 
    symbol,
    timestamp,
    COUNT(*) as price_levels
FROM market_board_snapshots s
JOIN market_board_price_levels p ON s.id = p.snapshot_id
GROUP BY s.id, symbol, timestamp
ORDER BY timestamp DESC
LIMIT 10;
"
```

## トラブルシューティング

### スナップショット機能がまだ動作している場合

```bash
# 設定ファイルを再確認
cat /opt/exch_sim/application.properties | grep "app.market-board.snapshot.enabled"

# 環境変数で上書きされていないか確認
sudo systemctl show exch-sim | grep -i snapshot

# サービスを再起動
sudo systemctl restart exch-sim
```

### Javaアプリケーションが起動しない場合

```bash
# エラーログを確認
sudo journalctl -u exch-sim -n 100 --no-pager

# 設定ファイルの構文エラーを確認
java -jar /opt/exch_sim/exch_sim-0.0.1-SNAPSHOT.jar --spring.config.location=file:/opt/exch_sim/application.properties --debug
```

### データベース接続エラー

```bash
# 接続情報を確認
cat /opt/exch_sim/application.properties | grep -E "spring.datasource|DB_"

# 接続テスト
psql -h localhost -U postgres -d exch_sim -c "SELECT 1;"
```

## リソース使用量の確認

### CPU使用率

```bash
# JavaプロセスのCPU使用率
ps aux | grep java | grep exch_sim

# RustプロセスのCPU使用率
ps aux | grep snapshot-service
```

### メモリ使用量

```bash
# Javaプロセスのメモリ使用量
ps aux | grep java | grep exch_sim

# Rustプロセスのメモリ使用量
ps aux | grep snapshot-service

# システム全体のメモリ
free -h
```

## ロールバック手順

もし問題が発生した場合、スナップショット機能を再有効化：

```bash
# 設定ファイルを編集
nano /opt/exch_sim/application.properties

# 以下の設定を変更
app.market-board.snapshot.enabled=true

# サービスを再起動
sudo systemctl restart exch-sim
```

