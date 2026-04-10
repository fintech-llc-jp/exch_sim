# 自動再起動タイマーの設定

`snapshot-service`と`exch-sim-rust`を毎日午前4時（日本時間）に自動再起動するためのsystemdタイマー設定です。

## ファイル構成

- `snapshot-service/snapshot-service-restart.timer` - タイマー定義
- `snapshot-service/snapshot-service-restart.service` - 再起動実行サービス
- `exch-sim-rust/exch-sim-rust-restart.timer` - タイマー定義
- `exch-sim-rust/exch-sim-rust-restart.service` - 再起動実行サービス

## VPS上での設定手順

### 1. ファイルをコピー

```bash
cd /opt/exch_sim

# snapshot-serviceのタイマー設定をコピー
sudo cp snapshot-service/snapshot-service-restart.timer /etc/systemd/system/
sudo cp snapshot-service/snapshot-service-restart.service /etc/systemd/system/

# exch-sim-rustのタイマー設定をコピー
sudo cp exch-sim-rust/exch-sim-rust-restart.timer /etc/systemd/system/
sudo cp exch-sim-rust/exch-sim-rust-restart.service /etc/systemd/system/
```

### 2. systemdの設定をリロード

```bash
sudo systemctl daemon-reload
```

### 3. タイマーを有効化

```bash
# snapshot-serviceのタイマーを有効化
sudo systemctl enable snapshot-service-restart.timer

# exch-sim-rustのタイマーを有効化
sudo systemctl enable exch-sim-rust-restart.timer
```

### 4. タイマーを起動

```bash
# snapshot-serviceのタイマーを起動
sudo systemctl start snapshot-service-restart.timer

# exch-sim-rustのタイマーを起動
sudo systemctl start exch-sim-rust-restart.timer
```

### 5. タイマーの状態を確認

```bash
# すべてのタイマーの状態を確認
sudo systemctl list-timers --all

# 特定のタイマーの状態を確認
sudo systemctl status snapshot-service-restart.timer
sudo systemctl status exch-sim-rust-restart.timer
```

### 6. タイマーの実行時刻を確認

```bash
# 次回の実行時刻を確認
sudo systemctl list-timers snapshot-service-restart.timer exch-sim-rust-restart.timer
```

## タイマーのテスト

### 手動でタイマーを実行（テスト用）

```bash
# サービスを再起動する（テスト）
sudo systemctl start snapshot-service-restart.service
sudo systemctl start exch-sim-rust-restart.service

# ログを確認
sudo journalctl -u snapshot-service-restart.service -n 20
sudo journalctl -u exch-sim-rust-restart.service -n 20
```

### タイマーの動作確認

```bash
# タイマーが有効になっているか確認
sudo systemctl is-enabled snapshot-service-restart.timer
sudo systemctl is-enabled exch-sim-rust-restart.timer

# タイマーがアクティブか確認
sudo systemctl is-active snapshot-service-restart.timer
sudo systemctl is-active exch-sim-rust-restart.timer
```

## タイマーの無効化（必要に応じて）

```bash
# タイマーを停止
sudo systemctl stop snapshot-service-restart.timer
sudo systemctl stop exch-sim-rust-restart.timer

# タイマーを無効化
sudo systemctl disable snapshot-service-restart.timer
sudo systemctl disable exch-sim-rust-restart.timer
```

## ログの確認

```bash
# タイマーの実行ログを確認
sudo journalctl -u snapshot-service-restart.service -f
sudo journalctl -u exch-sim-rust-restart.service -f

# サービスの再起動ログを確認
sudo journalctl -u snapshot-service -f
sudo journalctl -u exch-sim-rust -f
```

## タイムゾーンの確認

VPSのタイムゾーンが日本時間（JST）に設定されていることを確認してください：

```bash
# 現在のタイムゾーンを確認
timedatectl

# タイムゾーンを日本時間に設定（必要に応じて）
sudo timedatectl set-timezone Asia/Tokyo
```

## 注意事項

- タイマーは毎日午前4時（JST）に実行されます
- `Persistent=true`により、システムがダウンしていた場合でも次回起動時に実行されます
- 再起動は`systemctl restart`コマンドで実行されます
- ログは`journalctl`で確認できます
