# B_FX_BTCJPYで約定が発生しない問題のデバッグ

## ログ確認コマンド

### 1. 基本的な状況確認

```bash
# B_FX_BTCJPY関連のログ（直近100件）
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | tail -100

# エラーログ
sudo journalctl -u exch-sim-rust -p err | grep -i "B_FX_BTCJPY" | tail -50

# 警告ログ
sudo journalctl -u exch-sim-rust -p warning | grep -i "B_FX_BTCJPY" | tail -50
```

### 2. マーケットメーカー注文の処理状況

```bash
# マーケットメーカー注文の処理ログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "market maker" | tail -100

# スキップされているか確認（重要）
sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY" | tail -50

# 板情報の更新ログ
sudo journalctl -u exch-sim-rust | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -50
```

### 3. ユーザー注文の存在確認

```bash
# ユーザー注文に関するログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -E "new order|user order|process.*order" | tail -100

# 注文の追加ログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "add.*order\|Processing new order" | tail -50
```

### 4. マッチングの試行状況

```bash
# マッチングの試行ログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "matched\|matching\|can_match" | tail -100

# 約定ログ（発生しているか確認）
sudo journalctl -u exch-sim-rust | grep -i "Market maker execution" | grep -i "B_FX_BTCJPY" | tail -50

# ユーザー注文とのマッチングログ
sudo journalctl -u exch-sim-rust | grep -i "Market maker order matched with user orders" | grep -i "B_FX_BTCJPY" | tail -50
```

### 5. 板情報の状態確認

```bash
# 板情報の更新ログ（BID/ASK価格を確認）
sudo journalctl -u exch-sim-rust | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -20

# 板情報の更新タイミング
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -E "snapshot|delta|board" | tail -100
```

### 6. 時系列での分析

```bash
# 直近10分間のログを時系列で確認
sudo journalctl -u exch-sim-rust --since "10 minutes ago" | grep -i "B_FX_BTCJPY" | tail -200

# 特定の時刻のログ（例：現在時刻の前後）
sudo journalctl -u exch-sim-rust --since "$(date -d '5 minutes ago' '+%Y-%m-%d %H:%M:%S')" | grep -i "B_FX_BTCJPY"
```

### 7. 問題の特定に重要なログ

```bash
# 1. ユーザー注文がないためにスキップされているか
sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY" | tail -20

# 2. ユーザー注文が追加されているか
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "Processing new order" | tail -20

# 3. 板情報が正しく更新されているか
sudo journalctl -u exch-sim-rust | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -10

# 4. マッチングが試みられているか
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "market maker.*matched\|execution" | tail -20
```

## 問題の可能性

### パターン1: ユーザー注文がないためにスキップされている

**確認コマンド:**
```bash
sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY"
```

**対処法:**
- これは正常な動作です。ユーザー注文がない場合、マーケットメーカー注文同士がマッチングしないようにスキップしています
- ユーザー注文が追加されれば、次回の板更新でマッチングが試みられます

### パターン2: ユーザー注文があるのにスキップされている

**確認コマンド:**
```bash
# ユーザー注文の存在を確認
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "Processing new order" | tail -20

# スキップログを確認
sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY" | tail -20
```

**対処法:**
- `has_user_orders()`メソッドの実装を確認
- ユーザー注文が正しく認識されているか確認

### パターン3: 板情報が更新されていない

**確認コマンド:**
```bash
sudo journalctl -u exch-sim-rust | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -20
```

**対処法:**
- WebSocketからのデータ受信を確認
- `update_board_snapshot`が呼ばれているか確認

### パターン4: マッチング条件が満たされていない

**確認コマンド:**
```bash
# マッチングチェックのログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "matching check\|can_match\|matches=" | tail -50
```

**対処法:**
- ユーザー注文の価格と板情報のBID/ASKを比較
- マッチング条件（価格範囲）を確認

## データベースでの確認

```bash
# PostgreSQLに接続
PGPASSWORD="xxxxxxx" psql --host=localhost --port=15432 --username=exch_sim_user --dbname=exch_sim

# ユーザー注文の存在を確認
SELECT username, symbol, side, price, quantity, ord_status, created_at 
FROM orders 
WHERE symbol = 'B_FX_BTCJPY' 
  AND username != 'MARKET_MAKER'
  AND ord_status IN ('NEW', 'PARTIALLY_FILLED')
ORDER BY created_at DESC 
LIMIT 20;

# 最新の板情報を確認
SELECT s.symbol, s.timestamp, 
       MAX(CASE WHEN l.side = 'BID' AND l.level_index = 0 THEN l.price END) as bid_price,
       MAX(CASE WHEN l.side = 'ASK' AND l.level_index = 0 THEN l.price END) as ask_price
FROM market_board_snapshots s
JOIN market_board_price_levels l ON s.id = l.snapshot_id
WHERE s.symbol = 'B_FX_BTCJPY'
  AND l.level_index = 0
GROUP BY s.id, s.timestamp, s.symbol
ORDER BY s.timestamp DESC
LIMIT 10;

# 最新の約定を確認
SELECT exec_id, username, symbol, last_px, last_qty, side, created_at, counter_party_username
FROM executions
WHERE symbol = 'B_FX_BTCJPY'
ORDER BY created_at DESC
LIMIT 20;
```

## 推奨される分析手順

1. **まず、スキップログを確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY" | tail -20
   ```

2. **ユーザー注文の存在を確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "Processing new order" | tail -20
   ```

3. **板情報の更新を確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -10
   ```

4. **マッチングの試行を確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "matched\|matching" | tail -50
   ```

5. **データベースでユーザー注文と板情報を確認**
   - 上記のSQLクエリを実行

## 修正が必要な場合

もし`has_user_orders()`の実装に問題がある場合、以下のように修正できます：

```rust
// より確実な方法：bid_order_boardとask_order_boardを直接確認
pub fn has_user_orders(&self) -> bool {
    // Check bid_order_board
    for orders in self.bid_order_board.values() {
        for order in orders {
            if order.username != MARKET_MAKER_USERNAME {
                return true;
            }
        }
    }
    // Check ask_order_board
    for orders in self.ask_order_board.values() {
        for order in orders {
            if order.username != MARKET_MAKER_USERNAME {
                return true;
            }
        }
    }
    false
}
```

しかし、`bid_order_board`と`ask_order_board`がprivateの場合は、`order_map`を使う現在の実装で問題ないはずです。
