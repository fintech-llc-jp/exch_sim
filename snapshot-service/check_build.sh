#!/bin/bash

# VPS上でビルド状況を確認し、必要に応じてビルドを実行するスクリプト

VPS_USER="root"
VPS_HOST="77.42.74.155"

echo "=== Checking and Building on VPS ==="

ssh ${VPS_USER}@${VPS_HOST} << 'EOF'
    set -e
    
    cd /opt/snapshot-service
    
    echo "=== Step 1: Checking Rust installation ==="
    if ! command -v cargo &> /dev/null; then
        echo "Rust is not installed. Installing..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        source $HOME/.cargo/env
        export PATH="$HOME/.cargo/bin:$PATH"
    else
        echo "Rust is installed: $(rustc --version)"
        if [ -f "$HOME/.cargo/env" ]; then
            source $HOME/.cargo/env
        fi
        export PATH="$HOME/.cargo/bin:$PATH"
    fi
    
    echo "=== Step 2: Checking Cargo ==="
    cargo --version
    
    echo "=== Step 3: Checking source files ==="
    ls -la src/ || echo "src/ directory not found"
    ls -la Cargo.toml || echo "Cargo.toml not found"
    
    echo "=== Step 4: Building release binary ==="
    echo "This may take 10-30 minutes on first build..."
    cargo build --release 2>&1 | tee /tmp/cargo_build.log
    
    echo "=== Step 5: Checking build result ==="
    if [ -f "target/release/snapshot-service" ]; then
        chmod +x target/release/snapshot-service
        ls -lh target/release/snapshot-service
        file target/release/snapshot-service
        echo "✅ Build successful!"
    else
        echo "❌ Build failed! Check /tmp/cargo_build.log for details"
        echo "Last 50 lines of build log:"
        tail -50 /tmp/cargo_build.log
        exit 1
    fi
EOF

echo ""
echo "=== Check completed! ==="

