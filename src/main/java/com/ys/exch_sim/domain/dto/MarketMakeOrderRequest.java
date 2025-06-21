package com.ys.exch_sim.domain.dto;

import java.util.List;
import lombok.Data;

@Data
public class MarketMakeOrderRequest {
  private String symbol;
  private List<OrderLevel> bidLevels;
  private List<OrderLevel> askLevels;

  @Data
  public static class OrderLevel {
    private double price;
    private double quantity;
    private String ordType = "LIMIT"; // デフォルトは指値
    private String tif = "GTC"; // デフォルトはGTC

    public OrderLevel() {}

    public OrderLevel(double price, double quantity) {
      this.price = price;
      this.quantity = quantity;
    }

    public OrderLevel(double price, double quantity, String ordType, String tif) {
      this.price = price;
      this.quantity = quantity;
      this.ordType = ordType;
      this.tif = tif;
    }
  }
}
