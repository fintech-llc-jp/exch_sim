# Rust Snapshot Service 本番環境デプロイガイド

## ⚠️ 重要: アーキテクチャの違いについて

**macOSでビルドしたバイナリはLinux VPSで実行できません。**
`Exec format error`が発生する場合は、**VPS上で直接ビルド**する必要があります。

**推奨方法**: [VPS上でビルドする方法](./DEPLOYMENT_GUIDE_VPS_BUILD.md) を参照してください。

---

## 目次

1. [前提条件](#前提条件)
2. [ローカルでのビルド](#ローカルでのビルド) ⚠️ macOS→Linuxは不可
3. [VPSへのデプロイ](#vpsへのデプロイ)
4. [systemdサービスの設定](#systemdサービスの設定)
5. [起動と確認](#起動と確認)
6. [デプロイスクリプト](#デプロイスクリプト)
7. [トラブルシューティング](#トラブルシューティング)

## 前提条件

### ローカル環境

- Rustがインストールされていること（`rustup`を使用）
- `cargo`コマンドが使用可能であること
- VPSへのSSH接続が可能であること

### VPS環境

- Ubuntu/Debian系のLinux（推奨）
- PostgreSQLが起動しており、接続可能であること
- systemdが使用可能であること
- `/opt/snapshot-service`ディレクトリを作成する権限があること

## ローカルでのビルド

### 1. リリースビルド

```bash
cd snapshot-service
./build.sh
```

または

```bash
cargo build --release
```

ビルドが成功すると、`target/release/snapshot-service`にバイナリが生成されます。

### 2. ビルド確認

```bash
# バイナリの存在確認
ls -lh target/release/snapshot-service

# バイナリの情報確認
file target/release/snapshot-service
```

## VPSへのデプロイ

### 1. VPS上でディレクトリを作成

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# ディレクトリを作成
sudo mkdir -p /opt/snapshot-service
sudo chmod 755 /opt/snapshot-service
```

### 2. バイナリと設定ファイルをコピー

**ローカル環境から実行：**

```bash
# バイナリをコピー
scp target/release/snapshot-service root@your-vps-ip:/opt/snapshot-service/

# 設定ファイルをコピー
scp config.toml root@your-vps-ip:/opt/snapshot-service/

# systemdサービスファイルをコピー
scp snapshot-service.service root@your-vps-ip:/tmp/
```

### 3. VPS上で設定を確認・編集

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# 設定ファイルを編集
nano /opt/snapshot-service/config.toml
```

**重要な設定項目：**

- `[postgres]`セクション: PostgreSQL接続情報
  - `host`: PostgreSQLのホスト（別ホストの場合はIPアドレス）
  - `port`: PostgreSQLのポート（デフォルト: 5432）
  - `database`: データベース名（例: `exch_sim`）
  - `user`: PostgreSQLユーザー名
  - `password`: PostgreSQLパスワード

- `[snapshot]`セクション: スナップショット設定
  - `interval_seconds`: スナップショット収集間隔（デフォルト: 1秒）
  - `max_levels`: レベル数（デフォルト: 8）
  - `queue_size_limit`: キューサイズ制限（デフォルト: 500）

### 4. バイナリに実行権限を付与

```bash
# VPS上で実行
sudo chmod +x /opt/snapshot-service/snapshot-service
```

## systemdサービスの設定

### 1. サービスファイルを配置

```bash
# VPS上で実行
sudo mv /tmp/snapshot-service.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/snapshot-service.service
```

### 2. systemdをリロード

```bash
sudo systemctl daemon-reload
```

### 3. サービスファイルの確認

`/etc/systemd/system/snapshot-service.service`の内容：

```ini
[Unit]
Description=Exchange Simulator Snapshot Service (Rust)
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/snapshot-service

# Environment variables
Environment="CONFIG_PATH=/opt/snapshot-service/config.toml"
Environment="RUST_LOG=snapshot_service=info"

# Executable
ExecStart=/opt/snapshot-service/snapshot-service

# Restart settings
Restart=always
RestartSec=10
StartLimitInterval=300
StartLimitBurst=5

# Resource limits
MemoryLimit=100M
CPUQuota=50%

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=snapshot-service

[Install]
WantedBy=multi-user.target
```

## 起動と確認

### 1. サービスを有効化

```bash
sudo systemctl enable snapshot-service
```

### 2. サービスを起動

```bash
sudo systemctl start snapshot-service
```

### 3. ステータス確認

```bash
sudo systemctl status snapshot-service
```

正常に起動している場合、以下のような表示になります：

```
● snapshot-service.service - Exchange Simulator Snapshot Service (Rust)
     Loaded: loaded (/etc/systemd/system/snapshot-service.service; enabled; vendor preset: enabled)
     Active: active (running) since ...
```

### 4. ログ確認

```bash
# リアルタイムログ
sudo journalctl -u snapshot-service -f

# 最新100行のログ
sudo journalctl -u snapshot-service -n 100

# エラーログのみ
sudo journalctl -u snapshot-service -p err
```

### 5. 動作確認

ログに以下のようなメッセージが表示されれば正常です：

```
INFO snapshot_service: Starting Snapshot Service...
INFO snapshot_service: Configuration loaded from: /opt/snapshot-service/config.toml
INFO snapshot_service: Connected to PostgreSQL: ...
INFO snapshot_service: WebSocket clients started
INFO snapshot_service::websocket::bitflyer: Bitflyer WebSocket connection established
INFO snapshot_service::websocket::gmo: GMO WebSocket connection established
INFO snapshot_service: Snapshot collector started
INFO snapshot_service: PostgreSQL writer initialized
INFO snapshot_service: Snapshot Service is running. Press Ctrl+C to stop.
```

### 6. データベースへの書き込み確認

```bash
# PostgreSQLに接続
psql -U postgres -d exch_sim

# 最新のスナップショットを確認
SELECT symbol, timestamp, COUNT(*) as price_levels
FROM market_board_snapshots s
JOIN market_board_price_levels p ON s.id = p.snapshot_id
GROUP BY s.id, symbol, timestamp
ORDER BY timestamp DESC
LIMIT 10;
```

## デプロイスクリプト

### 自動デプロイスクリプト

`deploy.sh`を作成：

```bash
#!/bin/bash

set -e  # エラー時に停止

# 設定
VPS_USER="root"
VPS_HOST="your-vps-ip"  # ここをVPSのIPアドレスに変更
VPS_PATH="/opt/snapshot-service"
SERVICE_NAME="snapshot-service"

echo "=== Rust Snapshot Service Deployment ==="

# 1. ビルド
echo "Step 1: Building release binary..."
cd snapshot-service
./build.sh
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# 2. バイナリをコピー
echo "Step 2: Copying binary to VPS..."
scp target/release/snapshot-service ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/

# 3. 設定ファイルをコピー（既存のconfig.tomlを上書きしない）
echo "Step 3: Checking config.toml..."
if [ -f config.toml ]; then
    read -p "Overwrite config.toml on VPS? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        scp config.toml ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/
    fi
else
    echo "Warning: config.toml not found. Please create it manually on VPS."
fi

# 4. systemdサービスファイルをコピー
echo "Step 4: Copying systemd service file..."
scp snapshot-service.service ${VPS_USER}@${VPS_HOST}:/tmp/

# 5. VPS上で設定を適用
echo "Step 5: Configuring service on VPS..."
ssh ${VPS_USER}@${VPS_HOST} << 'EOF'
    # バイナリに実行権限を付与
    chmod +x /opt/snapshot-service/snapshot-service
    
    # systemdサービスファイルを配置
    sudo mv /tmp/snapshot-service.service /etc/systemd/system/
    sudo chmod 644 /etc/systemd/system/snapshot-service.service
    
    # systemdをリロード
    sudo systemctl daemon-reload
    
    echo "Service configuration completed."
EOF

# 6. サービスを再起動
echo "Step 6: Restarting service..."
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl restart ${SERVICE_NAME}"

# 7. ステータス確認
echo "Step 7: Checking service status..."
sleep 2
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl status ${SERVICE_NAME} --no-pager"

echo ""
echo "=== Deployment completed! ==="
echo "To view logs: ssh ${VPS_USER}@${VPS_HOST} 'sudo journalctl -u ${SERVICE_NAME} -f'"
```

実行権限を付与：

```bash
chmod +x deploy.sh
```

使用方法：

```bash
# スクリプトを編集してVPS情報を設定
nano deploy.sh

# デプロイ実行
./deploy.sh
```

## トラブルシューティング

### サービスが起動しない

```bash
# ステータス確認
sudo systemctl status snapshot-service

# 詳細ログ確認
sudo journalctl -u snapshot-service -n 50

# バイナリが実行可能か確認
ls -lh /opt/snapshot-service/snapshot-service
file /opt/snapshot-service/snapshot-service
```

### PostgreSQL接続エラー

```bash
# 設定ファイルを確認
cat /opt/snapshot-service/config.toml | grep -A 10 "\[postgres\]"

# PostgreSQL接続テスト
psql -h localhost -U postgres -d exch_sim -c "SELECT 1;"

# 別ホストの場合はネットワーク接続を確認
ping <postgres-host>
telnet <postgres-host> 5432
```

### WebSocket接続エラー

```bash
# ログでWebSocket接続エラーを確認
sudo journalctl -u snapshot-service | grep -i "websocket\|connection"

# ネットワーク接続を確認
curl -I https://ws.lightstream.bitflyer.com/json-rpc
curl -I https://api.coin.z.com/ws/public/v1
```

### データが保存されない

```bash
# ログでスナップショット収集を確認
sudo journalctl -u snapshot-service | grep -i "snapshot\|collect"

# データベースに接続して確認
psql -U postgres -d exch_sim -c "
SELECT 
    symbol, 
    COUNT(*) as snapshot_count,
    MIN(timestamp) as first_snapshot,
    MAX(timestamp) as last_snapshot
FROM market_board_snapshots
GROUP BY symbol
ORDER BY symbol;
"
```

### メモリ使用量が高い

```bash
# プロセスのメモリ使用量を確認
ps aux | grep snapshot-service

# systemdのリソース制限を確認
systemctl show snapshot-service | grep -i memory
```

### サービスを停止・再起動

```bash
# 停止
sudo systemctl stop snapshot-service

# 再起動
sudo systemctl restart snapshot-service

# 無効化（自動起動を無効化）
sudo systemctl disable snapshot-service
```

### ログローテーション

systemdのログは自動的にローテーションされますが、手動で確認：

```bash
# ログのサイズ確認
sudo journalctl -u snapshot-service --disk-usage

# 古いログを削除（7日以上前）
sudo journalctl --vacuum-time=7d
```

## 更新手順

既存のサービスを更新する場合：

```bash
# 1. ローカルでビルド
cd snapshot-service
./build.sh

# 2. バイナリをコピー
scp target/release/snapshot-service root@your-vps-ip:/opt/snapshot-service/

# 3. サービスを再起動
ssh root@your-vps-ip "sudo systemctl restart snapshot-service"

# 4. ステータス確認
ssh root@your-vps-ip "sudo systemctl status snapshot-service"
```

## セキュリティ考慮事項

1. **設定ファイルの権限**: `config.toml`にパスワードが含まれるため、適切な権限を設定：

```bash
sudo chmod 600 /opt/snapshot-service/config.toml
sudo chown root:root /opt/snapshot-service/config.toml
```

2. **専用ユーザーの作成**: rootユーザーで実行する代わりに、専用ユーザーを作成：

```bash
sudo useradd -r -s /bin/false snapshot-service
sudo chown -R snapshot-service:snapshot-service /opt/snapshot-service
```

そして、`snapshot-service.service`の`User`を変更：

```ini
User=snapshot-service
```

3. **ファイアウォール**: 別ホストのPostgreSQLに接続する場合は、適切なファイアウォール設定が必要です。

## 関連ファイル

- `target/release/snapshot-service` - ビルドされたバイナリ
- `config.toml` - 設定ファイル
- `snapshot-service.service` - systemdサービスファイル
- `deploy.sh` - デプロイスクリプト（オプション）

