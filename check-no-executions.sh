#!/bin/bash
# B_FX_BTCJPYで約定が発生しない問題のデバッグスクリプト

echo "=== B_FX_BTCJPYで約定が発生しない問題のデバッグ ==="
echo ""

# 1. スキップログの確認（最重要）
echo "1. マーケットメーカー注文の処理がスキップされているか:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Skipping market maker order processing" | grep -i "B_FX_BTCJPY" | tail -20
if [ $? -eq 0 ]; then
    echo "   → スキップログが見つかりました。ユーザー注文がない可能性があります。"
else
    echo "   → スキップログは見つかりませんでした。"
fi
echo ""

# 2. ユーザー注文の存在確認
echo "2. ユーザー注文の追加ログ:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "B_FX_BTCJPY" | grep -i "Processing new order" | tail -20
if [ $? -eq 0 ]; then
    echo "   → ユーザー注文が追加されています。"
else
    echo "   → ユーザー注文の追加ログが見つかりませんでした。"
fi
echo ""

# 3. 板情報の更新確認
echo "3. 板情報の更新ログ（最新10件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Board snapshot updated" | grep -i "B_FX_BTCJPY" | tail -10
echo ""

# 4. マーケットメーカー注文の処理ログ
echo "4. マーケットメーカー注文の処理ログ:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "B_FX_BTCJPY" | grep -i "market maker" | grep -v "Skipping" | tail -20
echo ""

# 5. マッチングの試行ログ
echo "5. マッチングの試行ログ:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "B_FX_BTCJPY" | grep -i "matched\|matching check" | tail -20
echo ""

# 6. 約定ログ（発生しているか確認）
echo "6. 約定ログ（直近20件）:"
sudo journalctl -u exch-sim-rust --since "1 hour ago" | grep -i "Market maker execution" | grep -i "B_FX_BTCJPY" | tail -20
if [ $? -eq 0 ]; then
    echo "   → 約定が発生しています。"
else
    echo "   → 約定ログが見つかりませんでした。"
fi
echo ""

# 7. エラーログ
echo "7. エラーログ:"
sudo journalctl -u exch-sim-rust -p err --since "1 hour ago" | grep -i "B_FX_BTCJPY" | tail -10
if [ $? -ne 0 ]; then
    echo "   → エラーログは見つかりませんでした。"
fi
echo ""

# 8. 警告ログ
echo "8. 警告ログ（B_FX_BTCJPY関連）:"
sudo journalctl -u exch-sim-rust -p warning --since "1 hour ago" | grep -i "B_FX_BTCJPY" | tail -20
echo ""

echo "=== 分析完了 ==="
echo ""
echo "次のステップ:"
echo "1. スキップログが表示されている場合 → ユーザー注文がないため正常です"
echo "2. ユーザー注文があるのにスキップされている場合 → has_user_orders()の実装を確認"
echo "3. 板情報が更新されていない場合 → WebSocketの接続を確認"
echo "4. マッチングが試みられていない場合 → マッチング条件を確認"
