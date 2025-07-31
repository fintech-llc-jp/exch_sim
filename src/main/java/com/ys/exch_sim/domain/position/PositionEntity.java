package com.ys.exch_sim.domain.position;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionEntity {
    
    @Id
    private String id; // username + "_" + symbol
    
    @Column(name = "username", nullable = false)
    private String username;
    
    @Column(name = "symbol", nullable = false)
    private String symbol;
    
    @Column(name = "total_buy_qty", nullable = false)
    private Long totalBuyQty;
    
    @Column(name = "total_buy_amount", nullable = false)
    private Double totalBuyAmount;
    
    @Column(name = "total_sell_qty", nullable = false)
    private Long totalSellQty;
    
    @Column(name = "total_sell_amount", nullable = false)
    private Double totalSellAmount;
    
    @Column(name = "net_qty", nullable = false)
    private Long netQty;
    
    @Column(name = "average_buy_price", nullable = false)
    private Double averageBuyPrice;
    
    @Column(name = "average_sell_price", nullable = false)
    private Double averageSellPrice;
    
    @Column(name = "realized_pnl", nullable = false)
    private Double realizedPnL;
    
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;
    
    // Helper method to create ID
    public static String createId(String username, String symbol) {
        return username + "_" + symbol.toUpperCase();
    }
    
    // Constructor from Position domain object
    public PositionEntity(Position position) {
        this.id = createId(position.getUsername(), position.getSymbol());
        this.username = position.getUsername();
        this.symbol = position.getSymbol();
        this.totalBuyQty = position.getTotalBuyQty();
        this.totalBuyAmount = position.getTotalBuyAmount();
        this.totalSellQty = position.getTotalSellQty();
        this.totalSellAmount = position.getTotalSellAmount();
        this.netQty = position.getNetQty();
        this.averageBuyPrice = position.getAverageBuyPrice();
        this.averageSellPrice = position.getAverageSellPrice();
        this.realizedPnL = position.getRealizedPnL();
        this.lastUpdated = position.getLastUpdated();
    }
    
    // Convert to Position domain object
    public Position toPosition() {
        Position position = new Position(this.username, this.symbol);
        position.setTotalBuyQty(this.totalBuyQty);
        position.setTotalBuyAmount(this.totalBuyAmount);
        position.setTotalSellQty(this.totalSellQty);
        position.setTotalSellAmount(this.totalSellAmount);
        position.setNetQty(this.netQty);
        position.setAverageBuyPrice(this.averageBuyPrice);
        position.setAverageSellPrice(this.averageSellPrice);
        position.setRealizedPnL(this.realizedPnL);
        position.setLastUpdated(this.lastUpdated);
        return position;
    }
}