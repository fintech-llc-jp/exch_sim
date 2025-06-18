package com.ys.exch_sim.domain.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PortfolioSummaryResponse {
    private String username;
    private double totalRealizedPnL;
    private double totalUnrealizedPnL;
    private double totalPnL;
    private int totalTradeCount;
    private double totalTradingVolume;
    private List<PositionResponse> positions;
    private Map<String, Long> symbolTradeCounts;

    public PortfolioSummaryResponse(String username, double totalRealizedPnL, double totalUnrealizedPnL,
                                   double totalPnL, int totalTradeCount, double totalTradingVolume,
                                   List<PositionResponse> positions, Map<String, Long> symbolTradeCounts) {
        this.username = username;
        this.totalRealizedPnL = totalRealizedPnL;
        this.totalUnrealizedPnL = totalUnrealizedPnL;
        this.totalPnL = totalPnL;
        this.totalTradeCount = totalTradeCount;
        this.totalTradingVolume = totalTradingVolume;
        this.positions = positions;
        this.symbolTradeCounts = symbolTradeCounts;
    }
}