package com.ys.exch_sim.domain.position;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trade_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistoryEntity {
    
    @Id
    @Column(name = "exec_id")
    private String execId;
    
    @Column(name = "username", nullable = false)
    private String username;
    
    @Column(name = "symbol", nullable = false)
    private String symbol;
    
    @Column(name = "side", nullable = false)
    private String side; // BUY or SELL
    
    @Column(name = "quantity", nullable = false)
    private Double quantity;
    
    @Column(name = "price", nullable = false)
    private Double price;
    
    @Column(name = "amount", nullable = false)
    private Double amount; // quantity * price
    
    @Column(name = "counter_party_username")
    private String counterPartyUsername;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
    
    @Column(name = "cl_ord_id")
    private String clOrdId;
    
    @Column(name = "is_market_maker", nullable = false)
    private Boolean isMarketMaker;
    
    // Constructor from TradeHistory domain object
    public TradeHistoryEntity(TradeHistory tradeHistory) {
        this.execId = tradeHistory.getExecID();
        this.username = tradeHistory.getUsername();
        this.symbol = tradeHistory.getSymbol();
        this.side = tradeHistory.getSide();
        // Convert actual quantity to storage format (multiply by 1000)
        this.quantity = tradeHistory.getQuantity() * 1000.0;
        this.price = tradeHistory.getPrice();
        this.amount = tradeHistory.getAmount();
        this.counterPartyUsername = tradeHistory.getCounterPartyUsername();
        this.timestamp = tradeHistory.getTimestamp();
        this.clOrdId = tradeHistory.getClOrdID();
        this.isMarketMaker = false; // Default value, will be set based on context
    }
    
    // Convert to TradeHistory domain object
    public TradeHistory toTradeHistory() {
        // Convert stored quantity (may be 1000x) to actual quantity
        double actualQuantity = this.quantity != null ? this.quantity / 1000.0 : 0.0;
        
        TradeHistory tradeHistory = new TradeHistory(
            this.execId,
            this.username,
            this.symbol,
            this.side,
            actualQuantity,
            this.price,
            this.counterPartyUsername,
            this.clOrdId
        );
        tradeHistory.setTimestamp(this.timestamp);
        return tradeHistory;
    }
}