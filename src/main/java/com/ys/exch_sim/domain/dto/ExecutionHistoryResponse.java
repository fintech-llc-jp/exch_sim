package com.ys.exch_sim.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExecutionHistoryResponse {
  private String username;
  private int page;
  private int size;
  private int totalPages;
  private long totalElements;
  private List<ExecutionHistoryDto> executions;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ExecutionHistoryDto {
    private String execID;
    private String clOrdID;
    private String symbol;
    private String execStatus;
    private Double lastPx;
    private Double lastQty;
    private String counterPartyUsername;
    private String side;
    private LocalDateTime createdAt;
  }
}