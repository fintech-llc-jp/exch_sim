package com.ys.exch_sim.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderResponse {
  private String clOrdID;
  private String status;
  private List<ExecutionDto> executions;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ExecutionDto {
    private String execID;
    private String execStatus;
    private Double lastPx;
    private Long lastQty;
  }
}
