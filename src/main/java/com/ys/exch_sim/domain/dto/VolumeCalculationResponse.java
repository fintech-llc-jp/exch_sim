package com.ys.exch_sim.domain.dto;

import java.time.LocalDateTime;

public record VolumeCalculationResponse(
    String symbol,
    LocalDateTime fromTime,
    LocalDateTime toTime,
    Double totalVolume,
    Long executionCount,
    String timeRangeDescription
) {
}