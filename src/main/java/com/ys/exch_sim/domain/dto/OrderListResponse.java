package com.ys.exch_sim.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderListResponse {
  private String username;
  private int totalOrders;
  private List<OrderDto> orders;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class OrderDto {
    private String clOrdID;
    private String symbol;
    private String side;
    private String ordType;
    private String ordStatus;
    private Double orderPx;
    private Double orderQty;
    private Double leavesQty;
    private Double filledQty;
    private String tif;
    private LocalDateTime timestamp;
  }
}
