package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.MarketBoardResponse;
import com.ys.exch_sim.domain.service.OrderService;
import com.ys.exch_sim.domain.market_data.service.MarketDataClientManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.HashMap;

@Slf4j
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketBoardController {

  private final OrderService orderService;
  private final MarketDataClientManager clientManager;

  @GetMapping("/board/{symbol}")
  public ResponseEntity<?> getMarketBoard(
      @PathVariable String symbol, @RequestParam(defaultValue = "10") int depth) {

    long startTime = System.currentTimeMillis();
    String normalizedSymbol = symbol != null ? symbol.toUpperCase() : "NULL";
    
    try {
      // 入力バリデーション
      if (symbol == null || symbol.trim().isEmpty()) {
        log.warn("❌ MarketBoard API - Invalid symbol parameter");
        return ResponseEntity.badRequest().body("Symbol is required");
      }

      if (depth <= 0 || depth > 100) {
        log.warn("❌ MarketBoard API - Invalid depth parameter: {}", depth);
        return ResponseEntity.badRequest().body("Depth must be between 1 and 100");
      }

      log.info("📊 MarketBoard API Request - symbol: {}, depth: {}", normalizedSymbol, depth);

      // 板情報を取得
      MarketBoardResponse response = orderService.getMarketBoard(normalizedSymbol, depth);

      // レスポンス統計情報を収集
      int bidCount = response.getBids() != null ? response.getBids().size() : 0;
      int askCount = response.getAsks() != null ? response.getAsks().size() : 0;
      long processingTime = System.currentTimeMillis() - startTime;

      // 詳細ログ出力
      log.info("✅ MarketBoard API Response - symbol: {}, depth: {}, bidLevels: {}, askLevels: {}, processingTime: {}ms", 
               normalizedSymbol, depth, bidCount, askCount, processingTime);

      // ベストBid/Askの詳細ログ
      if (!response.getBids().isEmpty()) {
        var bestBid = response.getBids().get(0);
        log.info("📈 {} Best BID - price: {}, quantity: {}", normalizedSymbol, bestBid.getPrice(), bestBid.getQuantity());
      } else {
        log.info("📈 {} Best BID - none available", normalizedSymbol);
      }

      if (!response.getAsks().isEmpty()) {
        var bestAsk = response.getAsks().get(0);
        log.info("📉 {} Best ASK - price: {}, quantity: {}", normalizedSymbol, bestAsk.getPrice(), bestAsk.getQuantity());
      } else {
        log.info("📉 {} Best ASK - none available", normalizedSymbol);
      }

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ MarketBoard API Error - symbol: {}, depth: {}, processingTime: {}ms, error: {}", 
                normalizedSymbol, depth, processingTime, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting market board: " + e.getMessage());
    }
  }

  @GetMapping("/board/{symbol}/simple")
  public ResponseEntity<?> getMarketBoardSimple(@PathVariable String symbol) {
    long startTime = System.currentTimeMillis();
    String normalizedSymbol = symbol != null ? symbol.toUpperCase() : "NULL";
    
    try {
      log.info("📊 MarketBoard Simple API Request - symbol: {}", normalizedSymbol);
      
      // デフォルトで深度5の板情報を取得
      ResponseEntity<?> response = getMarketBoard(symbol, 5);
      
      long processingTime = System.currentTimeMillis() - startTime;
      log.info("✅ MarketBoard Simple API Response - symbol: {}, processingTime: {}ms", 
               normalizedSymbol, processingTime);
               
      return response;
    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ MarketBoard Simple API Error - symbol: {}, processingTime: {}ms, error: {}", 
                normalizedSymbol, processingTime, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting market board: " + e.getMessage());
    }
  }

  @GetMapping("/connection-status")
  public ResponseEntity<Map<String, Object>> getConnectionStatus() {
    long startTime = System.currentTimeMillis();
    
    try {
      log.info("📡 Connection Status API Request");
      
      Map<String, Object> status = new HashMap<>();
      status.put("timestamp", java.time.Instant.now().toString());
      status.put("clients", clientManager.getAllClientsStatus());
      status.put("summary", createStatusSummary());
      
      long processingTime = System.currentTimeMillis() - startTime;
      status.put("processingTimeMs", processingTime);
      
      log.info("✅ Connection Status API Response - processingTime: {}ms", processingTime);
      
      return ResponseEntity.ok(status);
      
    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ Connection Status API Error - processingTime: {}ms, error: {}", 
                processingTime, e.getMessage(), e);
      
      Map<String, Object> errorResponse = new HashMap<>();
      errorResponse.put("error", "Failed to get connection status: " + e.getMessage());
      errorResponse.put("timestamp", java.time.Instant.now().toString());
      errorResponse.put("processingTimeMs", processingTime);
      
      return ResponseEntity.internalServerError().body(errorResponse);
    }
  }

  private Map<String, Object> createStatusSummary() {
    Map<String, Object> summary = new HashMap<>();
    
    // Use a simple count by iterating through all clients
    int totalClients = 0;
    int connectedCount = 0;
    
    // Manually count by checking client status
    String statusString = clientManager.getAllClientsStatus();
    String[] lines = statusString.split("\n");
    for (String line : lines) {
      if (line.contains("WebSocket Client")) {
        totalClients++;
        if (line.contains("Connected: true")) {
          connectedCount++;
        }
      }
    }
    int disconnectedClients = totalClients - connectedCount;
    
    summary.put("totalClients", totalClients);
    summary.put("connectedClients", connectedCount);
    summary.put("disconnectedClients", disconnectedClients);
    summary.put("connectionRate", totalClients > 0 ? 
        String.format("%.1f%%", (connectedCount * 100.0 / totalClients)) : "0.0%");
    
    return summary;
  }
}
