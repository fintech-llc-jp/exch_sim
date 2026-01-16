# VPS上でビルドする方法（アーキテクチャ不一致エラー対策）

## 問題

macOSでビルドしたバイナリをLinux VPSで実行しようとすると、`Exec format error`が発生します。
これは、アーキテクチャの不一致が原因です。

## 解決策: VPS上で直接ビルド

VPS上でRustをインストールし、そこで直接ビルドする方法が最も確実です。

## 手順

### 1. VPSにRustをインストール

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# Rustをインストール
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# 環境変数を読み込む
source $HOME/.cargo/env

# インストール確認
rustc --version
cargo --version
```

### 2. ソースコードをVPSにコピー

**ローカル環境から実行：**

```bash
# snapshot-serviceディレクトリ全体をコピー
cd /Users/sakamoto.yukio/workspace/exch_sim
scp -r snapshot-service root@your-vps-ip:/opt/

# または、rsyncを使用（より効率的）
rsync -avz --exclude 'target' --exclude '*.log' snapshot-service/ root@your-vps-ip:/opt/snapshot-service/
```

### 3. VPS上でビルド

```bash
# VPSにSSH接続
ssh root@your-vps-ip

# ディレクトリに移動
cd /opt/snapshot-service

# リリースビルド
cargo build --release

# ビルド確認
ls -lh target/release/snapshot-service
```

### 4. 設定ファイルの準備

```bash
# 設定ファイルをコピーまたは作成
cp config.toml.example config.toml

# 設定を編集
nano config.toml
```

**重要な設定項目：**
- `[postgres]`セクション: PostgreSQL接続情報
- `[snapshot]`セクション: スナップショット間隔とレベル数

### 5. systemdサービスの設定

```bash
# サービスファイルを配置
sudo cp snapshot-service.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/snapshot-service.service

# systemdをリロード
sudo systemctl daemon-reload

# サービスを有効化
sudo systemctl enable snapshot-service

# サービスを起動
sudo systemctl start snapshot-service

# ステータス確認
sudo systemctl status snapshot-service
```

### 6. ログ確認

```bash
# リアルタイムログ
sudo journalctl -u snapshot-service -f

# 最新100行
sudo journalctl -u snapshot-service -n 100
```

## 自動化スクリプト

### ローカルから実行するデプロイスクリプト

`deploy_vps_build.sh`を作成：

```bash
#!/bin/bash

set -e

# 設定
VPS_USER="root"
VPS_HOST="your-vps-ip"
VPS_PATH="/opt/snapshot-service"

echo "=== Deploying Snapshot Service (Build on VPS) ==="

# 1. ソースコードをコピー（targetディレクトリを除外）
echo "Step 1: Copying source code to VPS..."
rsync -avz --exclude 'target' --exclude '*.log' --exclude '.git' \
    snapshot-service/ ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/

# 2. VPS上でビルドと設定
echo "Step 2: Building and configuring on VPS..."
ssh ${VPS_USER}@${VPS_HOST} << 'EOF'
    set -e
    cd /opt/snapshot-service
    
    # Rustがインストールされているか確認
    if ! command -v cargo &> /dev/null; then
        echo "Installing Rust..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        source $HOME/.cargo/env
    else
        source $HOME/.cargo/env
    fi
    
    # ビルド
    echo "Building release binary..."
    cargo build --release
    
    # 設定ファイルが存在しない場合は作成
    if [ ! -f config.toml ]; then
        cp config.toml.example config.toml
        echo "⚠️  Please edit config.toml before starting the service"
    fi
    
    # systemdサービスを設定
    sudo cp snapshot-service.service /etc/systemd/system/
    sudo chmod 644 /etc/systemd/system/snapshot-service.service
    sudo systemctl daemon-reload
    
    echo "✅ Build and configuration completed"
EOF

# 3. サービスを再起動
echo "Step 3: Restarting service..."
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl restart snapshot-service"

# 4. ステータス確認
echo "Step 4: Checking service status..."
sleep 2
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl status snapshot-service --no-pager -l" || true

echo ""
echo "=== Deployment completed! ==="
```

実行権限を付与：

```bash
chmod +x deploy_vps_build.sh
```

## トラブルシューティング

### Rustがインストールされていない

```bash
# Rustをインストール
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

### ビルドに時間がかかる

初回ビルドは依存関係のコンパイルに時間がかかります（10-30分程度）。
2回目以降は差分ビルドのため、数分で完了します。

### メモリ不足でビルドが失敗する

```bash
# 並列ビルド数を制限
cargo build --release -j 1

# または、スワップを有効化
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

### ビルド後のバイナリサイズが大きい

リリースビルドは最適化されていますが、サイズが大きい場合は：

```bash
# バイナリをストリップ（デバッグ情報を削除）
strip target/release/snapshot-service
```

## 更新手順

ソースコードを更新した場合：

```bash
# ローカルから実行
./deploy_vps_build.sh

# または、手動で
rsync -avz --exclude 'target' snapshot-service/ root@your-vps-ip:/opt/snapshot-service/
ssh root@your-vps-ip "cd /opt/snapshot-service && cargo build --release && sudo systemctl restart snapshot-service"
```

