# 緊急メモリ問題の修正

## 現在の状況

- **総メモリ**: 3.7GB
- **使用中**: 3.7GB（ほぼ100%）
- **利用可能**: わずか6.4MB
- **Javaプロセス**: 2.47GB（63.1%）- `-Xmx2g`設定
- **PostgreSQL**: 約1.5GB
- **スワップ**: 無効（0B）

## 問題

Javaヒープサイズが`-Xmx2g`（2GB）に設定されており、4GB VPSには大きすぎます。PostgreSQL（約1.5GB）と合わせて約4GBを使用し、システムメモリを完全に使い切っています。

## 緊急対処

### 1. Javaヒープサイズを即座に削減

現在の設定：
```bash
-Xms512m -Xmx2g
```

**推奨設定（4GB VPS）**:
```bash
-Xms256m -Xmx768m
```

### 2. systemdサービスファイルを修正

`/etc/systemd/system/exch-sim.service`を編集：

```ini
[Unit]
Description=Exchange Simulator Application
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/exch_sim

# メモリ4GB VPS用の適切な設定
Environment="JAVA_OPTS=-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

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

### 3. サービスを再起動

```bash
# 設定をリロード
sudo systemctl daemon-reload

# サービスを再起動
sudo systemctl restart exch-sim

# ステータス確認
sudo systemctl status exch-sim

# メモリ使用状況を確認
free -h
ps aux --sort=-%mem | head -10
```

## PostgreSQLのメモリ設定も確認

PostgreSQLが約1.5GB使用しているため、設定を確認：

```bash
# PostgreSQL設定ファイルを確認
sudo -u postgres psql -c "SHOW shared_buffers;"
sudo -u postgres psql -c "SHOW effective_cache_size;"
sudo -u postgres psql -c "SHOW work_mem;"
```

`/etc/postgresql/15/main/postgresql.conf`を確認：

```conf
# 推奨設定（4GB VPS、Javaと共有）
shared_buffers = 256MB          # デフォルト128MBから増やす
effective_cache_size = 1GB      # システムメモリの25%
work_mem = 4MB                  # 接続数に応じて調整
maintenance_work_mem = 64MB
```

設定変更後、PostgreSQLを再起動：

```bash
sudo systemctl restart postgresql
```

## 期待される効果

### 変更前
- Java: 2.47GB
- PostgreSQL: 1.5GB
- **合計**: 約4GB（メモリ不足）

### 変更後
- Java: 約800MB-1GB（ヒープ768MB + オーバーヘッド）
- PostgreSQL: 約1GB（設定調整後）
- **合計**: 約2GB（余裕あり）

## メモリ使用状況の監視

```bash
# リアルタイムでメモリ使用状況を監視
watch -n 1 free -h

# または
watch -n 1 'ps aux --sort=-%mem | head -10'
```

## スワップの有効化（オプション）

メモリ不足時の安全策として、スワップを有効化：

```bash
# 2GBのスワップファイルを作成
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 永続化
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**注意**: スワップは物理メモリより遅いため、根本的な解決策ではありません。

## 確認コマンド

修正後の確認：

```bash
# 1. メモリ使用状況
free -h

# 2. Javaプロセスのメモリ使用量
ps aux | grep java | grep -v grep

# 3. PostgreSQLのメモリ使用量
ps aux | grep postgres | grep -v grep | awk '{sum+=$6} END {print "Total: " sum/1024 " MB"}'

# 4. kswapd0のCPU使用率（0%になるはず）
top -bn1 | grep kswapd0
```

## トラブルシューティング

### メモリがまだ不足する場合

1. **Javaヒープをさらに削減**:
   ```bash
   -Xmx512m  # 768MBから512MBに
   ```

2. **PostgreSQLの接続数を減らす**:
   ```conf
   max_connections = 20  # デフォルト100から削減
   ```

3. **不要なプロセスを停止**:
   ```bash
   # 使用していないサービスを確認
   systemctl list-units --type=service --state=running
   ```

### アプリケーションがOutOfMemoryになる場合

1. **ヒープサイズを少し増やす**:
   ```bash
   -Xmx1024m  # 768MBから1GBに
   ```

2. **GCログを有効化して分析**:
   ```bash
   -XX:+PrintGCDetails -Xloggc:/var/log/exch-sim/gc.log
   ```

## 関連ファイル

- `/etc/systemd/system/exch-sim.service` - systemdサービス設定
- `/etc/postgresql/15/main/postgresql.conf` - PostgreSQL設定

