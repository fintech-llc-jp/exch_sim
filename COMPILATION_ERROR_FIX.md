# コンパイルエラーの修正内容

## 発生したエラー

```
error[E0505]: cannot move out of `matching_orders` because it is borrowed
   --> src/order/service.rs:563:43
    |
546 |             let matching_orders = board_lock.get_matching_orders(
    |                 --------------- binding `matching_orders` declared here
...
553 |             let market_maker_matches: Vec<_> = matching_orders
    |                                                --------------- borrow of `matching_orders` occurs here
...
563 |             let matching_orders: Vec<_> = matching_orders
    |                                           ^^^^^^^^^^^^^^^ move out of `matching_orders` occurs here
...
573 |             if !market_maker_matches.is_empty() {
    |                 -------------------- borrow later used here
```

## エラーの原因

Rustの所有権システムによるエラーです。以下の流れで問題が発生しました：

1. **546行目**: `matching_orders`を変数として宣言
2. **553行目**: `matching_orders.iter()`で`matching_orders`を**借用（borrow）**
   - `market_maker_matches`が`matching_orders`への参照を保持
3. **563行目**: `matching_orders.into_iter()`で`matching_orders`を**移動（move）**しようとする
   - しかし、`market_maker_matches`がまだ`matching_orders`を借用しているため、移動できない
4. **573行目**: `market_maker_matches`を使用（借用がまだ有効）

## 修正前のコード

```rust
let matching_orders = board_lock.get_matching_orders(...);

let total_matches_before_filter = matching_orders.len();
let market_maker_matches: Vec<_> = matching_orders  // ← ここで借用
    .iter()
    .filter(|(counter_order_entry, _)| {
        counter_order_entry.username == "MARKET_MAKER"
    })
    .collect();

// Filter out market maker orders
let matching_orders: Vec<_> = matching_orders  // ← ここで移動しようとする（エラー！）
    .into_iter()
    .filter(|(counter_order_entry, _)| {
        counter_order_entry.username != "MARKET_MAKER"
    })
    .collect();

// market_maker_matchesがまだ借用しているため、エラー
if !market_maker_matches.is_empty() {
    tracing::warn!(...);
}
```

## 修正後のコード

```rust
let matching_orders = board_lock.get_matching_orders(...);

let total_matches_before_filter = matching_orders.len();

// Extract market maker matches for logging (before filtering)
// 必要な情報（価格）だけを先に抽出して、matching_ordersへの参照を保持しない
let market_maker_prices: Vec<i64> = matching_orders
    .iter()
    .filter(|(counter_order_entry, _)| {
        counter_order_entry.username == "MARKET_MAKER"
    })
    .map(|(counter_order_entry, _)| counter_order_entry.price)  // ← 価格だけを抽出
    .collect();  // ← ここで所有権が解放される
let market_maker_match_count = market_maker_prices.len();

// Filter out market maker orders
// これで、matching_ordersを移動できる（借用が終了している）
let matching_orders: Vec<_> = matching_orders
    .into_iter()
    .filter(|(counter_order_entry, _)| {
        counter_order_entry.username != "MARKET_MAKER"
    })
    .collect();

let total_matches_after_filter = matching_orders.len();

// Log filtering information for debugging
if !market_maker_prices.is_empty() {
    tracing::warn!(
        "Market maker order filtering: symbol={}, side={:?}, price={}, total_matches={}, market_maker_matches={}, filtered_matches={}, filtered_prices={:?}",
        symbol,
        side,
        order.raw_price.unwrap_or(0),
        total_matches_before_filter,
        market_maker_match_count,
        total_matches_after_filter,
        market_maker_prices  // ← 既に所有権を持っているので問題なし
    );
}
```

## 修正のポイント

1. **借用を早期に終了させる**: `market_maker_matches`に`OrderEntry`全体を保持するのではなく、必要な情報（価格）だけを`Vec<i64>`として抽出
2. **所有権の移動**: `collect()`で所有権が`market_maker_prices`に移動し、`matching_orders`への借用が終了
3. **その後で移動可能**: `matching_orders`への借用が終了したので、`into_iter()`で移動できる

## Rustの所有権システムについて

- **借用（Borrow）**: `&`や`.iter()`で参照を取得。元の値は使用可能
- **移動（Move）**: `.into_iter()`や所有権を取る操作。元の値は使用不可
- **ルール**: 借用中は移動できない。借用が終了してから移動可能

この修正により、コンパイルエラーが解決され、ログ出力も正しく機能するようになりました。
