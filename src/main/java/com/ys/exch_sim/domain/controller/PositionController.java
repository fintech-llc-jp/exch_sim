package com.ys.exch_sim.domain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.dto.PortfolioSummaryResponse;
import com.ys.exch_sim.domain.dto.PositionResponse;
import com.ys.exch_sim.domain.dto.TradeHistoryResponse;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.domain.position.TradeHistory;
import com.ys.exch_sim.domain.service.OrderService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/positions")
public class PositionController {

  private final PositionManager positionManager;
  private final OrderService orderService;

  private final ObjectMapper objectMapper;

  public PositionController(
      PositionManager positionManager, OrderService orderService, ObjectMapper objectMapper) {
    this.positionManager = positionManager;
    this.orderService = orderService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/summary")
  public ResponseEntity<?> getPortfolioSummary(Authentication authentication) {
    try {
      String username = authentication.getName();
      log.info("Getting portfolio summary for user: {}", username);

      // 現在価格を取得（簡易的に最新の板情報から取得）
      Map<String, Double> currentPrices = getCurrentPrices(username);

      // ポートフォリオサマリーを作成
      double totalRealizedPnL = positionManager.getTotalRealizedPnL(username);
      double totalUnrealizedPnL = positionManager.getTotalUnrealizedPnL(username, currentPrices);
      double totalPnL = positionManager.getTotalPnL(username, currentPrices);
      int totalTradeCount = positionManager.getTotalTradeCount(username);
      double totalTradingVolume = positionManager.getTotalTradingVolume(username);
      Map<String, Long> symbolTradeCounts = positionManager.getSymbolTradeCounts(username);

      // ポジション一覧を取得
      List<Position> positions = positionManager.getAllPositions(username);
      List<PositionResponse> positionResponses =
          positions.stream()
              .map(
                  position ->
                      convertToPositionResponse(position, currentPrices.get(position.getSymbol())))
              .collect(Collectors.toList());

      PortfolioSummaryResponse response =
          new PortfolioSummaryResponse(
              username,
              totalRealizedPnL,
              totalUnrealizedPnL,
              totalPnL,
              totalTradeCount,
              totalTradingVolume,
              positionResponses,
              symbolTradeCounts);

      log.info(
          "💰 {} Portfolio - Total PnL: {}, Realized: {}, Unrealized: {}, Positions: {}",
          username,
          totalPnL,
          totalRealizedPnL,
          totalUnrealizedPnL,
          objectMapper.writeValueAsString(positionResponses));

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting portfolio summary for user: " + authentication.getName(), e);
      return ResponseEntity.internalServerError()
          .body("Error retrieving portfolio summary: " + e.getMessage());
    }
  }

  @GetMapping("/{symbol}")
  public ResponseEntity<?> getPositionBySymbol(
      @PathVariable String symbol, Authentication authentication) {
    try {
      String username = authentication.getName();
      log.info("Getting position for user: {}, symbol: {}", username, symbol);

      Position position = positionManager.getPosition(username, symbol);
      if (position == null) {
        return ResponseEntity.ok(
            new PositionResponse(
                username, symbol.toUpperCase(), 0, 0.0, 0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, null));
      }

      // 現在価格を取得
      double currentPrice = getCurrentPrice(symbol);
      PositionResponse response = convertToPositionResponse(position, currentPrice);

      log.info(
          "📍 {} {} Position - Net: {}, PnL: {}, Current Price: {}",
          username,
          symbol,
          position.getNetQty(),
          position.getTotalPnL(currentPrice),
          currentPrice);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error(
          "Error getting position for user: " + authentication.getName() + ", symbol: " + symbol,
          e);
      return ResponseEntity.internalServerError()
          .body("Error retrieving position: " + e.getMessage());
    }
  }

  @GetMapping("/trades")
  public ResponseEntity<?> getTradeHistory(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String symbol,
      Authentication authentication) {
    try {
      String username = authentication.getName();
      log.info(
          "Getting trade history for user: {}, limit: {}, symbol: {}", username, limit, symbol);

      List<TradeHistory> trades;
      if (symbol != null && !symbol.trim().isEmpty()) {
        trades = positionManager.getTradeHistory(username, symbol.toUpperCase());
      } else {
        trades = positionManager.getTradeHistory(username, limit);
      }

      List<TradeHistoryResponse.TradeHistoryDto> tradeDtos =
          trades.stream()
              .map(
                  trade ->
                      new TradeHistoryResponse.TradeHistoryDto(
                          trade.getExecID(),
                          trade.getSymbol(),
                          trade.getSide(),
                          trade.getQuantity(),
                          trade.getPrice(),
                          trade.getAmount(),
                          trade.getCounterPartyUsername(),
                          trade.getTimestamp(),
                          trade.getClOrdID()))
              .collect(Collectors.toList());

      TradeHistoryResponse response = new TradeHistoryResponse(username, trades.size(), tradeDtos);

      log.info(
          "📜 {} Trade History - Total trades: {}, Symbol filter: {}",
          username,
          trades.size(),
          symbol != null ? symbol : "all");

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting trade history for user: " + authentication.getName(), e);
      return ResponseEntity.internalServerError()
          .body("Error retrieving trade history: " + e.getMessage());
    }
  }

  private PositionResponse convertToPositionResponse(Position position, Double currentPrice) {
    double currentPx = currentPrice != null ? currentPrice : 0.0;
    double unrealizedPnL = position.getUnrealizedPnL(currentPx);
    double totalPnL = position.getTotalPnL(currentPx);

    return new PositionResponse(
        position.getUsername(),
        position.getSymbol(),
        position.getTotalBuyQty(),
        position.getTotalBuyAmount(),
        position.getTotalSellQty(),
        position.getTotalSellAmount(),
        position.getNetQty(),
        position.getAverageBuyPrice(),
        position.getAverageSellPrice(),
        position.getRealizedPnL(),
        unrealizedPnL,
        totalPnL,
        position.getLastUpdated());
  }

  private Map<String, Double> getCurrentPrices(String username) {
    Map<String, Double> currentPrices = new HashMap<>();
    List<Position> positions = positionManager.getAllPositions(username);

    for (Position position : positions) {
      double currentPrice = getCurrentPrice(position.getSymbol());
      currentPrices.put(position.getSymbol(), currentPrice);
    }

    return currentPrices;
  }

  private double getCurrentPrice(String symbol) {
    try {
      // 板情報から中値を取得（簡易的な現在価格）
      var marketBoard = orderService.getMarketBoard(symbol, 1);
      if (marketBoard.getBids().isEmpty() || marketBoard.getAsks().isEmpty()) {
        return 0.0;
      }

      double bestBid = marketBoard.getBids().get(0).getPrice();
      double bestAsk = marketBoard.getAsks().get(0).getPrice();
      return (bestBid + bestAsk) / 2.0;

    } catch (Exception e) {
      log.warn("Could not get current price for symbol: {}, returning 0.0", symbol);
      return 0.0;
    }
  }
}
