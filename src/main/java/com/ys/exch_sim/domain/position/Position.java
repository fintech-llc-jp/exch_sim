package com.ys.exch_sim.domain.position;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Position {
    private String username;
    private String symbol;
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

    public void addBuyTrade(double quantity, double price) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("Quantity and price must be positive");
        }

        // 買いポジション更新
        this.totalBuyAmount += quantity * price;
        this.totalBuyQty += quantity;
        this.averageBuyPrice = this.totalBuyQty > 0 ? this.totalBuyAmount / this.totalBuyQty : 0.0;
        
        updateNetPosition();
        this.lastUpdated = LocalDateTime.now();
    }

    public void addSellTrade(double quantity, double price) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("Quantity and price must be positive");
        }

        // 売りポジション更新
        this.totalSellAmount += quantity * price;
        this.totalSellQty += quantity;
        this.averageSellPrice = this.totalSellQty > 0 ? this.totalSellAmount / this.totalSellQty : 0.0;
        
        // 実現損益の計算（売りの場合、既存の買いポジションがあれば実現）
        if (this.netQty > 0) {
            double realizedQty = Math.min(quantity, this.netQty);
            this.realizedPnL += realizedQty * (price - this.averageBuyPrice);
        }
        
        updateNetPosition();
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