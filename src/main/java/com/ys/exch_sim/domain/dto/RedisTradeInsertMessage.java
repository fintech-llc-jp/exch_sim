package com.ys.exch_sim.domain.dto;

public record RedisTradeInsertMessage(
    String symbol,
    Double price,
    Double quantity,
    String side
) {}