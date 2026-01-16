#!/bin/bash
set -e

echo "Building Snapshot Service..."

# Build release binary
cargo build --release

echo "Build completed!"
echo "Binary location: target/release/snapshot-service"

