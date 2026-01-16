#!/bin/bash

# VPS上で直接実行する依存関係インストールスクリプト
# 使用方法: VPSにSSH接続してから実行: ./install_dependencies.sh

set -e

echo "=== Installing dependencies ==="

echo "=== Step 1: Updating package list ==="
apt-get update

echo "=== Step 2: Installing build dependencies ==="
apt-get install -y \
    pkg-config \
    libssl-dev \
    build-essential \
    curl

echo "=== Step 3: Verifying installations ==="
pkg-config --version
pkg-config --modversion openssl || echo "OpenSSL not found via pkg-config, but libssl-dev is installed"

echo "✅ Dependencies installed successfully"
echo ""
echo "Next step: cargo build --release"

