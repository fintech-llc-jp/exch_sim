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
        // Convert double to long (multiply by 1000 for storage)
        this.totalBuyQty = (long) (position.getTotalBuyQty() * 1000);
        this.totalBuyAmount = position.getTotalBuyAmount();
        this.totalSellQty = (long) (position.getTotalSellQty() * 1000);
        this.totalSellAmount = position.getTotalSellAmount();
        this.netQty = (long) (position.getNetQty() * 1000);
        this.averageBuyPrice = position.getAverageBuyPrice();
        this.averageSellPrice = position.getAverageSellPrice();
        this.realizedPnL = position.getRealizedPnL();
        this.lastUpdated = position.getLastUpdated();
    }
    
    // Convert to Position domain object
    public Position toPosition() {
        Position position = new Position(this.username, this.symbol);
        // Convert long to double (divide by 1000 from storage)
        position.setTotalBuyQty(this.totalBuyQty != null ? this.totalBuyQty / 1000.0 : 0.0);
        position.setTotalBuyAmount(this.totalBuyAmount != null ? this.totalBuyAmount : 0.0);
        position.setTotalSellQty(this.totalSellQty != null ? this.totalSellQty / 1000.0 : 0.0);
        position.setTotalSellAmount(this.totalSellAmount != null ? this.totalSellAmount : 0.0);
        position.setNetQty(this.netQty != null ? this.netQty / 1000.0 : 0.0);
        position.setAverageBuyPrice(this.averageBuyPrice != null ? this.averageBuyPrice : 0.0);
        position.setAverageSellPrice(this.averageSellPrice != null ? this.averageSellPrice : 0.0);
        position.setRealizedPnL(this.realizedPnL != null ? this.realizedPnL : 0.0);
        position.setLastUpdated(this.lastUpdated != null ? this.lastUpdated : java.time.LocalDateTime.now());
        return position;
    }
}