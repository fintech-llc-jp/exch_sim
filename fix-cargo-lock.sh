#!/bin/bash
# Cargoのビルドロックを解除するスクリプト

echo "Cargoのビルドロックを解除します..."

# 1. 実行中のcargoプロセスを確認
echo "実行中のcargoプロセスを確認中..."
ps aux | grep cargo | grep -v grep

# 2. 実行中のcargoプロセスを終了
echo "実行中のcargoプロセスを終了中..."
pkill -9 cargo || echo "実行中のcargoプロセスはありませんでした"

# 3. ロックファイルを削除
echo "ロックファイルを削除中..."
find . -name ".cargo-lock" -type f -delete 2>/dev/null
find . -path "*/target/.rustc_info.json.lock" -type f -delete 2>/dev/null
find . -path "*/target/.cargo-lock" -type f -delete 2>/dev/null

# 4. ビルドディレクトリのロックを確認
echo "ビルドディレクトリのロックファイルを確認中..."
if [ -f "target/.cargo-lock" ]; then
    echo "ロックファイルが見つかりました: target/.cargo-lock"
    rm -f target/.cargo-lock
    echo "ロックファイルを削除しました"
fi

# 5. ロックディレクトリを確認
if [ -d "target/.cargo-lock" ]; then
    echo "ロックディレクトリが見つかりました: target/.cargo-lock"
    rm -rf target/.cargo-lock
    echo "ロックディレクトリを削除しました"
fi

echo "完了しました。再度 cargo build を実行してください。"
