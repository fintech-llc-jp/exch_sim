# PNL計算の修正

## 問題点

トレードヒストリーでPNL計算が正しくありませんでした：

1. **SHORT/COVER_SHORT**: 高く売って低く買い戻しているのにマイナスPNL
2. **BUY/SELL**: 低く買って高く売っているのにマイナスPNL

## 原因

PNL計算で、エントリー時（SHORT/BUY）の手数料が考慮されていませんでした。

### 修正前

- **COVER_SHORT**: `pnl = (entry_price - execution_price) * abs(position) - commission`
  - COVER_SHORTの時の手数料のみを引いていた
  - SHORTの時の手数料が考慮されていなかった

- **SELL**: `pnl = (execution_price - entry_price) * position - commission`
  - SELLの時の手数料のみを引いていた
  - BUYの時の手数料が考慮されていなかった

### 修正後

- **COVER_SHORT**: `pnl = (entry_price - execution_price) * close_quantity - entry_commission - exit_commission`
  - SHORTの時の手数料（entry_commission）とCOVER_SHORTの時の手数料（exit_commission）の両方を考慮

- **SELL**: `pnl = (execution_price - entry_price) * position - entry_commission - commission`
  - BUYの時の手数料（entry_commission）とSELLの時の手数料（commission）の両方を考慮

## 修正内容

1. `entry_commission`変数を追加して、エントリー時の手数料を記録
2. SHORT/BUYの時に`entry_commission = commission`を設定
3. COVER_SHORT/SELLの時に、`entry_commission`と`exit_commission`の両方をPNL計算に含める
4. SELLの時のcapital計算を`capital += pnl`に変更（PNLに基づいてcapitalを更新）

## 計算例

### SHORT/COVER_SHORTの例

- SHORT: 14,300,308で0.01売り
  - entry_price = 14,300,308
  - entry_commission = 14,300,308 * 0.01 * 0.001 = 143.00 JPY
  - capital -= 143.00

- COVER_SHORT: 14,294,404で0.01買い
  - execution_price = 14,294,404
  - exit_commission = 14,294,404 * 0.01 * 0.001 = 142.94 JPY
  - pnl = (14,300,308 - 14,294,404) * 0.01 - 143.00 - 142.94 = 59.04 - 285.94 = **-226.90 JPY**

実際には、手数料が2回かかるため、利益（59.04 JPY）よりも手数料（285.94 JPY）の方が大きくなり、結果としてマイナスPNLになります。

### BUY/SELLの例

- BUY: 14,294,404で0.01買い
  - entry_price = 14,294,404
  - entry_commission = 14,294,404 * 0.01 * 0.001 = 142.94 JPY
  - capital -= (14,294,404 * 0.01 + 142.94) = 143,086.38

- SELL: 14,299,034で0.01売り
  - execution_price = 14,299,034
  - exit_commission = 14,299,034 * 0.01 * 0.001 = 142.99 JPY
  - pnl = (14,299,034 - 14,294,404) * 0.01 - 142.94 - 142.99 = 46.30 - 285.93 = **-239.63 JPY**

同様に、手数料が2回かかるため、利益（46.30 JPY）よりも手数料（285.93 JPY）の方が大きくなり、結果としてマイナスPNLになります。

## 注意事項

手数料率が0.1%（0.001）の場合、往復で0.2%の手数料がかかります。小さな利益では手数料を上回れず、マイナスPNLになる可能性があります。
