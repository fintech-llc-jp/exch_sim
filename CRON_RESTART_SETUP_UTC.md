# Cronによる自動再起動設定（UTCタイムゾーン）

システムのタイムゾーンがUTCの場合の設定方法です。

## タイムゾーンの計算

- 日本時間（JST）午前4時 = UTC 19:00（前日）
- JST = UTC + 9時間

## VPS上での設定手順

### 1. スクリプトをコピー

```bash
cd /opt/exch_sim

# スクリプトをコピー
sudo cp restart-services-cron.sh /usr/local/bin/
sudo chmod +x /usr/local/bin/restart-services-cron.sh
```

### 2. ログディレクトリを作成（必要に応じて）

```bash
sudo mkdir -p /var/log
sudo touch /var/log/exch-sim-restart.log
sudo chmod 644 /var/log/exch-sim-restart.log
```

### 3. Cronジョブを設定

```bash
# rootユーザーでcrontabを編集
sudo crontab -e
```

以下の行を追加（UTC 19:00 = JST 04:00）：

```cron
# 毎日UTC 19:00（日本時間午前4時）にサービスを再起動
0 19 * * * /usr/local/bin/restart-services-cron.sh
```

### 4. Cronジョブの確認

```bash
# rootのcrontabを確認
sudo crontab -l

# cronサービスの状態を確認
sudo systemctl status cron
# または
sudo systemctl status crond  # CentOS/RHELの場合
```

## タイムゾーンの確認

```bash
# 現在のタイムゾーンを確認
timedatectl

# 現在時刻を確認（UTCとJSTの両方）
date
date -u
TZ=Asia/Tokyo date
```

## オプション1: スクリプト内でタイムゾーンを指定（推奨）

スクリプト内で明示的にJSTを使用する場合：

```bash
sudo nano /usr/local/bin/restart-services-cron.sh
```

スクリプトの先頭に以下を追加：

```bash
#!/bin/bash
# タイムゾーンをJSTに設定
export TZ=Asia/Tokyo

# 毎日午前4時（JST）にsnapshot-serviceとexch-sim-rustを再起動するスクリプト
...
```

この場合、cronはUTCで動作しますが、スクリプト内の`date`コマンドなどはJSTで動作します。
ただし、cronの実行時刻自体はシステムタイムゾーン（UTC）で指定する必要があるため、`0 19 * * *`のままです。

## オプション2: システムタイムゾーンをJSTに変更

システム全体のタイムゾーンをJSTに変更する場合：

```bash
# タイムゾーンをJSTに変更
sudo timedatectl set-timezone Asia/Tokyo

# 確認
timedatectl
```

この場合、cronファイルは以下のようになります：

```cron
# 毎日午前4時（JST）にサービスを再起動
0 4 * * * /usr/local/bin/restart-services-cron.sh
```

## ログの確認

```bash
# 再起動スクリプトのログを確認
sudo tail -f /var/log/exch-sim-restart.log

# 最新50行を表示
sudo tail -n 50 /var/log/exch-sim-restart.log

# サービスのログも確認
sudo journalctl -u snapshot-service -n 50
sudo journalctl -u exch-sim-rust -n 50
```

## テスト実行

```bash
# 手動でスクリプトを実行（テスト）
sudo /usr/local/bin/restart-services-cron.sh

# ログを確認
sudo tail -f /var/log/exch-sim-restart.log
```

## 時刻の確認例

```bash
# UTC時刻を確認
date -u
# 例: Mon Jan 20 19:00:00 UTC 2025

# JST時刻を確認
TZ=Asia/Tokyo date
# 例: Tue Jan 21 04:00:00 JST 2025
```

## まとめ

**UTCタイムゾーンの場合：**
- Cron設定: `0 19 * * *` （UTC 19:00 = JST 04:00）
- システムタイムゾーンをJSTに変更する場合は: `0 4 * * *`
