# Exec format error / status=203/EXEC トラブルシューティング

## 問題

systemdサービスが`status=203/EXEC`エラーで起動しない。

## 原因

1. バイナリのパスが間違っている
2. バイナリが存在しない
3. 実行権限がない
4. アーキテクチャの不一致（macOSでビルドしたバイナリをLinuxで実行）

## 解決手順

### 1. バイナリの存在とパスを確認

```bash
# VPSにSSH接続
ssh root@77.42.74.155

# バイナリの存在確認
ls -lh /opt/snapshot-service/target/release/snapshot-service

# バイナリの情報確認（アーキテクチャ）
file /opt/snapshot-service/target/release/snapshot-service

# 実行権限確認
ls -l /opt/snapshot-service/target/release/snapshot-service
```

### 2. systemdサービスファイルの確認

```bash
# サービスファイルの内容を確認
cat /etc/systemd/system/snapshot-service.service
```

**VPS上でビルドした場合、ExecStartは以下である必要があります：**

```ini
ExecStart=/opt/snapshot-service/target/release/snapshot-service
```

**ローカルでビルドしてコピーした場合（非推奨、アーキテクチャ不一致の可能性あり）：**

```ini
ExecStart=/opt/snapshot-service/snapshot-service
```

### 3. systemdサービスファイルを修正

```bash
# サービスファイルを編集
sudo nano /etc/systemd/system/snapshot-service.service

# または、sedで一括置換
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# systemdをリロード
sudo systemctl daemon-reload
```

### 4. バイナリを直接実行してテスト

```bash
# バイナリを直接実行してエラーを確認
/opt/snapshot-service/target/release/snapshot-service

# または、設定ファイルを指定
CONFIG_PATH=/opt/snapshot-service/config.toml /opt/snapshot-service/target/release/snapshot-service
```

### 5. サービスを再起動

```bash
# サービスを再起動
sudo systemctl restart snapshot-service

# ステータス確認
sudo systemctl status snapshot-service

# 詳細ログ確認
sudo journalctl -u snapshot-service -n 50 --no-pager
```

## 完全な修正手順（一括実行）

```bash
ssh root@77.42.74.155 << 'EOF'
    # 1. バイナリの存在確認
    echo "=== Checking binary ==="
    ls -lh /opt/snapshot-service/target/release/snapshot-service
    
    # 2. 実行権限を付与
    chmod +x /opt/snapshot-service/target/release/snapshot-service
    
    # 3. systemdサービスファイルを修正
    echo "=== Fixing systemd service file ==="
    sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service
    
    # 4. 修正内容を確認
    echo "=== Updated service file ==="
    grep ExecStart /etc/systemd/system/snapshot-service.service
    
    # 5. systemdをリロード
    sudo systemctl daemon-reload
    
    # 6. サービスを再起動
    sudo systemctl restart snapshot-service
    
    # 7. ステータス確認
    sleep 2
    sudo systemctl status snapshot-service --no-pager -l
EOF
```

## バイナリが存在しない場合

VPS上でビルドする必要があります：

```bash
ssh root@77.42.74.155 << 'EOF'
    cd /opt/snapshot-service
    
    # Rustがインストールされているか確認
    if ! command -v cargo &> /dev/null; then
        echo "Installing Rust..."
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        source $HOME/.cargo/env
    else
        source $HOME/.cargo/env
    fi
    
    # ビルド
    echo "Building release binary..."
    cargo build --release
    
    # 実行権限を付与
    chmod +x target/release/snapshot-service
    
    # バイナリの確認
    ls -lh target/release/snapshot-service
    file target/release/snapshot-service
EOF
```

