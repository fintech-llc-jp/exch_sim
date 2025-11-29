package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionHistoryResponse;
import com.ys.exch_sim.domain.dto.ExecutionPollingResponse;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import org.springframework.security.core.userdetails.UserDetailsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/executions")
public class ExecutionPollingController {

  private final ExecutionQueueService executionQueueService;
  private final UserDetailsService userDetailsService;

  public ExecutionPollingController(ExecutionQueueService executionQueueService,
                                   UserDetailsService userDetailsService) {
    this.executionQueueService = executionQueueService;
    this.userDetailsService = userDetailsService;
  }

  @GetMapping("/poll")
  public ResponseEntity<?> pollExecutions(@RequestParam(defaultValue = "10") int maxCount) {
    try {
      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for execution polling");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      log.warn("⚠️  DEPRECATED: Frontend is still using /poll endpoint! User: {} with maxCount: {}", username, maxCount);
      log.warn("⚠️  PLEASE UPDATE FRONTEND TO USE /api/executions/history for execution history!");
      log.info("Polling executions for user: {} with maxCount: {}", username, maxCount);

      // ユーザー存在確認
      try {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (userDetails == null) {
          log.warn("User not found: {}", username);
          return ResponseEntity.status(404).body("User not found");
        }
      } catch (Exception e) {
        log.warn("Error validating user: {}", username, e);
        return ResponseEntity.status(404).body("User not found");
      }

      // 約定結果をポーリング
      List<Execution> executions = executionQueueService.pollExecutions(username, maxCount);

      // レスポンス用DTOに変換
      List<ExecutionPollingResponse.ExecutionDto> executionDtos =
          executions.stream()
              .map(
                  exec -> {
                    try {
                      // Try to get data from original order first (for non-persisted executions)
                      return new ExecutionPollingResponse.ExecutionDto(
                          exec.getExecID().getId(),
                          exec.getOrder().getClOrdID().getId(),
                          exec.getOrder().getSymbol().getName(),
                          exec.getExecStatus().toString(),
                          getPxValue(exec.getLastPx()),
                          getQtyValue(exec.getLastQty()),
                          exec.getCounterPartyUsername(),
                          exec.getOrder().getSide().toString());
                    } catch (UnsupportedOperationException e) {
                      // For persisted executions, use the stored data
                      return new ExecutionPollingResponse.ExecutionDto(
                          exec.getExecID().getId(),
                          exec.getOrderID(), // Use stored orderID
                          exec.getSymbol(),   // Use stored symbol
                          exec.getExecStatus().toString(),
                          getPxValueFromRaw(exec.getLastPxRaw()),
                          getQtyValueFromRaw(exec.getLastQtyRaw()),
                          exec.getCounterPartyUsername(),
                          determineSideFromExecution(exec)); // We need to determine side differently
                    }
                  })
              .collect(Collectors.toList());

      ExecutionPollingResponse response =
          new ExecutionPollingResponse(username, executionDtos.size(), executionDtos);

      log.info("Successfully polled {} executions for user: {}", executionDtos.size(), username);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error polling executions", e);
      return ResponseEntity.internalServerError()
          .body("Error polling executions: " + e.getMessage());
    }
  }

