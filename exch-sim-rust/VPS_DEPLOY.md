# VPSデプロイガイド

このガイドでは、ExchSim RustをVPS上でビルド・実行する方法を説明します。

## 前提条件

- VPS上にRust環境がセットアップされていること
- PostgreSQLがインストール・起動していること
- Gitがインストールされていること

## デプロイ手順

### 1. リポジトリのクローン

```bash
cd /opt  # または任意のディレクトリ
git clone <your-repo-url> exch-sim-rust
cd exch-sim-rust/exch-sim-rust
```

### 2. 設定ファイルの準備

```bash
# 設定ファイルをコピー
cp config.toml.example config.toml

# 設定ファイルを編集（データベース接続情報など）
nano config.toml
```

`config.toml`で以下を確認・編集してください：
- `postgres.host`: PostgreSQLのホスト（通常は`localhost`）
- `postgres.port`: PostgreSQLのポート（通常は`5432`）
- `postgres.database`: データベース名（通常は`exch_sim`）
- `postgres.user`: PostgreSQLユーザー名
- `postgres.password`: PostgreSQLパスワード
- `server.port`: アプリケーションのポート（通常は`8080`）
- `jwt.secret`: JWT秘密鍵（本番環境では強力なランダム文字列を使用）

### 3. データベースの準備

```bash
# PostgreSQLに接続
psql -U postgres

# データベースとユーザーを作成（まだの場合）
CREATE DATABASE exch_sim;
CREATE USER exchsim WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE exch_sim TO exchsim;
\q
```

### 4. ビルドとデプロイ

```bash
# デプロイスクリプトに実行権限を付与
chmod +x deploy.sh

# デプロイスクリプトを実行
./deploy.sh
```

または、手動でビルドする場合：

```bash
# リリースビルド
cargo build --release

# バイナリの場所
# ./target/release/exch-sim-rust
```

### 5. 実行方法

#### 方法1: 直接実行

```bash
# フォアグラウンドで実行
./target/release/exch-sim-rust

# バックグラウンドで実行
nohup ./target/release/exch-sim-rust > /tmp/exch-sim-rust.log 2>&1 &
```

#### 方法2: systemdサービスとして設定（推奨）

```bash
# 専用ユーザーを作成（オプション、セキュリティのため推奨）
sudo useradd -r -s /bin/false exchsim
sudo chown -R exchsim:exchsim /opt/exch-sim-rust

# systemdサービスファイルをコピー
sudo cp exch-sim-rust.service /etc/systemd/system/

# サービスファイルを編集（必要に応じて）
sudo nano /etc/systemd/system/exch-sim-rust.service

# systemdをリロード
sudo systemctl daemon-reload

# サービスを有効化
sudo systemctl enable exch-sim-rust

# サービスを起動
sudo systemctl start exch-sim-rust

# ステータス確認
sudo systemctl status exch-sim-rust

# ログ確認
sudo journalctl -u exch-sim-rust -f
```

### 6. ファイアウォール設定

```bash
# UFWを使用している場合
sudo ufw allow 8080/tcp

# firewalldを使用している場合
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

### 7. リバースプロキシ設定（オプション）

Nginxを使用してリバースプロキシを設定する場合：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## トラブルシューティング

### ビルドエラー

```bash
# Rustのバージョンを確認
rustc --version
cargo --version

# Rustを更新
rustup update

# クリーンビルド
cargo clean
cargo build --release
```

### データベース接続エラー

```bash
# PostgreSQLが起動しているか確認
sudo systemctl status postgresql

# 接続テスト
psql -U postgres -d exch_sim -c "SELECT 1;"

# 設定ファイルの接続情報を確認
cat config.toml | grep -A 5 postgres
```

### ポートが既に使用されている

```bash
# ポート8080を使用しているプロセスを確認
sudo lsof -i :8080

# または
sudo netstat -tlnp | grep 8080

# プロセスを終了するか、config.tomlで別のポートを指定
```

### ログの確認

```bash
# systemdサービスを使用している場合
sudo journalctl -u exch-sim-rust -n 100

# 直接実行している場合
tail -f /tmp/exch-sim-rust.log
```

## 更新手順

```bash
# リポジトリを更新
cd /opt/exch-sim-rust/exch-sim-rust
git pull

# 再ビルド
cargo build --release

# systemdサービスを使用している場合、再起動
sudo systemctl restart exch-sim-rust
```

## セキュリティのベストプラクティス

1. **専用ユーザーで実行**: rootユーザーで実行しない
2. **強力なJWT秘密鍵**: `jwt.secret`にランダムで長い文字列を使用
3. **ファイアウォール設定**: 必要なポートのみ開放
4. **HTTPSの使用**: 本番環境ではリバースプロキシでHTTPSを有効化
5. **定期的な更新**: セキュリティパッチを適用

## パフォーマンス最適化

リリースビルドは最適化されていますが、さらに最適化する場合：

```bash
# プロファイル最適化ビルド
RUSTFLAGS="-C target-cpu=native" cargo build --release
```

## 参考

- [Rust公式ドキュメント](https://www.rust-lang.org/learn)
- [Axum公式ドキュメント](https://docs.rs/axum/)
- [systemdサービス管理](https://www.freedesktop.org/software/systemd/man/systemd.service.html)

