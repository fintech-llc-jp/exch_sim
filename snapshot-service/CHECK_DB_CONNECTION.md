# DB接続情報の確認方法

## 1. Rust Snapshot Serviceの設定確認

### 設定ファイルの場所

```bash
# VPS上で確認
cat /opt/snapshot-service/config.toml
```

### 確認すべき項目

```toml
[postgres]
host = "localhost"  # または "192.168.1.100"（別ホストの場合）
port = 5432
database = "exch_sim"
user = "postgres"  # または "exch_sim_user"
password = "your_password"
max_connections = 5
```

### 設定ファイルを編集

```bash
# VPS上で編集
nano /opt/snapshot-service/config.toml
```

## 2. Javaアプリケーションの設定確認

### 設定ファイルの場所

```bash
# VPS上で確認
cat /opt/exch_sim/application.properties
```

または、jarファイルと同じディレクトリにある場合：

```bash
cat /opt/exch_sim/application.properties
```

### 確認すべき項目

```properties
# データベース接続設定
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:exch_sim}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD:postgres123}
```

### 環境変数の確認

```bash
# 環境変数を確認
echo $DB_HOST
echo $DB_PORT
echo $DB_NAME
echo $DB_USER
echo $DB_PASSWORD

# または、systemdサービスファイルで確認
cat /etc/systemd/system/exch-sim.service | grep -i "DB_"
```

## 3. PostgreSQL接続テスト

### 方法1: psqlコマンドで接続テスト

```bash
# 設定ファイルから情報を取得して接続
psql -h localhost -p 5432 -U postgres -d exch_sim

# または、環境変数を使用
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME

# 接続成功後、簡単なクエリを実行
SELECT version();
SELECT current_database();
```

### 方法2: 接続情報を指定してテスト

```bash
# パスワードを対話的に入力
psql -h localhost -p 5432 -U postgres -d exch_sim

# パスワードを環境変数で指定（非推奨、セキュリティ上の理由）
PGPASSWORD=your_password psql -h localhost -p 5432 -U postgres -d exch_sim
```

### 方法3: 接続文字列でテスト

```bash
# 接続文字列を使用
psql "postgresql://postgres:your_password@localhost:5432/exch_sim"
```

## 4. データベースの存在確認

```bash
# PostgreSQLに接続
psql -h localhost -U postgres

# データベース一覧を確認
\l

# 特定のデータベースに接続
\c exch_sim

# テーブル一覧を確認
\dt

# スナップショットテーブルの確認
SELECT COUNT(*) FROM market_board_snapshots;
```

## 5. ネットワーク接続の確認

### 別ホストのPostgreSQLに接続する場合

```bash
# ホストへの接続確認
ping <postgres-host>

# ポートへの接続確認
telnet <postgres-host> 5432

# または、ncコマンド
nc -zv <postgres-host> 5432
```

## 6. 設定ファイルの比較

### Rust Snapshot ServiceとJavaアプリの設定を比較

```bash
# Rust設定のDB情報を抽出
grep -A 6 "\[postgres\]" /opt/snapshot-service/config.toml

# Java設定のDB情報を抽出
grep -E "spring.datasource|DB_" /opt/exch_sim/application.properties
```

## 7. 接続エラーのトラブルシューティング

### よくあるエラーと解決方法

#### エラー: "connection refused"

```bash
# PostgreSQLが起動しているか確認
sudo systemctl status postgresql

# 起動していない場合は起動
sudo systemctl start postgresql
```

#### エラー: "authentication failed"

```bash
# パスワードを確認
cat /opt/snapshot-service/config.toml | grep password
cat /opt/exch_sim/application.properties | grep password

# PostgreSQLのユーザーを確認
psql -h localhost -U postgres -c "\du"
```

#### エラー: "database does not exist"

```bash
# データベース一覧を確認
psql -h localhost -U postgres -c "\l"

# データベースが存在しない場合は作成
psql -h localhost -U postgres -c "CREATE DATABASE exch_sim;"
```

#### エラー: "connection timeout"（別ホストの場合）

```bash
# PostgreSQLの設定ファイルを確認（pg_hba.conf）
sudo cat /etc/postgresql/*/main/pg_hba.conf | grep -v "^#"

# PostgreSQLの設定ファイルを確認（postgresql.conf）
sudo cat /etc/postgresql/*/main/postgresql.conf | grep listen_addresses

# ファイアウォール設定を確認
sudo ufw status
```

## 8. 接続情報のセキュリティ確認

### 設定ファイルの権限確認

```bash
# Rust設定ファイルの権限
ls -l /opt/snapshot-service/config.toml

# Java設定ファイルの権限
ls -l /opt/exch_sim/application.properties

# 適切な権限に設定（所有者のみ読み取り可能）
sudo chmod 600 /opt/snapshot-service/config.toml
sudo chmod 600 /opt/exch_sim/application.properties
```

## 9. 接続情報の一括確認スクリプト

```bash
#!/bin/bash

echo "=== DB Connection Information ==="
echo ""

echo "--- Rust Snapshot Service ---"
if [ -f /opt/snapshot-service/config.toml ]; then
    echo "Config file: /opt/snapshot-service/config.toml"
    grep -A 6 "\[postgres\]" /opt/snapshot-service/config.toml | grep -E "host|port|database|user" | sed 's/password.*/password=***/'
else
    echo "Config file not found"
fi

echo ""
echo "--- Java Application ---"
if [ -f /opt/exch_sim/application.properties ]; then
    echo "Config file: /opt/exch_sim/application.properties"
    grep -E "spring.datasource|DB_" /opt/exch_sim/application.properties | sed 's/password.*/password=***/'
else
    echo "Config file not found"
fi

echo ""
echo "--- Environment Variables ---"
env | grep -E "DB_|POSTGRES" || echo "No DB environment variables set"

echo ""
echo "--- Connection Test ---"
if command -v psql &> /dev/null; then
    echo "Testing connection..."
    psql -h localhost -U postgres -d exch_sim -c "SELECT version();" 2>&1 | head -3
else
    echo "psql command not found"
fi
```

このスクリプトを`check_db.sh`として保存して実行：

```bash
chmod +x check_db.sh
./check_db.sh
```

