# クイックデプロイ手順

## 現在のディレクトリから実行する場合

`snapshot-service`ディレクトリ内にいる場合：

```bash
# 現在のディレクトリ（.）をコピー
rsync -avz --exclude 'target' --exclude '*.log' --exclude '.git' \
    ./ root@77.42.74.155:/opt/snapshot-service/
```

## 親ディレクトリから実行する場合

`exch_sim`ディレクトリ内にいる場合：

```bash
rsync -avz --exclude 'target' --exclude '*.log' --exclude '.git' \
    snapshot-service/ root@77.42.74.155:/opt/snapshot-service/
```

## 完全な手動デプロイ手順

### 1. ソースコードをコピー

```bash
# snapshot-serviceディレクトリ内にいる場合
cd snapshot-service
rsync -avz --exclude 'target' --exclude '*.log' --exclude '.git' \
    ./ root@77.42.74.155:/opt/snapshot-service/
```

### 2. VPS上でビルド

```bash
ssh root@77.42.74.155

# Rustがインストールされていない場合はインストール
if ! command -v cargo &> /dev/null; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source $HOME/.cargo/env
fi

# ビルド
cd /opt/snapshot-service
cargo build --release
```

### 3. 設定ファイルの準備

```bash
# 設定ファイルが存在しない場合は作成
if [ ! -f config.toml ]; then
    cp config.toml.example config.toml
    nano config.toml  # PostgreSQL接続情報を編集
fi
```

### 4. systemdサービスの設定

```bash
# サービスファイルを配置
sudo cp snapshot-service.service /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/snapshot-service.service

# ExecStartパスを修正（VPS上でビルドした場合）
sudo sed -i 's|ExecStart=/opt/snapshot-service/snapshot-service|ExecStart=/opt/snapshot-service/target/release/snapshot-service|' /etc/systemd/system/snapshot-service.service

# systemdをリロード
sudo systemctl daemon-reload

# サービスを有効化・起動
sudo systemctl enable snapshot-service
sudo systemctl start snapshot-service

# ステータス確認
sudo systemctl status snapshot-service
```

### 5. ログ確認

```bash
sudo journalctl -u snapshot-service -f
```

