package com.ys.exch_sim.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class TradeHistoryResponse {
  private String username;
  private int totalCount;
  private List<TradeHistoryDto> trades;

  public TradeHistoryResponse(String username, int totalCount, List<TradeHistoryDto> trades) {
    this.username = username;
    this.totalCount = totalCount;
    this.trades = trades;
  }

  @Data
  public static class TradeHistoryDto {
    private String execID;
    private String symbol;
    private String side;
    private double quantity;
    private double price;
    private double amount;
    private String counterPartyUsername;
    private LocalDateTime timestamp;
    private String clOrdID;
    private String openClose; // "OPEN" or "CLOSE"
    private Double profitLoss; // P/L for CLOSE trades (null for OPEN)

    public TradeHistoryDto(
        String execID,
        String symbol,
        String side,
        double quantity,
        double price,
        double amount,
        String counterPartyUsername,
        LocalDateTime timestamp,
        String clOrdID) {
      this.execID = execID;
      this.symbol = symbol;
      this.side = side;
      this.quantity = quantity;
      this.price = price;
      this.amount = amount;
      this.counterPartyUsername = counterPartyUsername;
      this.timestamp = timestamp;
      this.clOrdID = clOrdID;
    }

    public TradeHistoryDto(
        String execID,
        String symbol,
        String side,
        double quantity,
        double price,
        double amount,
        String counterPartyUsername,
        LocalDateTime timestamp,
        String clOrdID,
        String openClose,
        Double profitLoss) {
      this.execID = execID;
      this.symbol = symbol;
      this.side = side;
      this.quantity = quantity;
      this.price = price;
      this.amount = amount;
      this.counterPartyUsername = counterPartyUsername;
      this.timestamp = timestamp;
      this.clOrdID = clOrdID;
      this.openClose = openClose;
      this.profitLoss = profitLoss;
    }
  }
}
