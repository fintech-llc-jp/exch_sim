package com.ys.exch_sim.domain.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PositionResponse {
    private String username;
    private String symbol;
    private long totalBuyQty;
    private double totalBuyAmount;
    private long totalSellQty;
    private double totalSellAmount;
    private long netQty;
    private double averageBuyPrice;
    private double averageSellPrice;
    private double realizedPnL;
    private double unrealizedPnL;
    private double totalPnL;
    private LocalDateTime lastUpdated;

    public PositionResponse(String username, String symbol, long totalBuyQty, double totalBuyAmount,
                           long totalSellQty, double totalSellAmount, long netQty,
                           double averageBuyPrice, double averageSellPrice, double realizedPnL,
                           double unrealizedPnL, double totalPnL, LocalDateTime lastUpdated) {
        this.username = username;
        this.symbol = symbol;
        this.totalBuyQty = totalBuyQty;
        this.totalBuyAmount = totalBuyAmount;
        this.totalSellQty = totalSellQty;
        this.totalSellAmount = totalSellAmount;
        this.netQty = netQty;
        this.averageBuyPrice = averageBuyPrice;
        this.averageSellPrice = averageSellPrice;
        this.realizedPnL = realizedPnL;
        this.unrealizedPnL = unrealizedPnL;
        this.totalPnL = totalPnL;
        this.lastUpdated = lastUpdated;
    }
}