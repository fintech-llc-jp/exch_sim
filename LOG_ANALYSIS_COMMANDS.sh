#!/bin/bash
# 約定価格乖離問題のログ分析用スクリプト

echo "=== B_FX_BTCJPYの約定価格乖離問題のログ分析 ==="
echo ""

# 1. 直近のエラーログ
echo "1. 直近のエラーログ:"
sudo journalctl -u exch-sim-rust -p err --since "1 hour ago" | grep -i "B_FX_BTCJPY" | tail -20
echo ""

# 2. マーケットメーカー注文の処理ログ
echo "2. マーケットメーカー注文の処理ログ（直近50件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "B_FX_BTCJPY" | grep -i "market maker" | tail -50
echo ""

# 3. フィルタリングのログ（警告）
echo "3. マーケットメーカー注文のフィルタリングログ（警告）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Market maker order filtering" | grep -i "B_FX_BTCJPY" | tail -20
echo ""

# 4. 約定ログ
echo "4. 約定ログ（直近50件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Market maker execution" | grep -i "B_FX_BTCJPY" | tail -50
echo ""

# 5. 板情報の更新ログ
echo "5. 板情報の更新ログ（直近30件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -30
echo ""

# 6. 約定価格が範囲外の可能性があるログ
echo "6. 約定と板情報の時系列比較（直近100件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "B_FX_BTCJPY" | grep -E "execution|Board snapshot|matched" | tail -100
echo ""

# 7. カウンターパーティがMARKET_MAKERの約定（問題の可能性）
echo "7. カウンターパーティがMARKET_MAKERの約定（問題の可能性）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Market maker execution" | grep -i "B_FX_BTCJPY" | grep -i "counter_party.*MARKET_MAKER" | tail -20
echo ""

# 8. 約定価格と板情報の比較（特定の時刻）
echo "8. 最新の約定時刻を確認:"
LATEST_EXEC_TIME=$(sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Market maker execution" | grep -i "B_FX_BTCJPY" | tail -1 | grep -oP "\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}" | head -1)
if [ ! -z "$LATEST_EXEC_TIME" ]; then
    echo "最新の約定時刻: $LATEST_EXEC_TIME"
    echo "その前後の板情報:"
    sudo journalctl -u exch-sim-rust --since "$(date -d "$LATEST_EXEC_TIME - 10 seconds" '+%Y-%m-%d %H:%M:%S')" --until "$(date -d "$LATEST_EXEC_TIME + 10 seconds" '+%Y-%m-%d %H:%M:%S')" | grep -i "B_FX_BTCJPY" | grep -E "execution|Board snapshot"
else
    echo "約定ログが見つかりませんでした"
fi
echo ""

echo "=== 分析完了 ==="
