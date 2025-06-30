package com.ys.exch_sim.domain.dto;

import java.util.List;

public record TradeInsertResponse(
    String action,  // "ORDER_PLACED" or "EXECUTION_INSERTED"
    String symbol,
    String side,
    Double price,
    Double quantity,
    String description,
    List<ExecutionSummary> executions
) {
    public record ExecutionSummary(
        String execId,
        String execStatus,
        Double price,
        Double quantity
    ) {}
}