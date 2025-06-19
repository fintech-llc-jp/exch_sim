package com.ys.exch_sim.domain.dto;

import lombok.Data;
import java.util.List;

@Data
public class MarketMakeOrderResponse {
    private String username;
    private String symbol;
    private int cancelledOrdersCount;
    private int newBidOrdersCount;
    private int newAskOrdersCount;
    private List<String> bidOrderIds;
    private List<String> askOrderIds;
    private String status;
    private String message;

    public MarketMakeOrderResponse(String username, String symbol) {
        this.username = username;
        this.symbol = symbol;
        this.status = "SUCCESS";
    }

    public MarketMakeOrderResponse(String username, String symbol, String status, String message) {
        this.username = username;
        this.symbol = symbol;
        this.status = status;
        this.message = message;
    }
}