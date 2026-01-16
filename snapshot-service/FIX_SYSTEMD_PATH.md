# systemdサービスファイルのパス修正

## 問題

直接実行では動作するが、systemctlで起動すると`status=203/EXEC`エラーが発生します。

## 原因

systemdサービスファイルの`ExecStart`パスが、実際のバイナリの場所と一致していない可能性があります。

## 解決方法

### 1. バイナリの実際の場所を確認

```bash
# バイナリがどこにあるか確認
ls -lh /opt/snapshot-service/snapshot-service
ls -lh /opt/snapshot-service/target/release/snapshot-service

# どちらが存在するか確認
which snapshot-service 2>/dev/null || echo "Not in PATH"
```

### 2. systemdサービスファイルの現在の設定を確認

```bash
# 現在のExecStartパスを確認
grep ExecStart /etc/systemd/system/snapshot-service.service
```

### 3. パスを修正

#### パターンA: バイナリが `/opt/snapshot-service/snapshot-service` にある場合

```bash
# サービスファイルを確認（既に正しい可能性あり）
cat /etc/systemd/system/snapshot-service.service | grep ExecStart

# もし違うパスになっている場合は修正
sudo sed -i 's|ExecStart=.*|ExecStart=/opt/snapshot-service/snapshot-service|' /etc/systemd/system/snapshot-service.service
```

#### パターンB: バイナリが `/opt/snapshot-service/target/release/snapshot-service` にある場合

```bash
# サービスファイルを修正
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service
```

### 4. 修正内容を確認

```bash
# 修正後のExecStartパスを確認
grep ExecStart /etc/systemd/system/snapshot-service.service

# バイナリが存在するか確認
ls -lh $(grep ExecStart /etc/systemd/system/snapshot-service.service | cut -d'=' -f2)
```

### 5. バイナリの実行権限を確認

```bash
# 実行権限を付与
chmod +x /opt/snapshot-service/snapshot-service
# または
chmod +x /opt/snapshot-service/target/release/snapshot-service

# 権限を確認
ls -l /opt/snapshot-service/snapshot-service
# または
ls -l /opt/snapshot-service/target/release/snapshot-service
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

## 完全な修正手順（一括実行）

```bash
# 1. バイナリの場所を確認
BINARY_PATH=""
if [ -f "/opt/snapshot-service/snapshot-service" ]; then
    BINARY_PATH="/opt/snapshot-service/snapshot-service"
elif [ -f "/opt/snapshot-service/target/release/snapshot-service" ]; then
    BINARY_PATH="/opt/snapshot-service/target/release/snapshot-service"
else
    echo "Error: Binary not found!"
    exit 1
fi

echo "Binary found at: $BINARY_PATH"

# 2. 実行権限を付与
chmod +x "$BINARY_PATH"

# 3. systemdサービスファイルを修正
sudo sed -i "s|ExecStart=.*|ExecStart=$BINARY_PATH|" /etc/systemd/system/snapshot-service.service

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

### バイナリが見つからない場合

```bash
# バイナリを探す
find /opt/snapshot-service -name "snapshot-service" -type f

# 見つかったバイナリのパスを確認
find /opt/snapshot-service -name "snapshot-service" -type f -exec ls -lh {} \;
```

### サービスファイルが存在しない場合

```bash
# サービスファイルをコピー
sudo cp /opt/snapshot-service/snapshot-service.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/snapshot-service.service

# ExecStartパスを設定
sudo sed -i "s|ExecStart=.*|ExecStart=$BINARY_PATH|" /etc/systemd/system/snapshot-service.service

# systemdをリロード
sudo systemctl daemon-reload
```

### バイナリを直接実行してテスト

```bash
# バイナリを直接実行
/opt/snapshot-service/snapshot-service

# または
/opt/snapshot-service/target/release/snapshot-service

# 設定ファイルを指定して実行
CONFIG_PATH=/opt/snapshot-service/config.toml /opt/snapshot-service/snapshot-service
```

