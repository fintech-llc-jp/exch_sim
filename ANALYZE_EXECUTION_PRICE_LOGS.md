# 約定価格乖離問題のログ分析

## ログ確認コマンド

### 1. マーケットメーカー注文の処理ログ

```bash
# B_FX_BTCJPYのマーケットメーカー注文処理ログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "market maker" | tail -100

# 約定が発生した場合のログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "matched\|execution" | tail -100

# フィルタリングが機能しているか確認
sudo journalctl -u exch-sim-rust | grep -i "Filter out\|market maker.*match" | tail -50
```

### 2. 板情報の更新ログ

```bash
# 板情報の更新ログ（snapshot/delta）
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "snapshot\|delta\|board" | tail -100

# update_external_market_dataが呼ばれているか確認
sudo journalctl -u exch-sim-rust | grep -i "update_external_market_data\|External market data" | tail -50
```

### 3. 約定価格と板情報の比較ログ

```bash
# 約定価格のログ（MARKET_MAKERの約定）
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "MARKET_MAKER" | grep -i "execution\|last_px" | tail -100

# 板情報のBID/ASK価格
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -E "bid|ask|BID|ASK" | tail -100
```

### 4. エラーログ

```bash
# エラーログ
sudo journalctl -u exch-sim-rust -p err | grep -i "B_FX_BTCJPY" | tail -50

# 警告ログ
sudo journalctl -u exch-sim-rust -p warning | grep -i "B_FX_BTCJPY" | tail -50
```

### 5. 詳細なデバッグログ（RUST_LOG=debugの場合）

```bash
# デバッグログ全体
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -i "debug" | tail -200

# マッチング処理の詳細
sudo journalctl -u exch-sim-rust | grep -i "matching\|get_matching_orders" | tail -100
```

## 問題特定のためのログ分析

### パターン1: フィルタリングが機能していない場合

```bash
# マーケットメーカー注文同士がマッチングしているか確認
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -A 5 "market maker.*matched" | tail -100

# カウンターパーティがMARKET_MAKERの約定を確認
sudo journalctl -u exch-sim-rust | grep -i "counter_party.*MARKET_MAKER" | tail -50
```

### パターン2: 板情報が更新されていない場合

```bash
# update_external_market_dataが呼ばれているか
sudo journalctl -u exch-sim-rust | grep -i "update_external_market_data" | grep -i "B_FX_BTCJPY" | tail -50

# 板情報の更新タイミング
sudo journalctl -u exch-sim-rust | grep -i "External market data.*B_FX_BTCJPY" | tail -50
```

### パターン3: 古い板情報とマッチングしている場合

```bash
# 板情報の更新時刻と約定時刻を比較
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -E "timestamp|created_at|update_time" | tail -100

# 約定前の板情報を確認
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -B 10 "execution\|matched" | tail -200
```

## 時系列でのログ分析

```bash
# 直近5分間のログを時系列で確認
sudo journalctl -u exch-sim-rust --since "5 minutes ago" | grep -i "B_FX_BTCJPY" | tail -200

# 特定の時刻のログを確認（例：12:27:24の約定）
sudo journalctl -u exch-sim-rust --since "2026-01-21 12:27:20" --until "2026-01-21 12:27:30" | grep -i "B_FX_BTCJPY"
```

## ログから価格情報を抽出

```bash
# 約定価格を抽出
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -oP "last_px[=:]\s*\d+" | tail -50

# 板情報のBID/ASK価格を抽出
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -oP "(bid|ask)[=:]\s*\d+" | tail -50
```

## データベースとログの照合

```bash
# データベースの約定時刻に対応するログを確認
# 例：2026-01-21 12:27:24.113542の約定
sudo journalctl -u exch-sim-rust --since "2026-01-21 12:27:20" --until "2026-01-21 12:27:30" | grep -i "B_FX_BTCJPY"
```

## ログレベルの確認と変更

```bash
# 現在のログレベルを確認
sudo systemctl show exch-sim-rust | grep Environment

# ログレベルをdebugに変更（再起動が必要）
sudo systemctl edit exch-sim-rust
# 以下を追加：
# [Service]
# Environment="RUST_LOG=debug"

# サービスを再起動
sudo systemctl daemon-reload
sudo systemctl restart exch-sim-rust
```

## 問題の特定に役立つログパターン

### 1. マーケットメーカー注文が既存のマーケットメーカー注文とマッチングしている場合

```bash
# カウンターパーティがMARKET_MAKERの約定
sudo journalctl -u exch-sim-rust | grep -i "counter_party.*MARKET_MAKER" | grep -i "B_FX_BTCJPY"
```

### 2. 板情報が更新されずに古い価格でマッチングしている場合

```bash
# 約定直前の板情報更新ログ
sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | grep -B 5 "execution\|matched" | grep -i "board\|snapshot\|delta"
```

### 3. フィルタリングが機能していない場合

```bash
# フィルタリングのログ（実装されていない場合は出力されない）
sudo journalctl -u exch-sim-rust | grep -i "Filter out market maker"
```

## 推奨される分析手順

1. **まず、エラーログを確認**
   ```bash
   sudo journalctl -u exch-sim-rust -p err | tail -50
   ```

2. **B_FX_BTCJPY関連のログを確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY" | tail -200
   ```

3. **問題が発生した時刻のログを詳細に確認**
   ```bash
   # 例：12:27:24の約定
   sudo journalctl -u exch-sim-rust --since "2026-01-21 12:27:20" --until "2026-01-21 12:27:30"
   ```

4. **マーケットメーカー注文の処理ログを確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "market maker.*B_FX_BTCJPY" | tail -100
   ```

5. **約定が発生した際のログを確認**
   ```bash
   sudo journalctl -u exch-sim-rust | grep -i "B_FX_BTCJPY.*matched\|execution" | tail -100
   ```
