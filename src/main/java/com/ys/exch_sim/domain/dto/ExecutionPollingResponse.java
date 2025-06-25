package com.ys.exch_sim.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExecutionPollingResponse {
  private String username;
  private int executionCount;
  private List<ExecutionDto> executions;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ExecutionDto {
    private String execID;
    private String clOrdID;
    private String symbol;
    private String execStatus;
    private Double lastPx;
    private Double lastQty;
    private String counterPartyUsername;
    private String side;
  }
}
