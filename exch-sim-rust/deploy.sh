#!/bin/bash

# ExchSim Rust VPSデプロイスクリプト
# 使用方法: ./deploy.sh

set -e  # エラー時に終了

echo "ExchSim Rust VPSデプロイスクリプト"
echo "===================================="

# Cargoのパスを設定
if [ -f "$HOME/.cargo/env" ]; then
    source "$HOME/.cargo/env"
fi

# Cargoが利用可能か確認
if ! command -v cargo &> /dev/null; then
    echo "エラー: cargoコマンドが見つかりません。"
    echo "Rustがインストールされているか確認してください。"
    exit 1
fi

# 設定ファイルの確認
if [ ! -f "config.toml" ]; then
    echo "警告: config.tomlが見つかりません。"
    if [ -f "config.toml.example" ]; then
        echo "config.toml.exampleからコピーしますか？ (y/n)"
        read -r answer
        if [ "$answer" = "y" ]; then
            cp config.toml.example config.toml
            echo "config.tomlを作成しました。必要に応じて編集してください。"
            echo "編集後、再度このスクリプトを実行してください。"
            exit 0
        fi
    fi
    echo "config.tomlが必要です。終了します。"
    exit 1
fi

# リリースビルド
echo ""
echo "リリースビルドを開始します..."
cargo build --release

if [ $? -ne 0 ]; then
    echo "エラー: ビルドに失敗しました。"
    exit 1
fi

echo "✓ ビルド完了"
echo ""

# バイナリの場所を表示
BINARY_PATH="./target/release/exch-sim-rust"
if [ -f "$BINARY_PATH" ]; then
    echo "バイナリ: $BINARY_PATH"
    ls -lh "$BINARY_PATH"
    echo ""
    echo "デプロイ完了！"
    echo ""
    echo "実行方法:"
    echo "  $BINARY_PATH"
    echo ""
    echo "または、systemdサービスとして設定する場合:"
    echo "  sudo cp exch-sim-rust.service /etc/systemd/system/"
    echo "  sudo systemctl daemon-reload"
    echo "  sudo systemctl enable exch-sim-rust"
    echo "  sudo systemctl start exch-sim-rust"
else
    echo "エラー: バイナリが見つかりません: $BINARY_PATH"
    exit 1
fi

