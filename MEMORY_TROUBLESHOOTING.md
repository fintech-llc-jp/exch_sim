# kswapd0 CPU 100%問題の対処法

## 問題の症状

`kswapd0`プロセスが常にCPUを100%使用している。

```
root      20   0       0      0      0 S 100.0   0.0 112:04.88 kswapd0
```

## 原因

`kswapd0`はLinuxカーネルのスワップデーモンで、物理メモリが不足しているときにスワップ領域とメモリの間でページを移動します。これが100%のCPUを使用しているということは、**システムが深刻なメモリ不足状態**にあります。

## 確認手順

### 1. メモリ使用状況の確認

```bash
# メモリとスワップの使用状況を確認
free -h
```

出力例：
```
              total        used        free      shared  buff/cache   available
Mem:           2.0G        1.8G        100M         50M        100M         50M
Swap:          1.0G        900M        100M
```

**問題のサイン**:
- `available`が非常に少ない（100MB以下）
- `Swap`の`used`が大きい（500MB以上）
- `free`が非常に少ない

### 2. スワップ使用状況の確認

```bash
# スワップの詳細を確認
swapon --show

# スワップの使用率を確認
cat /proc/swaps
```

### 3. メモリ使用量の多いプロセスを確認

```bash
# メモリ使用量順にプロセスを表示
ps aux --sort=-%mem | head -20

# Javaプロセスのメモリ使用状況
ps aux | grep java | grep -v grep
```

### 4. Javaプロセスの詳細なメモリ情報

```bash
# JavaプロセスのPIDを取得
JAVA_PID=$(pgrep -f "exch_sim")

# メモリマップを確認
pmap -x $JAVA_PID | tail -1

# ヒープ使用状況を確認（JVMが実行中の場合）
jstat -gc $JAVA_PID 1000
```

## 対処法

### 1. Javaヒープサイズを減らす（最優先）

現在の設定を確認：

```bash
# 実行中のJavaプロセスの起動オプションを確認
ps aux | grep java | grep -v grep
```

**推奨設定（メモリ2GB VPS）**:

```bash
# ヒープサイズを512MBに削減
java -Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar exch_sim-0.0.1-SNAPSHOT.jar
```

**推奨設定（メモリ4GB VPS）**:

```bash
# ヒープサイズを768MBに設定（現在の設定）
java -Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar exch_sim-0.0.1-SNAPSHOT.jar
```

**メモリが非常に少ない場合（1.5GB以下）**:

```bash
# ヒープサイズを256MBに削減
java -Xms64m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar exch_sim-0.0.1-SNAPSHOT.jar
```

### 2. システムメモリの確認

VPSの総メモリを確認：

```bash
# 総メモリを確認
free -h | grep Mem | awk '{print $2}'

# または
cat /proc/meminfo | grep MemTotal
```

**推奨設定の目安**:
- **メモリ1GB**: `-Xmx256m`（最大256MB）
- **メモリ2GB**: `-Xmx512m`（最大512MB）
- **メモリ4GB**: `-Xmx768m`（最大768MB）
- **メモリ8GB以上**: `-Xmx1536m`（最大1.5GB）

**重要**: ヒープサイズはシステムメモリの**50%以下**に抑えることを推奨します。

### 3. スワップを無効化または削減

スワップが頻繁に使用されている場合、パフォーマンスが大幅に低下します。

```bash
# スワップを一時的に無効化（再起動で元に戻る）
sudo swapoff -a

# 永続的に無効化する場合（注意: メモリ不足のリスクあり）
# /etc/fstabからスワップの行をコメントアウト
```

**注意**: スワップを無効化する前に、十分な物理メモリがあることを確認してください。

### 4. 他のプロセスのメモリ使用を確認

```bash
# メモリ使用量の多いプロセスTop 10
ps aux --sort=-%mem | head -11

# PostgreSQLのメモリ使用状況
ps aux | grep postgres | grep -v grep
```

PostgreSQLもメモリを消費するため、JavaとPostgreSQLの合計でシステムメモリを超えないようにする必要があります。

### 5. メモリリークの可能性を調査

```bash
# Javaプロセスのヒープダンプを取得
JAVA_PID=$(pgrep -f "exch_sim")
jmap -dump:format=b,file=heap.hprof $JAVA_PID

# ヒープ使用状況を確認
jmap -heap $JAVA_PID
```

## 緊急時の対処

### 1. アプリケーションを再起動

```bash
# systemdサービスを使用している場合
sudo systemctl restart exch-sim

# 直接実行している場合
# プロセスを停止して再起動
pkill -f "exch_sim"
java -Xms128m -Xmx512m -XX:+UseG1GC -jar exch_sim-0.0.1-SNAPSHOT.jar
```

### 2. メモリを解放

```bash
# キャッシュをクリア（一時的）
sync
echo 3 | sudo tee /proc/sys/vm/drop_caches

# 注意: これは一時的な対処法です
```

### 3. 不要なプロセスを停止

```bash
# 使用していないサービスを停止
sudo systemctl stop <unused-service>
```

## 推奨設定（VPSのメモリ容量別）

### メモリ1GB VPS

```bash
JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### メモリ2GB VPS（推奨最小構成）

```bash
JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### メモリ4GB VPS

```bash
JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### メモリ8GB VPS

```bash
JAVA_OPTS="-Xms512m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

## systemdサービス設定例

`/etc/systemd/system/exch-sim.service`:

```ini
[Unit]
Description=Exchange Simulator Application
After=network.target postgresql.service

[Service]
Type=simple
User=exch_sim
WorkingDirectory=/opt/exch_sim

# メモリ2GB VPS用の設定
Environment="JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/exch_sim/exch_sim-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10

# メモリ制限（オプション）
MemoryLimit=768M

[Install]
WantedBy=multi-user.target
```

## モニタリング

### メモリ使用状況を継続的に監視

```bash
# 1秒ごとにメモリ使用状況を表示
watch -n 1 free -h

# または
watch -n 1 'ps aux --sort=-%mem | head -10'
```

### アラート設定

```bash
# メモリ使用率が90%を超えたらアラート
while true; do
  MEM_USAGE=$(free | grep Mem | awk '{printf "%.0f", $3/$2 * 100}')
  if [ $MEM_USAGE -gt 90 ]; then
    echo "警告: メモリ使用率が${MEM_USAGE}%です"
    # 通知を送信する処理
  fi
  sleep 60
done
```

## 根本的な解決策

1. **VPSのメモリを増やす**: 最も確実な解決策
2. **アプリケーションのメモリ使用を最適化**: キューサイズの制限など
3. **不要な機能を無効化**: メモリキャッシュを無効化するなど

## 関連ファイル

- `MEMORY_OPTIMIZATION.md` - メモリ最適化の詳細
- `DEPLOYMENT_GUIDE.md` - デプロイ方法

