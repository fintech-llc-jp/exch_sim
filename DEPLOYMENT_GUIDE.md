# jarファイルのデプロイ方法

## jarファイルの場所

`./gradlew bootJar`を実行すると、以下の場所にjarファイルが生成されます：

```
build/libs/exch_sim-0.0.1-SNAPSHOT.jar
```

## VPSへのコピー方法

### 方法1: scpコマンドを使用（推奨）

```bash
# VPSにjarファイルをコピー
scp build/libs/exch_sim-0.0.1-SNAPSHOT.jar user@your-vps-ip:/path/to/destination/

# 例
scp build/libs/exch_sim-0.0.1-SNAPSHOT.jar root@192.168.1.100:/opt/exch_sim/
```

### 方法2: rsyncコマンドを使用（進捗表示あり）

```bash
# rsyncでコピー（進捗表示あり）
rsync -avz --progress build/libs/exch_sim-0.0.1-SNAPSHOT.jar user@your-vps-ip:/path/to/destination/

# 例
rsync -avz --progress build/libs/exch_sim-0.0.1-SNAPSHOT.jar root@192.168.1.100:/opt/exch_sim/
```

### 方法3: SFTPクライアントを使用

1. FileZilla、WinSCP、CyberduckなどのSFTPクライアントを起動
2. VPSに接続
3. `build/libs/exch_sim-0.0.1-SNAPSHOT.jar`をドラッグ&ドロップ

## VPSでの実行方法

### 1. jarファイルを実行

```bash
# VPSにSSH接続
ssh user@your-vps-ip

# jarファイルがあるディレクトリに移動
cd /opt/exch_sim

# 実行（メモリ設定を含む）
java -Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar exch_sim-0.0.1-SNAPSHOT.jar \
  --spring.config.location=file:/opt/exch_sim/application.properties
```

### 2. systemdサービスとして実行（推奨）

`/etc/systemd/system/exch-sim.service`を作成：

```ini
[Unit]
Description=Exchange Simulator Application
After=network.target postgresql.service

[Service]
Type=simple
User=exch_sim
WorkingDirectory=/opt/exch_sim
Environment="JAVA_OPTS=-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/exch_sim/exch_sim-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

サービスを有効化して起動：

```bash
# サービスを有効化
sudo systemctl enable exch-sim

# サービスを起動
sudo systemctl start exch-sim

# ステータス確認
sudo systemctl status exch-sim

# ログ確認
sudo journalctl -u exch-sim -f
```

## 設定ファイルの配置

VPSに`application.properties`もコピーする必要があります：

```bash
# 設定ファイルをコピー
scp src/main/resources/application.properties user@your-vps-ip:/opt/exch_sim/

# または、jarファイルと同じディレクトリに配置
scp src/main/resources/application.properties user@your-vps-ip:/opt/exch_sim/application.properties
```

## 環境変数の設定

VPSで環境変数を設定する場合：

```bash
# ~/.bashrc または /etc/environment に追加
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=exch_sim
export DB_USER=postgres
export DB_PASSWORD=your_password
```

## デプロイスクリプト例

`deploy.sh`を作成：

```bash
#!/bin/bash

# ビルド
./gradlew bootJar

# VPS情報
VPS_USER="root"
VPS_HOST="your-vps-ip"
VPS_PATH="/opt/exch_sim"

# jarファイルをコピー
echo "Copying jar file to VPS..."
scp build/libs/exch_sim-0.0.1-SNAPSHOT.jar ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/

# 設定ファイルをコピー（必要に応じて）
# scp src/main/resources/application.properties ${VPS_USER}@${VPS_HOST}:${VPS_PATH}/

# サービスを再起動（systemdを使用している場合）
echo "Restarting service on VPS..."
ssh ${VPS_USER}@${VPS_HOST} "sudo systemctl restart exch-sim"

echo "Deployment completed!"
```

実行権限を付与：

```bash
chmod +x deploy.sh
```

## トラブルシューティング

### jarファイルが見つからない場合

```bash
# ビルドをクリーンして再ビルド
./gradlew clean bootJar

# jarファイルの場所を確認
ls -lh build/libs/*.jar
```

### 実行時にエラーが発生する場合

```bash
# 詳細なエラーログを確認
java -jar exch_sim-0.0.1-SNAPSHOT.jar --debug

# または、ログファイルを確認
tail -f /var/log/exch-sim/application.log
```

### メモリ不足の場合

```bash
# ヒープサイズを調整
java -Xms128m -Xmx512m -jar exch_sim-0.0.1-SNAPSHOT.jar
```

## 関連ファイル

- `build/libs/exch_sim-0.0.1-SNAPSHOT.jar` - 生成されたjarファイル
- `src/main/resources/application.properties` - 設定ファイル
- `Dockerfile` - Docker環境用の設定

