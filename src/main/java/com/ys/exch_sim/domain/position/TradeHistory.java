package com.ys.exch_sim.domain.position;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TradeHistory {
    private String execID;
    private String username;
    private String symbol;
    private String side;              // BUY or SELL
    private long quantity;
    private double price;
    private double amount;            // quantity * price
    private String counterPartyUsername;
    private LocalDateTime timestamp;
    private String clOrdID;

    public TradeHistory(String execID, String username, String symbol, String side, 
                       long quantity, double price, String counterPartyUsername, 
                       String clOrdID) {
        this.execID = execID;
        this.username = username;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.amount = quantity * price;
        this.counterPartyUsername = counterPartyUsername;
        this.clOrdID = clOrdID;
        this.timestamp = LocalDateTime.now();
    }
}