  @GetMapping("/queue-size")
  public ResponseEntity<?> getQueueSize() {
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      int queueSize = executionQueueService.getQueueSize(username);

      return ResponseEntity.ok(Map.of("username", username, "queueSize", queueSize));

    } catch (Exception e) {
      log.error("Error getting queue size", e);
      return ResponseEntity.internalServerError()
          .body("Error getting queue size: " + e.getMessage());
    }
  }

  private Double getPxValue(Px px) {
    return (double) px.getLongPx() / px.getSymbol().getPxMultiplier();
  }

  private Double getQtyValue(Qty qty) {
    return (double) qty.getLongQty() / qty.getSymbol().getQtyMultiplier();
  }

  private Double getPxValueFromRaw(Long rawPx) {
    if (rawPx == null) return null;
    // Price multiplier is 1 for all instruments in current configuration
    return rawPx.doubleValue();
  }

  private Double getQtyValueFromRaw(Long rawQty) {
    if (rawQty == null) return null;
    // For B_FX_BTCJPY, qtyMultiplier=1000, so we need to convert back
    // Assuming default multiplier of 1000 for stored quantity data
    return rawQty.doubleValue() / 1000.0;
  }

  private String determineSideFromExecution(Execution exec) {
    // Use the stored side from the execution entity
    return exec.getSide() != null ? exec.getSide() : "UNKNOWN";
  }

  @GetMapping("/debug")
  public ResponseEntity<?> debugExecutions() {
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();

      // ExecutionRepository removed - debug endpoint deprecated
      Map<String, Object> debug = Map.of(
        "status", "ExecutionRepository removed",
        "message", "Debug endpoint no longer available - H2 database removed",
        "username", username,
        "queueSize", executionQueueService.getQueueSize(username)
      );

      return ResponseEntity.ok(debug);
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body("Debug error: " + e.getMessage());
    }
  }

  @GetMapping("/history")
  public ResponseEntity<?> getExecutionHistory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String symbol,
      @RequestParam(defaultValue = "true") boolean filledOnly) {

    long startTime = System.currentTimeMillis();
    String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);

    try {
      log.info("📊 ExecutionHistory API Request [{}] - page: {}, size: {}, symbol: {}, filledOnly: {}",
               requestId, page, size, symbol, filledOnly);

      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for execution history");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();

      // メモリキャッシュから約定履歴を取得
      ExecutionQueueService.ExecutionHistoryData historyData =
          executionQueueService.getExecutionHistory(username, page, size);

      // ExecutionをDTOに変換
      List<ExecutionHistoryResponse.ExecutionHistoryDto> executionDtos =
          historyData.executions.stream()
              .map(exec -> {
                try {
                  // Non-persisted executionの場合
                  return new ExecutionHistoryResponse.ExecutionHistoryDto(
                      exec.getExecID().getId(),
                      exec.getOrder().getClOrdID().getId(),
                      exec.getOrder().getSymbol().getName(),
                      exec.getExecStatus().toString(),
                      getPxValue(exec.getLastPx()),
                      getQtyValue(exec.getLastQty()),
                      exec.getCounterPartyUsername(),
                      exec.getOrder().getSide().toString(),
                      exec.getCreatedAt() != null ? exec.getCreatedAt() : LocalDateTime.now());
                } catch (UnsupportedOperationException e) {
                  // Persisted executionの場合
                  return new ExecutionHistoryResponse.ExecutionHistoryDto(
                      exec.getExecID().getId(),
                      exec.getOrderID(),
                      exec.getSymbol(),
                      exec.getExecStatus().toString(),
                      getPxValueFromRaw(exec.getLastPxRaw()),
                      getQtyValueFromRaw(exec.getLastQtyRaw()),
                      exec.getCounterPartyUsername(),
                      determineSideFromExecution(exec),
                      exec.getCreatedAt() != null ? exec.getCreatedAt() : LocalDateTime.now());
                }
              })
              .collect(Collectors.toList());

      // symbol フィルタリング
      if (symbol != null && !symbol.trim().isEmpty()) {
        String symbolFilter = symbol.toUpperCase();
        int beforeFilterCount = executionDtos.size();
        executionDtos = executionDtos.stream()
            .filter(dto -> dto.getSymbol() != null && dto.getSymbol().toUpperCase().contains(symbolFilter))
            .collect(Collectors.toList());

        // フィルタリング後の件数で応答を構成
        // ページネーションを再計算（フィルタリング後のデータに基づく）
        int filteredTotal = executionDtos.size();
        int filteredPages = (int) Math.ceil((double) filteredTotal / size);

        ExecutionHistoryResponse response = new ExecutionHistoryResponse(
            username,
            page,
            size,
            filteredPages,
            filteredTotal,
            executionDtos
        );

        long totalProcessingTime = System.currentTimeMillis() - startTime;
        log.info("✅ ExecutionHistory API Response [{}] - user: {}, symbol: {}, beforeFilterSize: {}, " +
                "afterFilterSize: {}, totalElements: {}, responseSize: {}, processingTime: {}ms",
                 requestId, username, symbolFilter, beforeFilterCount, filteredTotal,
                 historyData.totalElements, executionDtos.size(), totalProcessingTime);

        return ResponseEntity.ok(response);
      }

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          username,
          page,
          size,
          historyData.totalPages,
          historyData.totalElements,
          executionDtos
      );

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info("✅ ExecutionHistory API Response [{}] - user: {}, responseSize: {}, totalElements: {}, " +
              "processingTime: {}ms",
               requestId, username, executionDtos.size(), historyData.totalElements, totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: {}",
                requestId, processingTime, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting execution history: " + e.getMessage());
    }
  }

  @GetMapping("/all")
  public ResponseEntity<?> getAllExecutionHistory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = true) String symbol) {

    long startTime = System.currentTimeMillis();
    String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);

    try {
      log.info("📊 ExecutionHistory API Request [{}] - symbol: {}, page: {}, size: {}",
               requestId, symbol, page, size);

      // symbol パラメータの検証
      if (symbol == null || symbol.trim().isEmpty()) {
        long processingTime = System.currentTimeMillis() - startTime;
        log.warn("❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: symbol parameter is required",
                 requestId, processingTime);
        return ResponseEntity.badRequest()
            .body("Error: symbol parameter is required");
      }

      String normalizedSymbol = symbol.trim().toUpperCase();

      // メモリキャッシュから指定銘柄の約定履歴を取得
      ExecutionQueueService.ExecutionHistoryData historyData =
          executionQueueService.getExecutionsBySymbol(normalizedSymbol, page, size);

      // ExecutionをDTOに変換
      List<ExecutionHistoryResponse.ExecutionHistoryDto> executionDtos =
          historyData.executions.stream()
              .map(exec -> {
                try {
                  // Non-persisted executionの場合
                  return new ExecutionHistoryResponse.ExecutionHistoryDto(
                      exec.getExecID().getId(),
                      exec.getOrder().getClOrdID().getId(),
                      exec.getOrder().getSymbol().getName(),
                      exec.getExecStatus().toString(),
                      getPxValue(exec.getLastPx()),
                      getQtyValue(exec.getLastQty()),
                      exec.getCounterPartyUsername(),
                      exec.getOrder().getSide().toString(),
                      exec.getCreatedAt() != null ? exec.getCreatedAt() : LocalDateTime.now());
                } catch (UnsupportedOperationException e) {
                  // Persisted executionの場合
                  return new ExecutionHistoryResponse.ExecutionHistoryDto(
                      exec.getExecID().getId(),
                      exec.getOrderID(),
                      exec.getSymbol(),
                      exec.getExecStatus().toString(),
                      getPxValueFromRaw(exec.getLastPxRaw()),
                      getQtyValueFromRaw(exec.getLastQtyRaw()),
                      exec.getCounterPartyUsername(),
                      determineSideFromExecution(exec),
                      exec.getCreatedAt() != null ? exec.getCreatedAt() : LocalDateTime.now());
                }
              })
              .collect(Collectors.toList());

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          normalizedSymbol,
          page,
          size,
          historyData.totalPages,
          historyData.totalElements,
          executionDtos
      );

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info("✅ ExecutionHistory API Response [{}] - symbol: {}, responseSize: {}, totalElements: {}, " +
              "processingTime: {}ms",
               requestId, normalizedSymbol, executionDtos.size(), historyData.totalElements, totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: {}",
                requestId, processingTime, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting execution history: " + e.getMessage());
    }
  }

  @GetMapping("/db-info")
  public ResponseEntity<?> getDatabaseInfo() {
    try {
      log.info("🔍 Database info request");

      // ExecutionRepository removed
      Map<String, Object> dbInfo = Map.of(
          "status", "ExecutionRepository removed",
          "message", "H2 database no longer available",
          "currentTime", LocalDateTime.now().toString()
      );

      return ResponseEntity.ok(dbInfo);

    } catch (Exception e) {
      log.error("Error getting database info", e);
      return ResponseEntity.internalServerError()
          .body("Error getting database info: " + e.getMessage());
    }
  }

}
