package com.ys.exch_sim.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketBoardResponse {
  private String symbol;
  private List<PriceLevel> bids; // 買い注文（価格降順）
  private List<PriceLevel> asks; // 売り注文（価格昇順）

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class PriceLevel {
    private Double price;
    private Long quantity;
  }
}
