# Snapshot Service

Exchange Simulator用のスナップショット収集サービス（Rust実装）

## 概要

このサービスは、Javaプロセスから独立して動作し、WebSocketからマーケットデータを取得してスナップショットをPostgreSQLに保存します。

## 機能

- Bitflyer/GMO WebSocketへの直接接続
- 独自のMarketBoard管理
- 30秒間隔でのスナップショット収集
- 10秒間隔での時間ベースバッチ書き込み（IO使用量削減）
- 別ホストでの動作対応

## ビルド

```bash
cd snapshot-service
./build.sh
```

または

```bash
cargo build --release
```

## 設定

`config.toml.example`をコピーして`config.toml`を作成し、設定を編集してください。

```bash
cp config.toml.example config.toml
# config.tomlを編集
```

## 実行

```bash
# 設定ファイルのパスを指定（デフォルト: config.toml）
CONFIG_PATH=config.toml ./target/release/snapshot-service
```

## デプロイ

### 1. バイナリをVPSにコピー

```bash
scp target/release/snapshot-service root@your-vps:/opt/snapshot-service/
scp config.toml root@your-vps:/opt/snapshot-service/
scp snapshot-service.service root@your-vps:/etc/systemd/system/
```

### 2. systemdサービスを有効化

```bash
sudo systemctl daemon-reload
sudo systemctl enable snapshot-service
sudo systemctl start snapshot-service
sudo systemctl status snapshot-service
```

## ログ確認

```bash
sudo journalctl -u snapshot-service -f
```

## トラブルシューティング

### PostgreSQL接続エラー

- `config.toml`の`[postgres]`セクションを確認
- ネットワーク接続を確認（別ホストの場合はファイアウォール設定）

### WebSocket接続エラー

- Bitflyer/GMOのWebSocket URLを確認
- ネットワーク接続を確認

### データが保存されない

- ログを確認: `sudo journalctl -u snapshot-service -n 100`
- PostgreSQL接続を確認
- WebSocketメッセージ受信を確認

