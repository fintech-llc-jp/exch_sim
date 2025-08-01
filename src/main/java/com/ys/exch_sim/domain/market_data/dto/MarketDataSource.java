package com.ys.exch_sim.domain.market_data.dto;

/**
 * マーケットデータのソースを表す列挙型
 */
public enum MarketDataSource {
    BITFLYER("BITFLYER"),
    GMO("GMO");
    
    private final String name;
    
    MarketDataSource(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
    
    @Override
    public String toString() {
        return name;
    }
}