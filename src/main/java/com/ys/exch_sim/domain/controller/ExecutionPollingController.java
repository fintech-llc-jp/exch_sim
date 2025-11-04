package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionHistoryResponse;
import com.ys.exch_sim.domain.dto.ExecutionPollingResponse;
import com.ys.exch_sim.domain.dto.VolumeCalculationResponse;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.domain.service.BigQueryVolumeCalculationService;
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
  private final BigQueryVolumeCalculationService volumeCalculationService;

  public ExecutionPollingController(ExecutionQueueService executionQueueService,
                                   UserDetailsService userDetailsService,
                                   @Autowired(required = false) BigQueryVolumeCalculationService volumeCalculationService) {
    this.executionQueueService = executionQueueService;
    this.userDetailsService = userDetailsService;
    this.volumeCalculationService = volumeCalculationService;
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
                      LocalDateTime.now());
                }
              })
              .collect(Collectors.toList());

      // symbol フィルタリング
      if (symbol != null && !symbol.trim().isEmpty()) {
        String symbolFilter = symbol.toUpperCase();
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
        log.info("✅ ExecutionHistory API Response [{}] - Retrieved {} executions (filtered by {}), totalElements: {}, processingTime: {}ms",
                 requestId, executionDtos.size(), symbol, filteredTotal, totalProcessingTime);

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
      log.info("✅ ExecutionHistory API Response [{}] - Retrieved {} executions, totalElements: {}, processingTime: {}ms",
               requestId, executionDtos.size(), historyData.totalElements, totalProcessingTime);

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
      @RequestParam(required = false) String symbol) {

    long startTime = System.currentTimeMillis();
    String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);

    try {
      log.info("📊 GlobalExecutionHistory API Request [{}] - page: {}, size: {}, symbol: {}",
               requestId, page, size, symbol);

      // メモリキャッシュから全ユーザーの約定履歴を取得
      ExecutionQueueService.ExecutionHistoryData historyData =
          executionQueueService.getAllExecutionHistory(page, size);

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
                      LocalDateTime.now());
                }
              })
              .collect(Collectors.toList());

      // symbol フィルタリング
      if (symbol != null && !symbol.trim().isEmpty()) {
        String symbolFilter = symbol.toUpperCase();
        executionDtos = executionDtos.stream()
            .filter(dto -> dto.getSymbol() != null && dto.getSymbol().toUpperCase().contains(symbolFilter))
            .collect(Collectors.toList());

        // フィルタリング後の件数で応答を構成
        // ページネーションを再計算（フィルタリング後のデータに基づく）
        int filteredTotal = executionDtos.size();
        int filteredPages = (int) Math.ceil((double) filteredTotal / size);

        ExecutionHistoryResponse response = new ExecutionHistoryResponse(
            "ALL_USERS",
            page,
            size,
            filteredPages,
            filteredTotal,
            executionDtos
        );

        long totalProcessingTime = System.currentTimeMillis() - startTime;
        log.info("✅ GlobalExecutionHistory API Response [{}] - Retrieved {} executions (filtered by {}), totalElements: {}, processingTime: {}ms",
                 requestId, executionDtos.size(), symbol, filteredTotal, totalProcessingTime);

        return ResponseEntity.ok(response);
      }

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          "ALL_USERS",
          page,
          size,
          historyData.totalPages,
          historyData.totalElements,
          executionDtos
      );

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info("✅ GlobalExecutionHistory API Response [{}] - Retrieved {} executions, totalElements: {}, processingTime: {}ms",
               requestId, executionDtos.size(), historyData.totalElements, totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error("❌ GlobalExecutionHistory API Error [{}] - processingTime: {}ms, error: {}",
                requestId, processingTime, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting global execution history: " + e.getMessage());
    }
  }

  @GetMapping("/volume")
  public ResponseEntity<?> calculateVolume(
      @RequestParam String symbol,
      @RequestParam String fromTime,
      @RequestParam String toTime) {
    try {
      log.info("📊 Volume calculation request - symbol: {}, fromTime: {}, toTime: {}", symbol, fromTime, toTime);

      // BigQueryVolumeCalculationServiceが利用可能かチェック
      if (volumeCalculationService == null) {
        log.warn("BigQuery volume calculation service is not available");
        return ResponseEntity.internalServerError()
            .body("Volume calculation service not available");
      }

      // デバッグ用：キャッシュの状態をログ出力
      Map<String, Object> volumeStatus = volumeCalculationService.getVolumeStatus();
      log.info("🔍 Volume cache status: {}", volumeStatus);

      // BigQueryベースの計算（現在は24時間固定）
      String normalizedSymbol = symbol != null ? symbol.toUpperCase() : null;
      log.info("🔍 Normalized symbol: '{}' (original: '{}')", normalizedSymbol, symbol);

      Long volumeRaw;
      if (symbol == null || symbol.trim().isEmpty() || "ALL".equalsIgnoreCase(symbol.trim())) {
        volumeRaw = volumeCalculationService.calculateTotalVolume();
        log.info("🔍 Calculating total volume: {}", volumeRaw);
      } else {
        volumeRaw = volumeCalculationService.calculateVolumeBySymbol(normalizedSymbol);
        log.info("🔍 Calculating volume for symbol '{}': {}", normalizedSymbol, volumeRaw);
      }

      // Convert raw volume to actual value (qtyMultiplier=1000)
      Double totalVolume = volumeRaw != null ? volumeRaw.doubleValue() / 1000.0 : 0.0;

      // 現在の実装では約定件数は取得しない（必要に応じて後で追加）
      Long executionCount = 0L;

      String timeRangeDescription = "24-hour rolling volume (BigQuery-based)";

      VolumeCalculationResponse response = new VolumeCalculationResponse(
          symbol != null && !symbol.trim().isEmpty() ? symbol.toUpperCase() : "ALL",
          LocalDateTime.now().minusHours(24),
          LocalDateTime.now(),
          totalVolume,
          executionCount,
          timeRangeDescription
      );

      log.info("Successfully calculated volume (BigQuery-based) for symbol: {}, total volume: {}",
               symbol, totalVolume);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error calculating volume", e);
      return ResponseEntity.internalServerError()
          .body("Error calculating volume: " + e.getMessage());
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

  @GetMapping("/volume/debug")
  public ResponseEntity<?> getVolumeDebugInfo() {
    try {
      log.info("🔍 Volume debug info request");

      Map<String, Object> debugInfo = new java.util.HashMap<>();

      if (volumeCalculationService != null) {
        Map<String, Object> volumeStatus = volumeCalculationService.getVolumeStatus();
        debugInfo.put("volumeCalculationService", "available");
        debugInfo.put("volumeStatus", volumeStatus);
        debugInfo.put("totalVolume", volumeCalculationService.calculateTotalVolume());
      } else {
        debugInfo.put("volumeCalculationService", "not available");
      }

      // H2 database removed
      debugInfo.put("h2Status", "removed");
      debugInfo.put("h2TotalExecutions", "N/A");

      return ResponseEntity.ok(debugInfo);

    } catch (Exception e) {
      log.error("Error getting volume debug info", e);
      return ResponseEntity.internalServerError()
          .body("Error getting volume debug info: " + e.getMessage());
    }
  }
}
