# メモリ最適化とヒープサイズ設定

## 問題

OutOfMemoryエラーでアプリケーションが落ちることがある。

## メモリ使用状況の分析

### 主要なメモリ消費箇所

1. **PostgreSQLWriter**
   - `LinkedBlockingQueue<MarketBoardSnapshot>` - 無制限に成長する可能性
   - スナップショットがキューに溜まりすぎるとメモリ不足

2. **PositionManager**
   - `ConcurrentHashMap<String, ConcurrentHashMap<String, Position>>` - ユーザー別・銘柄別ポジション
   - `List<TradeHistory>` - 取引履歴キャッシュ（無制限に成長）
   - `ConcurrentHashMap<String, ConcurrentHashMap<String, FifoPositionQueue>>` - FIFOキュー

3. **ExecutionQueueService**
   - `ConcurrentHashMap<String, BlockingQueue<Execution>>` - ユーザーごとの約定キュー
   - `ConcurrentHashMap<String, ConcurrentLinkedDeque<Execution>>` - 銘柄ごとの約定履歴（無制限に成長）

### メモリ使用量の推定

- **基本アプリケーション**: 約100-150MB
- **Spring Boot + 依存関係**: 約50-100MB
- **メモリキャッシュ（ユーザー10人、銘柄5種類）**: 約50-100MB
- **キュー（スナップショット1000件）**: 約50-100MB
- **その他（接続プール、スレッドなど）**: 約50-100MB

**合計**: 約300-550MB（安全マージン込みで512MB-1GB推奨）

## 推奨ヒープサイズ設定

### VPS環境（2CPU、メモリ4GB程度）

```bash
# 最小ヒープ: 256MB、最大ヒープ: 768MB
JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### VPS環境（2CPU、メモリ2GB程度）

```bash
# 最小ヒープ: 128MB、最大ヒープ: 512MB
JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### より安全な設定（メモリに余裕がある場合）

```bash
# 最小ヒープ: 512MB、最大ヒープ: 1GB
JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

## G1GCの設定理由

- **低レイテンシ**: 200ms以下のGC一時停止
- **大容量ヒープに適している**: 1GB以上のヒープで効率的
- **予測可能な一時停止**: MaxGCPauseMillisで制御可能

## メモリリーク対策

### 1. キューサイズの制限

`PostgreSQLWriter`のキューサイズを制限する設定を追加：

```properties
# PostgreSQL Writer Configuration
# キューサイズの上限（デフォルト: 無制限、推奨: 1000-5000）
app.postgresql.writer.queue-capacity=2000
```

### 2. 約定履歴の制限

`ExecutionQueueService`の約定履歴を制限：

```properties
# Execution Queue Configuration
# 銘柄ごとの約定履歴の最大保持数（デフォルト: 無制限、推奨: 1000-5000）
app.execution-queue.history-max-size=2000
```

### 3. 取引履歴キャッシュの制限

`PositionManager`の取引履歴キャッシュを制限：

```properties
# Position Manager Configuration
# 取引履歴キャッシュの最大保持数（デフォルト: 無制限、推奨: 1000-5000）
app.position-manager.trade-history-cache-max-size=2000
```

## 実装方法

### 1. 起動スクリプトに追加

```bash
#!/bin/bash
export JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
./gradlew bootRun
```

### 2. systemdサービスに追加

`/etc/systemd/system/exch-sim.service`:

```ini
[Service]
Environment="JAVA_OPTS=-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /path/to/exch-sim.jar
```

### 3. Dockerfileに追加

```dockerfile
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

## メモリ監視

### JVMメモリ使用状況の確認

```bash
# 実行中のJavaプロセスのメモリ使用状況を確認
jstat -gc <pid> 1000

# または
jmap -heap <pid>
```

### アプリケーションログで監視

```properties
# GCログを有効化
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xloggc:/var/log/exch-sim/gc.log
```

## トラブルシューティング

### OutOfMemoryが発生した場合

1. **ヒープダンプを取得**:
   ```bash
   jmap -dump:format=b,file=heap.hprof <pid>
   ```

2. **メモリ使用状況を分析**:
   - Eclipse Memory Analyzer (MAT)を使用
   - どのオブジェクトがメモリを消費しているか確認

3. **ヒープサイズを増やす**:
   - 段階的に増やす（512MB → 768MB → 1GB）
   - システムメモリの80%以下に抑える

### メモリ不足の症状

- GCが頻繁に発生（1秒に1回以上）
- GC一時停止が長い（500ms以上）
- レスポンスが遅い
- OutOfMemoryエラー

## 推奨設定まとめ

### 最小構成（メモリ2GB VPS）

```properties
# application.properties
app.postgresql.writer.queue-capacity=1000
app.execution-queue.history-max-size=1000
app.position-manager.trade-history-cache-max-size=1000

# JAVA_OPTS
-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### 標準構成（メモリ4GB VPS）

```properties
# application.properties
app.postgresql.writer.queue-capacity=2000
app.execution-queue.history-max-size=2000
app.position-manager.trade-history-cache-max-size=2000

# JAVA_OPTS
-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### 高負荷構成（メモリ8GB VPS）

```properties
# application.properties
app.postgresql.writer.queue-capacity=5000
app.execution-queue.history-max-size=5000
app.position-manager.trade-history-cache-max-size=5000

# JAVA_OPTS
-Xms512m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

## 関連ファイル

- `Dockerfile` - Docker環境のJAVA_OPTS設定
- `src/main/java/com/ys/exch_sim/domain/market_board/PostgreSQLWriter.java` - スナップショットキュー
- `src/main/java/com/ys/exch_sim/domain/service/ExecutionQueueService.java` - 約定キュー
- `src/main/java/com/ys/exch_sim/domain/position/PositionManager.java` - ポジションキャッシュ

## 変更履歴

- 2025-12-17: メモリ最適化とヒープサイズ設定ガイドを作成

