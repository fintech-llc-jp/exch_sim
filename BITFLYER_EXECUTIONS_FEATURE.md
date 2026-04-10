# Bitflyer約定情報のDB保存機能

## 追加した機能

`snapshot-service`にBitflyerの約定情報（executions）をDBに保存する機能を追加しました。

## 変更内容

### 1. チャンネル購読の追加

`lightning_executions_BTC_JPY`と`lightning_executions_FX_BTC_JPY`チャンネルを購読するように追加しました。

### 2. 約定メッセージのパース

Bitflyerの約定メッセージをパースする構造体を追加：
- `ChannelMessage`: `channelMessage`メソッド形式
- `ExecutionsMessage`: 直接メソッド形式
- `ExecutionData`: 約定データ

### 3. DB保存機能

GMO版と同様に`save_trade_to_db`関数を追加し、約定情報を`executions`テーブルに保存します。

## 設定

`snapshot-service`の`main.rs`で、`BitflyerWebSocketClient`にPostgreSQLプールを渡すように修正しました。

## デプロイ後の確認

### ログ確認

```bash
# Bitflyerの約定情報が保存されているか確認
sudo journalctl -u snapshot-service | grep -i "Bitflyer Trade" | tail -50

# DB保存の成功ログ
sudo journalctl -u snapshot-service | grep -i "Saved Bitflyer trade" | tail -50

# エラーログ
sudo journalctl -u snapshot-service -p err | grep -i "Bitflyer\|execution" | tail -20
```

### データベースでの確認

```bash
# PostgreSQLに接続
PGPASSWORD="xxxxxx" psql --host=localhost --port=15432 --username=exch_sim_user --dbname=exch_sim

# Bitflyerの約定情報を確認（EXTERNAL_FEED）
SELECT exec_id, username, symbol, last_px, last_qty, side, created_at, counter_party_username
FROM executions
WHERE symbol = 'B_FX_BTCJPY'
  AND username = 'EXTERNAL_FEED'
ORDER BY created_at DESC
LIMIT 20;

# 約定数の確認
SELECT username, symbol, COUNT(*) as count, MIN(created_at) as first_exec, MAX(created_at) as last_exec
FROM executions
WHERE symbol = 'B_FX_BTCJPY'
  AND username = 'EXTERNAL_FEED'
GROUP BY username, symbol;
```

## 注意事項

- 約定情報は`username = 'EXTERNAL_FEED'`として保存されます
- `price_multiplier = 1`, `qty_multiplier = 1000`を使用して変換されます
- 重複挿入エラー（unique_violation）は無視されます
