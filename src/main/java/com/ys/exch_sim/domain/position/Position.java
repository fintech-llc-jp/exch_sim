package com.ys.exch_sim.domain.position;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Position {
    private String username;
    private String symbol;
    private String unit;              // 単位（BTC, ETH, JPYなど）
    private double totalBuyQty;       // 総買い数量
    private double totalBuyAmount;    // 総買い金額
    private double totalSellQty;      // 総売り数量
    private double totalSellAmount;   // 総売り金額
    private double netQty;            // ネットポジション（買い - 売り）
    private double averageBuyPrice;   // 平均買い単価
    private double averageSellPrice;  // 平均売り単価
    private double realizedPnL;       // 実現損益
    private LocalDateTime lastUpdated; // 最終更新時刻

    public Position(String username, String symbol) {
        this.username = username;
        this.symbol = symbol;
        this.unit = determineUnit(symbol);
        this.totalBuyQty = 0.0;
        this.totalBuyAmount = 0.0;
        this.totalSellQty = 0.0;
        this.totalSellAmount = 0.0;
        this.netQty = 0.0;
        this.averageBuyPrice = 0.0;
        this.averageSellPrice = 0.0;
        this.realizedPnL = 0.0;
        this.lastUpdated = LocalDateTime.now();
    }
    
    // Helper method to determine unit from symbol
    private String determineUnit(String symbol) {
        if ("JPY".equals(symbol)) {
            return "JPY";
        } else if (symbol.contains("BTC")) {
            return "BTC";
        } else if (symbol.contains("ETH")) {
            return "ETH";
        } else {
            return "UNIT"; // デフォルト
        }
    }

    public void addBuyTrade(double quantity, double price) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("Quantity and price must be positive");
        }

        // 買いトレード前のnetQtyを保存
        double previousNetQty = this.netQty;

        // ショートポジションがある場合の実現損益計算（平均価格更新前に計算）
        if (previousNetQty < 0) {
            // ショートポジションの一部または全部を買い戻し
            double realizedQty = Math.min(quantity, Math.abs(previousNetQty));
            this.realizedPnL += realizedQty * (this.averageSellPrice - price);
        }

        // 買いポジション更新
        this.totalBuyAmount += quantity * price;
        this.totalBuyQty += quantity;
        this.averageBuyPrice = this.totalBuyQty > 0 ? this.totalBuyAmount / this.totalBuyQty : 0.0;

        // netQtyを更新
        this.netQty = previousNetQty + quantity;

        // ポジションがフラットになった場合、すべての累積値をリセット
        if (this.netQty == 0) {
            this.totalBuyQty = 0.0;
            this.totalBuyAmount = 0.0;
            this.totalSellQty = 0.0;
            this.totalSellAmount = 0.0;
            this.averageBuyPrice = 0.0;
            this.averageSellPrice = 0.0;
        }
        // ショートからロングに反転した場合、売りの累積値をリセットし、買いを調整
        else if (previousNetQty < 0 && this.netQty > 0) {
            this.totalSellQty = 0.0;
            this.totalSellAmount = 0.0;
            this.averageSellPrice = 0.0;
            // 買いの累積値を反転後のnetQtyに合わせる（反転を引き起こしたトレードの価格を使用）
            this.totalBuyQty = this.netQty;
            this.totalBuyAmount = this.netQty * price;
            this.averageBuyPrice = price;
        }

        this.lastUpdated = LocalDateTime.now();
    }

    public void addSellTrade(double quantity, double price) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("Quantity and price must be positive");
        }

        // 売りトレード前のnetQtyを保存
        double previousNetQty = this.netQty;

        // 実現損益の計算（平均価格更新前に計算）
        if (previousNetQty > 0) {
            // ロングポジションの一部または全部を売り
            double realizedQty = Math.min(quantity, previousNetQty);
            this.realizedPnL += realizedQty * (price - this.averageBuyPrice);
        }

        // 売りポジション更新
        this.totalSellAmount += quantity * price;
        this.totalSellQty += quantity;
        this.averageSellPrice = this.totalSellQty > 0 ? this.totalSellAmount / this.totalSellQty : 0.0;

        // netQtyを更新
        this.netQty = previousNetQty - quantity;

        // ポジションがフラットになった場合、すべての累積値をリセット
        if (this.netQty == 0) {
            this.totalBuyQty = 0.0;
            this.totalBuyAmount = 0.0;
            this.totalSellQty = 0.0;
            this.totalSellAmount = 0.0;
            this.averageBuyPrice = 0.0;
            this.averageSellPrice = 0.0;
        }
        // ロングからショートに反転した場合、買いの累積値をリセットし、売りを調整
        else if (previousNetQty > 0 && this.netQty < 0) {
            this.totalBuyQty = 0.0;
            this.totalBuyAmount = 0.0;
            this.averageBuyPrice = 0.0;
            // 売りの累積値を反転後のnetQtyに合わせる（反転を引き起こしたトレードの価格を使用）
            this.totalSellQty = Math.abs(this.netQty);
            this.totalSellAmount = Math.abs(this.netQty) * price;
            this.averageSellPrice = price;
        }

        this.lastUpdated = LocalDateTime.now();
    }

    private void updateNetPosition() {
        this.netQty = this.totalBuyQty - this.totalSellQty;
    }

    public double getUnrealizedPnL(double currentPrice) {
        if (this.netQty == 0 || currentPrice <= 0) {
            return 0.0;
        }
        
        if (this.netQty > 0) {
            // ロングポジションの含み損益
            return this.netQty * (currentPrice - this.averageBuyPrice);
        } else {
            // ショートポジションの含み損益
            return Math.abs(this.netQty) * (this.averageSellPrice - currentPrice);
        }
    }

    public double getTotalPnL(double currentPrice) {
        return this.realizedPnL + getUnrealizedPnL(currentPrice);
    }

    public boolean isLongPosition() {
        return this.netQty > 0;
    }

    public boolean isShortPosition() {
        return this.netQty < 0;
    }

    public boolean isFlat() {
        return this.netQty == 0;
    }
}