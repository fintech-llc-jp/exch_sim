# 約定価格乖離問題の根本原因分析

## ログから判明した問題

ログを見ると、以下の問題が確認されました：

```
Market maker order filtering: symbol=B_FX_BTCJPY, side=Sell, price=13988157, 
total_matches=2, market_maker_matches=2, filtered_matches=0, filtered_prices=[13988266, 13988266]
```

### 問題の詳細

1. **`total_matches=2`**: マッチング候補が2件見つかった
2. **`market_maker_matches=2`**: その2件が**すべてマーケットメーカー注文**
3. **`filtered_matches=0`**: フィルタリング後は0件（ユーザー注文とのマッチングなし）

### 根本原因

`update_board_snapshot`の処理フローに問題があります：

1. **`update_external_market_data`を呼び出し**: 板情報を更新し、マーケットメーカー注文を追加
2. **`process_market_maker_order`を呼び出し**: 新しいマーケットメーカー注文を作成してマッチングを試みる
3. **問題**: 新しいマーケットメーカー注文が、`update_external_market_data`で追加された既存のマーケットメーカー注文とマッチングしようとする

### なぜこれが問題なのか

- `update_external_market_data`で既に板情報が更新されている
- `process_market_maker_order`は新しい注文を作成してマッチングを試みるが、ユーザー注文がない場合は無駄な処理になる
- フィルタリングは機能しているが、毎回警告ログが出力され、パフォーマンスにも影響する

## 修正内容

### 1. ユーザー注文の存在確認メソッドを追加

```rust
// market_board/board.rs
pub fn has_user_orders(&self) -> bool {
    self.order_map
        .values()
        .any(|entry| entry.username != MARKET_MAKER_USERNAME)
}
```

### 2. `update_board_snapshot`でユーザー注文の存在を確認

```rust
// market_board/manager.rs
// Check if there are user orders before processing market maker orders
let has_user_orders = {
    let board_guard = board.read().await;
    board_guard.has_user_orders()
};

if !has_user_orders {
    tracing::debug!(
        "Skipping market maker order processing: symbol={}, no user orders to match with",
        symbol
    );
    return;
}
```

### 3. `update_board_delta`でも同様の確認を追加

同じロジックを`update_board_delta`にも適用しました。

## 効果

1. **無駄な処理を削減**: ユーザー注文がない場合は`process_market_maker_order`を呼び出さない
2. **警告ログの削減**: マーケットメーカー注文同士のマッチング試行が発生しない
3. **パフォーマンス向上**: 不要なマッチング処理をスキップ

## 注意事項

- ユーザー注文が追加された後、次の`update_board_snapshot`でマッチングが試みられる
- リアルタイム性は維持される（ユーザー注文が追加されれば、次回の板更新でマッチングされる）

## デプロイ後の確認

デプロイ後、以下のログが表示されなくなることを確認してください：

```bash
# この警告が表示されなくなる
sudo journalctl -u exch-sim-rust | grep -i "Market maker order filtering" | grep -i "B_FX_BTCJPY"
```

代わりに、以下のログが表示される場合があります：

```bash
# ユーザー注文がない場合
sudo journalctl -u exch-sim-rust | grep -i "Skipping market maker order processing"
```
