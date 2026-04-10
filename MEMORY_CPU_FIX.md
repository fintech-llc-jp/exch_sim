# メモリとCPU問題の追加修正

## 現在の状況

### 改善点
- メモリ使用量: 2.8GB/3.7GB（利用可能: 944MB）
- Javaプロセス: 1.4GB（35.8%）- 以前の2.47GBから改善

### 残っている問題
1. **Javaプロセスが167%のCPUを使用**（2CPUでほぼフル使用）
2. **PostgreSQL接続が`idle in transaction`状態**（トランザクションが長時間開いている）
3. **Javaヒープサイズが1GB**（まだ768MBに削減可能）

## 追加の修正

### 1. Javaヒープサイズを768MBに削減

現在: `-Xmx1g`（1GB）
推奨: `-Xmx768m`（768MB）

```bash
# systemdサービスファイルを編集
sudo nano /etc/systemd/system/exch-sim.service
```

変更:
```ini
# 変更前
Environment="JAVA_OPTS=-Xms256m -Xmx1g ..."

# 変更後
Environment="JAVA_OPTS=-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### 2. PostgreSQL接続プールの設定を確認

`idle in transaction`状態の接続が複数あります。これはトランザクションが長時間開いていることを示します。

#### application.propertiesの確認

```properties
# HikariCP Connection Pool Configuration
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=0
spring.datasource.hikari.connection-timeout=10000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

#### 接続数を削減

```properties
# 接続プールサイズを削減（10 → 5）
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=0
```

### 3. PostgreSQLの`idle in transaction`接続を確認

```bash
# 長時間開いているトランザクションを確認
sudo -u postgres psql -d exch_sim -c "
SELECT 
    pid,
    now() - pg_stat_activity.query_start AS duration,
    query,
    state
FROM pg_stat_activity
WHERE state = 'idle in transaction'
ORDER BY duration DESC;
"

# 長時間開いているトランザクションを強制終了（必要に応じて）
sudo -u postgres psql -d exch_sim -c "
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND now() - pg_stat_activity.query_start > interval '5 minutes';
"
```

### 4. CPU使用率が高い原因の調査

Javaプロセスが167%のCPUを使用している原因を確認：

```bash
# Javaプロセスのスレッドを確認
JAVA_PID=$(pgrep -f "exch_sim.jar")
top -H -p $JAVA_PID

# または
jstack $JAVA_PID | grep -A 10 "RUNNABLE"
```

考えられる原因：
1. **スナップショット取得処理が頻繁**（2秒間隔でもCPUを多く使用）
2. **PostgreSQL書き込み処理が重い**
3. **GC（ガベージコレクション）が頻繁に発生**

## 推奨設定（最終版）

### systemdサービス設定

`/etc/systemd/system/exch-sim.service`:

```ini
[Unit]
Description=Exchange Simulator Application
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/exch_sim

# メモリ4GB VPS用の最適化設定
Environment="JAVA_OPTS=-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+PrintGCDetails -Xloggc:/var/log/exch-sim/gc.log"

ExecStart=/usr/bin/java $JAVA_OPTS \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/exch_sim \
  -Dspring.datasource.username=exch_sim_user \
  -Dspring.datasource.password=xxxxxxxx \
  -jar /opt/exch_sim/exch_sim.jar

Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### application.propertiesの設定

```properties
# HikariCP Connection Pool Configuration（接続数を削減）
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=0
spring.datasource.hikari.connection-timeout=10000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.leak-detection-threshold=60000

# スナップショット取得間隔をさらに延長（CPU使用率削減）
app.market-board.snapshot.interval-ms=5000
app.market-board.snapshot.max-levels=10
```

## 実行手順

### 1. サービスを停止

```bash
sudo systemctl stop exch-sim
```

### 2. 設定ファイルを更新

```bash
# systemdサービス設定を編集
sudo nano /etc/systemd/system/exch-sim.service

# application.propertiesを編集（jarファイル内または外部設定ファイル）
# または、起動時に設定を上書き
```

### 3. 長時間開いているトランザクションをクリーンアップ

```bash
# 必要に応じて、長時間開いているトランザクションを終了
sudo -u postgres psql -d exch_sim -c "
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND now() - pg_stat_activity.query_start > interval '10 minutes';
"
```

### 4. サービスを再起動

```bash
sudo systemctl daemon-reload
sudo systemctl start exch-sim
sudo systemctl status exch-sim
```

### 5. 確認

```bash
# メモリ使用状況
free -h

# CPUとメモリ使用量
ps aux --sort=-%mem | head -10

# PostgreSQL接続状況
sudo -u postgres psql -d exch_sim -c "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"
```

## 期待される効果

### メモリ
- Java: 約1GB（1.4GB → 約1GB）
- PostgreSQL: 約1.5GB（変更なし）
- **合計**: 約2.5GB（利用可能: 約1.2GB）

### CPU
- スナップショット取得間隔を5秒に延長することで、CPU使用率を削減
- 接続プールサイズを削減することで、PostgreSQLの負荷を軽減

## モニタリング

### CPU使用率の監視

```bash
# リアルタイムでCPU使用率を監視
top -bn1 | grep -E "PID|java|postgres"

# または
htop
```

### GCログの確認

```bash
# GCログを確認（設定した場合）
tail -f /var/log/exch-sim/gc.log
```

## トラブルシューティング

### CPU使用率が依然として高い場合

1. **スナップショット取得間隔をさらに延長**:
   ```properties
   app.market-board.snapshot.interval-ms=10000  # 10秒
   ```

2. **スナップショット取得レベル数を削減**:
   ```properties
   app.market-board.snapshot.max-levels=5  # 10から5に
   ```

3. **GCログを確認してGCが頻繁に発生していないか確認**

### メモリがまだ不足する場合

1. **ヒープサイズを512MBに削減**:
   ```bash
   -Xmx512m
   ```

2. **PostgreSQLのメモリ設定を確認**:
   ```bash
   sudo -u postgres psql -c "SHOW shared_buffers;"
   ```

## 関連ファイル

- `/etc/systemd/system/exch-sim.service` - systemdサービス設定
- `src/main/resources/application.properties` - アプリケーション設定
- `/etc/postgresql/15/main/postgresql.conf` - PostgreSQL設定

