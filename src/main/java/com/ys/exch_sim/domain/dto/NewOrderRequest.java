package com.ys.exch_sim.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NewOrderRequest {
  private String symbol;
  private Double price;
  private Double quantity;
  private String side; // "BUY" or "SELL"
  private String ordType; // "LIMIT" or "MARKET"
  private String tif; // "GTC", "IOC", "FOK"
  private Boolean isMarketMake;
}
