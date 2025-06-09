package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.MarketBoardResponse;
import com.ys.exch_sim.domain.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketBoardController {

  private final OrderService orderService;

  @GetMapping("/board/{symbol}")
  public ResponseEntity<?> getMarketBoard(
      @PathVariable String symbol, @RequestParam(defaultValue = "10") int depth) {

    try {
      // 入力バリデーション
      if (symbol == null || symbol.trim().isEmpty()) {
        log.warn("Invalid symbol parameter");
        return ResponseEntity.badRequest().body("Symbol is required");
      }

      if (depth <= 0 || depth > 100) {
        log.warn("Invalid depth parameter: {}", depth);
        return ResponseEntity.badRequest().body("Depth must be between 1 and 100");
      }

      // 板情報を取得
      MarketBoardResponse response = orderService.getMarketBoard(symbol.toUpperCase(), depth);

      log.info("Market board retrieved successfully for symbol: {} with depth: {}", symbol, depth);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting market board for symbol: " + symbol, e);
      return ResponseEntity.internalServerError()
          .body("Error getting market board: " + e.getMessage());
    }
  }

  @GetMapping("/board/{symbol}/simple")
  public ResponseEntity<?> getMarketBoardSimple(@PathVariable String symbol) {
    try {
      // デフォルトで深度5の板情報を取得
      return getMarketBoard(symbol, 5);
    } catch (Exception e) {
      log.error("Error getting simple market board for symbol: " + symbol, e);
      return ResponseEntity.internalServerError()
          .body("Error getting market board: " + e.getMessage());
    }
  }
}
