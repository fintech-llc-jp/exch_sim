#!/bin/bash

# ローカルテスト用起動スクリプト

echo "ExchSim Rust ローカル起動スクリプト"
echo "======================================"

# Cargoのパスを設定
if [ -f "$HOME/.cargo/env" ]; then
    source "$HOME/.cargo/env"
fi

# Cargoが利用可能か確認
if ! command -v cargo &> /dev/null; then
    echo "エラー: cargoコマンドが見つかりません。"
    echo "Rustがインストールされているか確認してください。"
    echo ""
    echo "インストール方法:"
    echo "  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    echo "  インストール後、ターミナルを再起動するか、以下を実行:"
    echo "  source \$HOME/.cargo/env"
    exit 1
fi

# 設定ファイルの確認
if [ ! -f "config.toml" ]; then
    echo "警告: config.tomlが見つかりません。config.toml.exampleからコピーしますか？ (y/n)"
    read -r answer
    if [ "$answer" = "y" ]; then
        cp config.toml.example config.toml
        echo "config.tomlを作成しました。必要に応じて編集してください。"
    else
        echo "config.tomlが必要です。終了します。"
        exit 1
    fi
fi

# PostgreSQL接続確認（オプション）
echo ""
echo "PostgreSQL接続を確認しています..."
if command -v psql &> /dev/null; then
    PGPASSWORD="${PGPASSWORD:-postgres123}" psql -h localhost -U postgres -d exch_sim -c "SELECT 1;" &> /dev/null
    if [ $? -eq 0 ]; then
        echo "✓ PostgreSQL接続成功"
    else
        echo "⚠ PostgreSQL接続に失敗しました。データベースが起動しているか確認してください。"
        echo "  手動で確認: psql -h localhost -U postgres -d exch_sim"
    fi
else
    echo "⚠ psqlコマンドが見つかりません。PostgreSQL接続は確認できませんでした。"
fi

echo ""
echo "アプリケーションを起動します..."
echo "停止するには Ctrl+C を押してください。"
echo ""

# アプリケーション起動
cargo run

