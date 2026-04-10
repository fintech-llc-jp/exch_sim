# 自動シャットダウン問題のトラブルシューティング

## 問題

アプリケーションが自動的にシャットダウンしてしまう。ログには正常なシャットダウンシーケンスが記録されている。

## 考えられる原因

### 1. OutOfMemoryエラー（最も可能性が高い）

メモリ不足でOutOfMemoryエラーが発生し、アプリケーションがクラッシュしている可能性があります。

### 2. OOM Killer（Out-Of-Memory Killer）

LinuxカーネルのOOM Killerがメモリ不足を検出し、プロセスを強制終了している可能性があります。

### 3. systemdのメモリ制限

systemdサービスにメモリ制限が設定されており、制限を超えた場合にプロセスがkillされている可能性があります。

## 確認手順

### 1. OutOfMemoryエラーの確認

```bash
# systemdログでOutOfMemoryエラーを検索
sudo journalctl -u exch-sim -n 200 --no-pager | grep -i "outofmemory\|out of memory"

# アプリケーションログでエラーを確認
sudo journalctl -u exch-sim -n 200 --no-pager | grep -i "error\|exception\|fatal"
```

### 2. OOM Killerの確認

```bash
# システムログでOOM Killerの記録を確認
sudo dmesg | grep -i "out of memory\|killed process\|oom"

# または
sudo grep -i "killed process" /var/log/syslog | tail -20
```

### 3. systemdサービスのメモリ制限確認

```bash
# systemdサービス設定を確認
sudo systemctl show exch-sim | grep Memory

# サービス設定ファイルを確認
sudo cat /etc/systemd/system/exch-sim.service
```

### 4. メモリ使用状況の確認

```bash
# 現在のメモリ使用状況
free -h

# プロセスのメモリ使用状況
ps aux --sort=-%mem | head -10
```

## 対処法

### 1. Javaヒープサイズをさらに削減

現在の設定（`-Xmx768m`）でもメモリ不足の場合、さらに削減：

```ini
# /etc/systemd/system/exch-sim.service
Environment="JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### 2. systemdのメモリ制限を設定

メモリ制限を設定して、OOM Killerを防ぐ：

```ini
[Service]
# メモリ制限を設定（1GB）
MemoryLimit=1G
MemoryHigh=900M

# CPU制限も設定（オプション）
CPUQuota=150%
```

### 3. OOM Killerの調整（一時的）

```bash
# OOM Killerのスコアを確認
cat /proc/$(pgrep -f "exch_sim.jar")/oom_score

# OOM Killerから保護（一時的、再起動でリセット）
echo -1000 | sudo tee /proc/$(pgrep -f "exch_sim.jar")/oom_score_adj
```

### 4. スワップを有効化（緊急時）

```bash
# 2GBのスワップファイルを作成
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 永続化
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 推奨設定（2CPU/4GB VPS）

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

# メモリ設定（512MBヒープ）
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
  -Dspring.datasource.password=xxxxx \
  -jar /opt/exch_sim/exch_sim.jar

[Install]
WantedBy=multi-user.target
```

### application.propertiesの設定

```properties
# スナップショット機能を無効化（CPUとメモリ使用率を大幅に削減）
app.market-board.snapshot.enabled=false

# または、最小構成
app.market-board.snapshot.interval-ms=30000
app.market-board.snapshot.max-levels=5
app.postgresql.writer.batch-size=20
spring.datasource.hikari.maximum-pool-size=3
```

## 緊急時の対処

### 1. アプリケーションを再起動

```bash
sudo systemctl restart exch-sim
```

### 2. メモリを解放

```bash
# キャッシュをクリア（一時的）
sync
echo 3 | sudo tee /proc/sys/vm/drop_caches
```

### 3. ヒープダンプを取得（OutOfMemory発生時）

```bash
# ヒープダンプのディレクトリを作成
sudo mkdir -p /var/log/exch-sim
sudo chmod 755 /var/log/exch-sim

# アプリケーションを再起動（-XX:+HeapDumpOnOutOfMemoryErrorが設定されている場合）
sudo systemctl restart exch-sim
```

## モニタリング

### メモリ使用状況を継続的に監視

```bash
# リアルタイムでメモリ使用状況を監視
watch -n 1 free -h

# プロセスのメモリ使用状況を監視
watch -n 1 'ps aux --sort=-%mem | head -10'
```

### アラート設定

```bash
# メモリ使用率が90%を超えたらアラート
while true; do
  MEM_USAGE=$(free | grep Mem | awk '{printf "%.0f", $3/$2 * 100}')
  if [ $MEM_USAGE -gt 90 ]; then
    echo "警告: メモリ使用率が${MEM_USAGE}%です - $(date)"
    # 通知を送信する処理
  fi
  sleep 60
done
```

## 根本的な解決策

### 1. VPSのアップグレード

2CPU/4GBではリソースが不足している可能性があります：
- **推奨**: 4CPU/8GB VPS
- **最小**: 2CPU/4GB VPS（設定を最適化した場合）

### 2. 機能の無効化

不要な機能を無効化：
- スナップショット機能を無効化
- メモリキャッシュを無効化

### 3. アーキテクチャの見直し

- マイクロサービス化
- データベースを別サーバーに分離

## 関連ファイル

- `/etc/systemd/system/exch-sim.service` - systemdサービス設定
- `src/main/resources/application.properties` - アプリケーション設定

