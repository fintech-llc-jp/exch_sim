package com.ys.exch_sim.domain.market_data.dto;

import java.time.Instant;

/**
 * 外部取引データを表すレコード
 */
public record ExternalTradeData(
    String exchange,
    String symbol,
    Double price,
    Double quantity,
    String side,
    Instant timestamp
) {
    public ExternalTradeData {
        if (exchange == null || exchange.trim().isEmpty()) {
            throw new IllegalArgumentException("Exchange cannot be null or empty");
        }
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("Symbol cannot be null or empty");
        }
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (side == null || (!side.equals("BUY") && !side.equals("SELL"))) {
            throw new IllegalArgumentException("Side must be 'BUY' or 'SELL'");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
    
    /**
     * 取引金額を計算
     */
    public Double getNotionalAmount() {
        return price * quantity;
    }
    
    /**
     * サイドがBUYかどうかを判定
     */
    public boolean isBuy() {
        return "BUY".equals(side);
    }
    
    /**
     * サイドがSELLかどうかを判定
     */
    public boolean isSell() {
        return "SELL".equals(side);
    }
}