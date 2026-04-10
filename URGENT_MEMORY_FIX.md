# 緊急メモリ不足問題の修正

## 確認された問題

```
java.lang.OutOfMemoryError: Java heap space
oom-kill: Killed process 2595055 (java) total-vm:4884620kB, anon-rss:2462088kB
```

**OutOfMemoryErrorとOOM Killerの両方が発生**しています。これは深刻なメモリ不足です。

## 緊急対処（即座に実行）

### 1. スナップショット機能を完全に無効化

`application.properties`で以下を有効化：

```properties
# スナップショット機能を完全に無効化（必須）
app.market-board.snapshot.enabled=false
```

**効果**: メモリ使用量を約30-50%削減

### 2. Javaヒープサイズを512MBに削減

`/etc/systemd/system/exch-sim.service`:

```ini
Environment="JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### 3. systemdのメモリ制限を設定

```ini
[Service]
# メモリ制限を設定（OOM Killerを防ぐ）
MemoryLimit=1G
MemoryHigh=900M
```

### 4. PostgreSQL接続数をさらに削減

`application.properties`:

```properties
# 接続数を3に削減
spring.datasource.hikari.maximum-pool-size=3
```

## 完全なsystemdサービス設定

`/etc/systemd/system/exch-sim.service`:

```ini
[Unit]
Description=Exchange Simulator Application
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/exch_sim

# メモリ設定（512MBヒープ - 緊急時は256MBも検討）
Environment="JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/exch-sim/"

# メモリ制限を設定（OOM Killerを防ぐ）
MemoryLimit=1G
MemoryHigh=900M

# リスタート設定
Restart=always
RestartSec=10
StartLimitInterval=300
StartLimitBurst=5

ExecStart=/usr/bin/java $JAVA_OPTS \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/exch_sim \
  -Dspring.datasource.username=exch_sim_user \
  -Dspring.datasource.password=xxxxxxx \
  -jar /opt/exch_sim/exch_sim.jar

StandardOutput=journal
StandardError=journal
SyslogIdentifier=exch-sim

[Install]
WantedBy=multi-user.target
```

## 実行手順

### 1. サービスを停止

```bash
sudo systemctl stop exch-sim
```

### 2. application.propertiesを更新

jarファイルを再ビルドするか、外部設定ファイルを使用：

```bash
# 外部設定ファイルを作成
sudo nano /opt/exch_sim/application-override.properties
```

内容：
```properties
# スナップショット機能を完全に無効化
app.market-board.snapshot.enabled=false

# 接続数を削減
spring.datasource.hikari.maximum-pool-size=3
```

### 3. systemdサービス設定を更新

```bash
sudo nano /etc/systemd/system/exch-sim.service
# 上記の設定を適用
```

### 4. 設定をリロードして起動

```bash
sudo systemctl daemon-reload
sudo systemctl start exch-sim
sudo systemctl status exch-sim
```

### 5. メモリ使用状況を確認

```bash
free -h
ps aux --sort=-%mem | head -10
```

## それでもメモリ不足の場合

### ヒープサイズを256MBに削減

```ini
Environment="JAVA_OPTS=-Xms64m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

**注意**: 256MBでは機能が制限される可能性があります。

## 根本的な解決策

### 1. VPSのアップグレード（推奨）

2CPU/4GBではリソースが不足しています：
- **推奨**: 4CPU/8GB VPS
- **最小**: 2CPU/4GB VPS（スナップショット機能無効化必須）

### 2. スワップを有効化（一時的対策）

```bash
# 2GBのスワップファイルを作成
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 永続化
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**注意**: スワップは物理メモリより遅いため、パフォーマンスが低下します。

## モニタリング

### メモリ使用状況を継続的に監視

```bash
# リアルタイムで監視
watch -n 1 'free -h && echo "---" && ps aux --sort=-%mem | head -5'
```

### アラート設定

```bash
# メモリ使用率が85%を超えたらアラート
while true; do
  MEM_USAGE=$(free | grep Mem | awk '{printf "%.0f", $3/$2 * 100}')
  if [ $MEM_USAGE -gt 85 ]; then
    echo "警告: メモリ使用率が${MEM_USAGE}%です - $(date)"
  fi
  sleep 30
done
```

