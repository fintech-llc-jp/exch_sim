# サービス起動エラー修正手順（VPS上で実行）

## 問題

`status=203/EXEC`エラーが発生しています。これは、systemdサービスファイルの`ExecStart`パスが間違っているか、バイナリが存在しないことを示しています。

## 修正手順（VPS上で実行）

### 1. バイナリの存在確認

```bash
# バイナリが存在するか確認
ls -lh /opt/snapshot-service/target/release/snapshot-service

# 存在しない場合は、ビルドが必要
cd /opt/snapshot-service
source $HOME/.cargo/env
cargo build --release
```

### 2. systemdサービスファイルの確認

```bash
# 現在のExecStartパスを確認
grep ExecStart /etc/systemd/system/snapshot-service.service
```

### 3. systemdサービスファイルを修正

```bash
# ExecStartパスを修正（VPS上でビルドした場合）
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# 修正内容を確認
grep ExecStart /etc/systemd/system/snapshot-service.service
```

正しいパスは：
```
ExecStart=/opt/snapshot-service/target/release/snapshot-service
```

### 4. バイナリの実行権限確認

```bash
# 実行権限を付与
chmod +x /opt/snapshot-service/target/release/snapshot-service

# 権限を確認
ls -l /opt/snapshot-service/target/release/snapshot-service
```

### 5. バイナリを直接実行してテスト

```bash
# バイナリを直接実行してエラーを確認
/opt/snapshot-service/target/release/snapshot-service

# または、設定ファイルを指定
CONFIG_PATH=/opt/snapshot-service/config.toml /opt/snapshot-service/target/release/snapshot-service
```

### 6. systemdをリロードしてサービスを再起動

```bash
# systemdをリロード
sudo systemctl daemon-reload

# サービスを再起動
sudo systemctl restart snapshot-service

# ステータス確認
sleep 2
sudo systemctl status snapshot-service --no-pager -l
```

### 7. ログ確認

```bash
# 最新のログを確認
sudo journalctl -u snapshot-service -n 50 --no-pager

# リアルタイムログ
sudo journalctl -u snapshot-service -f
```

## 一括実行スクリプト

以下のコマンドを順番に実行：

```bash
# 1. バイナリの確認とビルド（必要に応じて）
cd /opt/snapshot-service
if [ ! -f "target/release/snapshot-service" ]; then
    echo "Building binary..."
    source $HOME/.cargo/env
    cargo build --release
fi

# 2. 実行権限を付与
chmod +x target/release/snapshot-service

# 3. systemdサービスファイルを修正
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# 4. 修正内容を確認
echo "Updated ExecStart:"
grep ExecStart /etc/systemd/system/snapshot-service.service

# 5. systemdをリロード
sudo systemctl daemon-reload

# 6. サービスを再起動
sudo systemctl restart snapshot-service

# 7. ステータス確認
sleep 2
sudo systemctl status snapshot-service --no-pager -l
```

## トラブルシューティング

### バイナリが存在しない場合

```bash
# ビルドを実行
cd /opt/snapshot-service
source $HOME/.cargo/env
cargo build --release

# ビルドが成功したか確認
ls -lh target/release/snapshot-service
```

### ビルドが失敗する場合

```bash
# 依存関係をインストール
apt-get update
apt-get install -y pkg-config libssl-dev build-essential curl

# 再度ビルド
cargo build --release
```

### サービスファイルが存在しない場合

```bash
# サービスファイルをコピー
sudo cp /opt/snapshot-service/snapshot-service.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/snapshot-service.service

# ExecStartパスを修正
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# systemdをリロード
sudo systemctl daemon-reload
```

