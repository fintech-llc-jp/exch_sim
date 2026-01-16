# VPS上でビルドする手順

VPSにSSH接続した状態で、以下のコマンドを実行してください。

## 1. 依存関係のインストール

```bash
apt-get update
apt-get install -y pkg-config libssl-dev build-essential curl
```

## 2. Rust環境の確認・設定

```bash
# Rustがインストールされているか確認
if ! command -v cargo &> /dev/null; then
    echo "Installing Rust..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source $HOME/.cargo/env
    export PATH="$HOME/.cargo/bin:$PATH"
else
    source $HOME/.cargo/env
    export PATH="$HOME/.cargo/bin:$PATH"
fi

# バージョン確認
rustc --version
cargo --version
```

## 3. ビルド実行

```bash
cd /opt/snapshot-service
cargo build --release
```

**注意**: 初回ビルドは10-30分かかる場合があります。

## 4. ビルド結果の確認

```bash
# バイナリの存在確認
ls -lh target/release/snapshot-service

# バイナリの情報確認（アーキテクチャ）
file target/release/snapshot-service

# 実行権限を付与
chmod +x target/release/snapshot-service
```

## 5. systemdサービスファイルの修正

```bash
# ExecStartパスを修正（VPS上でビルドした場合）
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# 修正内容を確認
grep ExecStart /etc/systemd/system/snapshot-service.service
```

## 6. systemdのリロードとサービス再起動

```bash
# systemdをリロード
sudo systemctl daemon-reload

# サービスを再起動
sudo systemctl restart snapshot-service

# ステータス確認
sleep 2
sudo systemctl status snapshot-service --no-pager -l
```

## 7. ログ確認

```bash
# リアルタイムログ
sudo journalctl -u snapshot-service -f

# 最新100行
sudo journalctl -u snapshot-service -n 100
```

## トラブルシューティング

### ビルドが失敗する場合

```bash
# 詳細ログでビルド
cargo build --release --verbose 2>&1 | tee build.log

# エラー部分を確認
grep -i error build.log
```

### メモリ不足でビルドが失敗する場合

```bash
# 並列ビルド数を制限
cargo build --release -j 1
```

### バイナリが見つからない場合

```bash
# targetディレクトリを確認
ls -la target/release/

# ビルドをクリーンして再ビルド
cargo clean
cargo build --release
```

