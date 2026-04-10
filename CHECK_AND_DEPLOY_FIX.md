# B_FX_BTCJPYの約定価格乖離問題の確認とデプロイ

## 問題の状況

約定価格（13958524〜13961708）が板情報（BID: 14067701, ASK: 14070749）の範囲外になっています。
これは、古いマーケットメーカー注文とマッチングしている可能性があります。

## 修正内容

1. `update_board_snapshot`で`update_external_market_data`を呼び出すように修正
2. `process_market_maker_order`でマーケットメーカー注文同士のマッチングをフィルタリング

## VPS上での確認とデプロイ手順

### 1. 現在のコードを確認

```bash
cd /opt/exch_sim

# 最新のコードをプル
git pull origin develop

# 修正が含まれているか確認
grep -n "Filter out market maker" exch-sim-rust/src/order/service.rs
grep -n "update_external_market_data" exch-sim-rust/src/market_board/manager.rs | head -5
```

### 2. ビルド

```bash
cd /opt/exch_sim/exch-sim-rust

# ビルド（ロックが残っている場合は先に解除）
pkill -9 cargo rustc 2>/dev/null
rm -f target/.cargo-lock 2>/dev/null

# リリースビルド
cargo build --release
```

### 3. サービスを再起動

```bash
# サービスを停止
sudo systemctl stop exch-sim-rust

# バイナリをコピー（ビルドが成功した場合）
sudo cp target/release/exch-sim-rust /opt/exch-sim-rust/target/release/

# サービスを起動
sudo systemctl start exch-sim-rust

# 状態を確認
sudo systemctl status exch-sim-rust
```

### 4. ログを確認

```bash
# サービスのログを確認
sudo journalctl -u exch-sim-rust -f

# 約定価格のログを確認
sudo journalctl -u exch-sim-rust | grep -i "execution\|match" | tail -50
```

### 5. データベースで確認

```bash
# PostgreSQLに接続
PGPASSWORD="xxxxxxxx" psql --host=localhost --port=15432 --username=exch_sim_user --dbname=exch_sim

# 約定価格と板情報を比較
WITH latest_executions AS (
    SELECT
        'EXECUTION' as data_type,
        created_at as update_time,
        last_px as price,
        NULL::numeric as bid_price,
        NULL::numeric as ask_price,
        side,
        last_qty as quantity
    FROM executions
    WHERE symbol = 'B_FX_BTCJPY'
      AND exec_status = 'FILLED'
      AND last_px > 0
      AND created_at > NOW() - INTERVAL '5 minutes'
    ORDER BY created_at DESC
    LIMIT 20
),
latest_board_data AS (
    SELECT
        'MARKET_BOARD' as data_type,
        s.timestamp as update_time,
        NULL::numeric as price,
        MAX(CASE WHEN l.side = 'BID' AND l.level_index = 0 THEN l.price END) as bid_price,
        MAX(CASE WHEN l.side = 'ASK' AND l.level_index = 0 THEN l.price END) as ask_price,
        NULL::text as side,
        NULL::numeric as quantity
    FROM market_board_snapshots s
    JOIN market_board_price_levels l ON s.id = l.snapshot_id
    WHERE s.symbol = 'B_FX_BTCJPY'
      AND l.level_index = 0
      AND s.timestamp > NOW() - INTERVAL '5 minutes'
    GROUP BY s.id, s.timestamp
    ORDER BY s.timestamp DESC
    LIMIT 20
)
SELECT
    data_type,
    update_time,
    price,
    bid_price,
    ask_price,
    CASE 
        WHEN price IS NOT NULL AND bid_price IS NOT NULL AND ask_price IS NOT NULL THEN
            CASE 
                WHEN price < bid_price OR price > ask_price THEN 'OUT_OF_RANGE'
                ELSE 'OK'
            END
        ELSE NULL
    END as validation,
    side,
    quantity
FROM latest_executions
UNION ALL
SELECT
    data_type,
    update_time,
    price,
    bid_price,
    ask_price,
    NULL as validation,
    side,
    quantity
FROM latest_board_data
ORDER BY update_time DESC;
```

## 修正が反映されていない場合の確認

### 1. コードの確認

```bash
cd /opt/exch_sim/exch-sim-rust

# process_market_maker_orderにフィルタリングがあるか確認
grep -A 5 "Filter out market maker" src/order/service.rs

# update_board_snapshotでupdate_external_market_dataを呼び出しているか確認
grep -A 3 "update_external_market_data" src/market_board/manager.rs | head -10
```

### 2. ビルドの確認

```bash
# ビルドが成功しているか確認
ls -lh target/release/exch-sim-rust

# バイナリの更新日時を確認
stat target/release/exch-sim-rust
```

### 3. サービスの確認

```bash
# サービスが最新のバイナリを使用しているか確認
sudo systemctl status exch-sim-rust
ps aux | grep exch-sim-rust
```

## 緊急時の対処

もし修正が反映されない場合、一時的に`process_market_maker_order`を無効化することもできますが、これは推奨されません。

代わりに、VPS上で最新のコードを確実にデプロイしてください。
