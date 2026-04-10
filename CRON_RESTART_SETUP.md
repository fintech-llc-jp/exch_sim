# Cronによる自動再起動設定

`snapshot-service`と`exch-sim-rust`を毎日午前4時（日本時間）に自動再起動するためのcron設定です。

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

以下の行を追加：

```cron
# 毎日午前4時にサービスを再起動
0 4 * * * /usr/local/bin/restart-services-cron.sh
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

VPSのタイムゾーンが日本時間（JST）に設定されていることを確認してください：

```bash
# 現在のタイムゾーンを確認
timedatectl

# タイムゾーンを日本時間に設定（必要に応じて）
sudo timedatectl set-timezone Asia/Tokyo

# cronのタイムゾーンを確認（/etc/timezoneまたは/etc/localtime）
cat /etc/timezone
ls -la /etc/localtime
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

## Cronジョブの削除（必要に応じて）

```bash
# rootのcrontabを編集
sudo crontab -e

# 該当行を削除
```

## サービス名の確認

もしサービス名が異なる場合は、`restart-services-cron.sh`を編集してください：

```bash
# 現在のサービス名を確認
sudo systemctl list-units --type=service | grep -E "(snapshot|exch)"

# スクリプトを編集
sudo nano /usr/local/bin/restart-services-cron.sh
```

## Cronの時間指定について

- `0 4 * * *` = 毎日午前4時0分
- `0 4 * * 1` = 毎週月曜日の午前4時
- `0 4 1 * *` = 毎月1日の午前4時

詳細は `man 5 crontab` を参照してください。

## systemdタイマーとの比較

### Cronの利点
- シンプルで理解しやすい
- 設定が簡単
- ログが分かりやすい

### systemdタイマーの利点
- systemdと統合されている
- より詳細な制御が可能
- 依存関係の管理ができる

この用途ではcronの方がシンプルで適しています。
