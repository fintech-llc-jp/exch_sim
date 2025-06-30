package com.ys.exch_sim.domain.dto;

public record TradeInsertRequest(
    String symbol,
    Double price,
    Double quantity,
    String side  // "BUY" or "SELL"
) {
}