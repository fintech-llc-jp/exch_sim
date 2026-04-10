# CPU使用率200%問題の解決方法

## 問題

ExchSimを起動するとCPU使用率が200%（2CPUでフル使用）になってしまう。

## 原因分析

スナップショット機能は既に無効化されていますが、以下の処理がCPUを消費している可能性があります：

1. **マーケットデータの高頻度処理**
   - WebSocketからのメッセージが高頻度で到着
   - `market-data.update-interval-ms=1000`（1秒間隔）で処理
   - 非同期処理のスレッドプールが大きい

2. **非同期処理のスレッドプール**
   - `market-data.async.core-pool-size=4`
   - `market-data.async.max-pool-size=8`
   - 複数のスレッドが同時に処理を実行

3. **PostgreSQLへの頻繁な書き込み**
   - 取引履歴やポジション情報の保存
   - トランザクション処理

## 解決方法

### 1. マーケットデータ更新間隔を延長

```properties
# 変更前: 1秒間隔
# 変更後: 5秒間隔（CPU使用率を約80%削減）
market-data.update-interval-ms=5000
```

### 2. 非同期処理のスレッドプールサイズを削減

```properties
# 変更前
market-data.async.core-pool-size=4
market-data.async.max-pool-size=8

# 変更後（CPU使用率を削減）
market-data.async.core-pool-size=2
market-data.async.max-pool-size=4
```

### 3. 板データのレベル数を削減

```properties
# 変更前: 10レベル
# 変更後: 5レベル（処理量を50%削減）
market-data.board.max-levels=5
```

### 4. バッチサイズを増加

```properties
# 変更前: 100
# 変更後: 200（書き込み頻度を50%削減）
market-data.trade.batch-size=200
```

## 推奨設定（CPU使用率を最小化）

`application.properties`に以下の設定を追加または変更：

```properties
# マーケットデータ更新間隔を延長（CPU使用率を大幅に削減）
market-data.update-interval-ms=5000

# 非同期処理のスレッドプールサイズを削減
market-data.async.core-pool-size=2
market-data.async.max-pool-size=4
market-data.async.queue-capacity=500

# 板データのレベル数を削減
market-data.board.max-levels=5

# バッチサイズを増加
market-data.trade.batch-size=200

# スナップショット機能は既に無効化済み
app.market-board.snapshot.enabled=false
```

## 即座に適用する方法

### VPS上で設定ファイルを編集

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# 設定ファイルを編集
nano /opt/exch_sim/application.properties

# 以下の設定を変更
# market-data.update-interval-ms=5000
# market-data.async.core-pool-size=2
# market-data.async.max-pool-size=4
# market-data.board.max-levels=5
# market-data.trade.batch-size=200

# サービスを再起動
sudo systemctl restart exch-sim

# ステータス確認
sudo systemctl status exch-sim

# CPU使用率を確認
top -p $(pgrep -f "exch_sim.jar")
```

## 段階的な最適化

### ステップ1: マーケットデータ更新間隔を延長（最も効果的）

```properties
market-data.update-interval-ms=5000
```

**期待される効果**: CPU使用率を約50-70%削減

### ステップ2: 非同期処理のスレッドプールを削減

```properties
market-data.async.core-pool-size=2
market-data.async.max-pool-size=4
```

**期待される効果**: CPU使用率を約20-30%削減

### ステップ3: 板データのレベル数を削減

```properties
market-data.board.max-levels=5
```

**期待される効果**: CPU使用率を約10-20%削減

## モニタリング

### CPU使用率の確認

```bash
# リアルタイムでCPU使用率を確認
top -p $(pgrep -f "exch_sim.jar")

# または
ps aux | grep java | grep exch_sim
```

### スレッド数の確認

```bash
# Javaプロセスのスレッド数を確認
JAVA_PID=$(pgrep -f "exch_sim.jar")
ps -T -p $JAVA_PID | wc -l
```

### ログで処理頻度を確認

```bash
# マーケットデータの更新頻度を確認
sudo journalctl -u exch-sim | grep -i "market.*update" | tail -20
```

## トラブルシューティング

### CPU使用率がまだ高い場合

1. **JVMのGC設定を確認**
   ```bash
   # systemdサービスファイルでJVMオプションを確認
   cat /etc/systemd/system/exch-sim.service | grep JAVA_OPTS
   ```

2. **PostgreSQL接続数を確認**
   ```bash
   # 接続数を確認
   psql -U postgres -d exch_sim -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'exch_sim';"
   ```

3. **プロファイリングツールを使用**
   ```bash
   # jstackでスレッドダンプを取得
   JAVA_PID=$(pgrep -f "exch_sim.jar")
   jstack $JAVA_PID > /tmp/thread_dump.txt
   ```

## 期待される結果

最適化後：
- **CPU使用率**: 200% → 50-80%（2CPU VPSの場合、25-40%）
- **メモリ使用量**: ほぼ変わらず
- **機能**: 通常の取引機能は正常に動作

