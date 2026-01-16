#!/bin/bash

set -e  # エラー時に停止

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

echo "=== Rust Snapshot Service Deployment ==="
echo "VPS: ${VPS_USER}@${VPS_HOST}"
echo "Path: ${VPS_PATH}"
echo ""

# 1. ビルド
echo "Step 1: Building release binary..."
cd "$(dirname "$0")"
./build.sh
if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi
echo "✅ Build completed"

# 2. バイナリをコピー
echo ""
echo "Step 2: Copying binary to VPS..."
scp target/release/snapshot-service ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/
echo "✅ Binary copied"

# 3. 設定ファイルをコピー（確認付き）
echo ""
echo "Step 3: Config file..."
if [ -f config.toml ]; then
    read -p "Overwrite config.toml on VPS? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        scp config.toml ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/
        echo "✅ Config file copied"
    else
        echo "⏭️  Config file skipped"
    fi
else
    echo "⚠️  Warning: config.toml not found. Please create it manually on VPS."
fi

# 4. systemdサービスファイルをコピー
echo ""
echo "Step 4: Copying systemd service file..."
scp snapshot-service.service ${VPS_USER}@${VPS_HOST}:/tmp/
echo "✅ Service file copied"

# 5. VPS上で設定を適用
echo ""
echo "Step 5: Configuring service on VPS..."
ssh ${VPS_USER}@${VPS_HOST} << 'EOF'
    set -e
    
    # バイナリに実行権限を付与
    chmod +x /opt/snapshot-service/snapshot-service
    
    # systemdサービスファイルを配置
    sudo mv /tmp/snapshot-service.service /etc/systemd/system/
    sudo chmod 644 /etc/systemd/system/snapshot-service.service
    
    # systemdをリロード
    sudo systemctl daemon-reload
    
    echo "✅ Service configuration completed"
EOF

# 6. サービスを再起動
echo ""
echo "Step 6: Restarting service..."
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl restart ${SERVICE_NAME}"
echo "✅ Service restarted"

# 7. ステータス確認
echo ""
echo "Step 7: Checking service status..."
sleep 2
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl status ${SERVICE_NAME} --no-pager -l" || true

echo ""
echo "=== Deployment completed! ==="
echo ""
echo "Useful commands:"
echo "  View logs:      ssh ${VPS_USER}@${VPS_HOST} 'sudo journalctl -u ${SERVICE_NAME} -f'"
echo "  Check status:   ssh ${VPS_USER}@${VPS_HOST} 'sudo systemctl status ${SERVICE_NAME}'"
echo "  Restart:        ssh ${VPS_USER}@${VPS_HOST} 'sudo systemctl restart ${SERVICE_NAME}'"
echo "  Stop:           ssh ${VPS_USER}@${VPS_HOST} 'sudo systemctl stop ${SERVICE_NAME}'"

