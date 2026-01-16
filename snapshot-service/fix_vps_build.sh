#!/bin/bash

# VPS上で直接実行するビルドとサービス修正スクリプト
# 使用方法: VPSにSSH接続してから実行: ./fix_vps_build.sh

set -e

cd /opt/snapshot-service

echo "=== Step 1: Checking Rust installation ==="
if ! command -v cargo &> /dev/null; then
    echo "Installing Rust..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source $HOME/.cargo/env
    export PATH="$HOME/.cargo/bin:$PATH"
else
    echo "Rust is already installed"
    if [ -f "$HOME/.cargo/env" ]; then
        source $HOME/.cargo/env
    fi
    export PATH="$HOME/.cargo/bin:$PATH"
fi

echo "=== Step 2: Building release binary ==="
cargo build --release

echo "=== Step 3: Checking binary ==="
if [ -f "target/release/snapshot-service" ]; then
    chmod +x target/release/snapshot-service
    ls -lh target/release/snapshot-service
    file target/release/snapshot-service
    echo "✅ Binary created successfully"
else
    echo "❌ Binary not found!"
    exit 1
fi

echo "=== Step 4: Fixing systemd service file ==="
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

echo "=== Step 5: Verifying service file ==="
grep ExecStart /etc/systemd/system/snapshot-service.service

echo "=== Step 6: Reloading systemd ==="
sudo systemctl daemon-reload

echo "=== Step 7: Restarting service ==="
sudo systemctl restart snapshot-service

echo "=== Step 8: Checking service status ==="
sleep 2
sudo systemctl status snapshot-service --no-pager -l || true

echo ""
echo "=== Fix completed! ==="
echo "To view logs: sudo journalctl -u snapshot-service -f"

