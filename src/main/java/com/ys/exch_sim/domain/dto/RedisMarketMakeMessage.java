package com.ys.exch_sim.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RedisMarketMakeMessage(
    String symbol,
    
    @JsonProperty("bidLevels")
    List<PriceLevel> bidLevels,
    
    @JsonProperty("askLevels")
    List<PriceLevel> askLevels
) {
    public record PriceLevel(
        Double price,
        Double quantity
    ) {}
}