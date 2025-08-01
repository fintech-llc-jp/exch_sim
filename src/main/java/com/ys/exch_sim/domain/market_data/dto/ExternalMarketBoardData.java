package com.ys.exch_sim.domain.market_data.dto;

import java.time.Instant;
import java.util.List;

/**
 * 外部マーケットボードデータを表すレコード
 */
public record ExternalMarketBoardData(
    String exchange,
    String symbol,
    List<PriceLevel> bids,
    List<PriceLevel> asks,
    Instant timestamp
) {
    /**
     * 価格レベルを表すレコード
     */
    public record PriceLevel(Double price, Double quantity) {
        public PriceLevel {
            if (price == null || price <= 0) {
                throw new IllegalArgumentException("Price must be positive");
            }
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
        }
    }
    
    public ExternalMarketBoardData {
        if (exchange == null || exchange.trim().isEmpty()) {
            throw new IllegalArgumentException("Exchange cannot be null or empty");
        }
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("Symbol cannot be null or empty");
        }
        if (bids == null) {
            throw new IllegalArgumentException("Bids cannot be null");
        }
        if (asks == null) {
            throw new IllegalArgumentException("Asks cannot be null");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
    
    /**
     * ベストビッド価格を取得
     */
    public Double getBestBidPrice() {
        return bids.isEmpty() ? null : bids.get(0).price();
    }
    
    /**
     * ベストアスク価格を取得
     */
    public Double getBestAskPrice() {
        return asks.isEmpty() ? null : asks.get(0).price();
    }
    
    /**
     * スプレッドを計算
     */
    public Double getSpread() {
        Double bestBid = getBestBidPrice();
        Double bestAsk = getBestAskPrice();
        if (bestBid == null || bestAsk == null) {
            return null;
        }
        return bestAsk - bestBid;
    }
}