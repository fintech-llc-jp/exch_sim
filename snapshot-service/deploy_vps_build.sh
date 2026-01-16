#!/bin/bash

set -e

# ============================================
# 設定: ここを環境に合わせて変更してください
# ============================================
VPS_USER="root"
VPS_HOST="your-vps-ip"  # VPSのIPアドレスまたはホスト名
VPS_PATH="/opt/snapshot-service"
SERVICE_NAME="snapshot-service"

# ============================================
# デプロイ処理
# ============================================

echo "=== Deploying Snapshot Service (Build on VPS) ==="
echo "VPS: ${VPS_USER}@${VPS_HOST}"
echo "Path: ${VPS_PATH}"
echo ""

# 1. ソースコードをコピー（targetディレクトリを除外）
echo "Step 1: Copying source code to VPS..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
rsync -avz --exclude 'target' --exclude '*.log' --exclude '.git' \
    ./ ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/ 2>&1 | grep -v "^sending\|^sent\|^total" || true
echo "✅ Source code copied"

# 2. VPS上でビルドと設定
echo ""
echo "Step 2: Building and configuring on VPS..."
ssh ${VPS_USER}@${VPS_HOST} << 'EOF'
    set -e
    cd /opt/snapshot-service
    
    # Rustがインストールされているか確認
    if ! command -v cargo &> /dev/null; then
        echo "Installing Rust..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        source $HOME/.cargo/env
        export PATH="$HOME/.cargo/bin:$PATH"
    else
        # 既にインストールされている場合も環境変数を読み込む
        if [ -f "$HOME/.cargo/env" ]; then
            source $HOME/.cargo/env
        fi
        export PATH="$HOME/.cargo/bin:$PATH"
    fi
    
    # ビルド
    echo "Building release binary..."
    cargo build --release
    
    # 設定ファイルが存在しない場合は作成
    if [ ! -f config.toml ]; then
        if [ -f config.toml.example ]; then
            cp config.toml.example config.toml
            echo "⚠️  Created config.toml from example. Please edit it before starting the service."
        else
            echo "⚠️  Warning: config.toml not found. Please create it manually."
        fi
    fi
    
    # バイナリに実行権限を付与
    chmod +x target/release/snapshot-service
    
    # systemdサービスを設定
    if [ -f snapshot-service.service ]; then
        sudo cp snapshot-service.service /etc/systemd/system/
        sudo chmod 644 /etc/systemd/system/snapshot-service.service
        sudo systemctl daemon-reload
        echo "✅ Systemd service configured"
    else
        echo "⚠️  Warning: snapshot-service.service not found"
    fi
    
    echo "✅ Build and configuration completed"
EOF

# 3. サービスを再起動（設定ファイルが存在する場合のみ）
echo ""
echo "Step 3: Restarting service..."
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl restart ${SERVICE_NAME}" 2>&1 || {
    echo "⚠️  Service restart failed. This is normal if config.toml needs to be edited first."
    echo "   Please edit config.toml and then run: sudo systemctl start ${SERVICE_NAME}"
}

# 4. ステータス確認
echo ""
echo "Step 4: Checking service status..."
sleep 2
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl status ${SERVICE_NAME} --no-pager -l" || true

echo ""
echo "=== Deployment completed! ==="
echo ""
echo "Next steps:"
echo "  1. Edit config.toml if needed:"
echo "     ssh ${VPS_USER}@${VPS_HOST} 'nano ${VPS_PATH}/config.toml'"
echo ""
echo "  2. Start/restart the service:"
echo "     ssh ${VPS_USER}@${VPS_HOST} 'sudo systemctl restart ${SERVICE_NAME}'"
echo ""
echo "  3. View logs:"
echo "     ssh ${VPS_USER}@${VPS_HOST} 'sudo journalctl -u ${SERVICE_NAME} -f'"

