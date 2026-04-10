package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionHistoryResponse;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
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

  public ExecutionPollingController(
      ExecutionQueueService executionQueueService, UserDetailsService userDetailsService) {
    this.executionQueueService = executionQueueService;
    this.userDetailsService = userDetailsService;
  }

  // Removed /poll endpoint - use /history instead for multi-client safety
  // Removed /queue-size endpoint - no longer needed without polling

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

  // Removed /debug endpoint - no longer needed

  @GetMapping("/history")
  public ResponseEntity<?> getExecutionHistory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String symbol,
      @RequestParam(defaultValue = "true") boolean filledOnly) {

    long startTime = System.currentTimeMillis();
    String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);

    try {
      log.info(
          "📊 ExecutionHistory API Request [{}] - page: {}, size: {}, symbol: {}, filledOnly: {}",
          requestId,
          page,
          size,
          symbol,
          filledOnly);

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
              .map(
                  exec -> {
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
        executionDtos =
            executionDtos.stream()
                .filter(
                    dto ->
                        dto.getSymbol() != null
                            && dto.getSymbol().toUpperCase().contains(symbolFilter))
                .collect(Collectors.toList());

        // フィルタリング後の件数で応答を構成
        // ページネーションを再計算（フィルタリング後のデータに基づく）
        int filteredTotal = executionDtos.size();
        int filteredPages = (int) Math.ceil((double) filteredTotal / size);

        ExecutionHistoryResponse response =
            new ExecutionHistoryResponse(
                username, page, size, filteredPages, filteredTotal, executionDtos);

        long totalProcessingTime = System.currentTimeMillis() - startTime;
        log.info(
            "✅ ExecutionHistory API Response [{}] - user: {}, symbol: {}, beforeFilterSize: {}, "
                + "afterFilterSize: {}, totalElements: {}, responseSize: {}, processingTime: {}ms",
            requestId,
            username,
            symbolFilter,
            beforeFilterCount,
            filteredTotal,
            historyData.totalElements,
            executionDtos.size(),
            totalProcessingTime);

        return ResponseEntity.ok(response);
      }

      ExecutionHistoryResponse response =
          new ExecutionHistoryResponse(
              username,
              page,
              size,
              historyData.totalPages,
              historyData.totalElements,
              executionDtos);

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info(
          "✅ ExecutionHistory API Response [{}] - user: {}, responseSize: {}, totalElements: {}, "
              + "processingTime: {}ms",
          requestId,
          username,
          executionDtos.size(),
          historyData.totalElements,
          totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error(
          "❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: {}",
          requestId,
          processingTime,
          e.getMessage(),
          e);
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
      log.info(
          "📊 ExecutionHistory API Request [{}] - symbol: {}, page: {}, size: {}",
          requestId,
          symbol,
          page,
          size);

      // symbol パラメータの検証
      if (symbol == null || symbol.trim().isEmpty()) {
        long processingTime = System.currentTimeMillis() - startTime;
        log.warn(
            "❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: symbol parameter is"
                + " required",
            requestId,
            processingTime);
        return ResponseEntity.badRequest().body("Error: symbol parameter is required");
      }

      String normalizedSymbol = symbol.trim().toUpperCase();

      // メモリキャッシュから指定銘柄の約定履歴を取得
      ExecutionQueueService.ExecutionHistoryData historyData =
          executionQueueService.getExecutionsBySymbol(normalizedSymbol, page, size);

      // ExecutionをDTOに変換
      List<ExecutionHistoryResponse.ExecutionHistoryDto> executionDtos =
          historyData.executions.stream()
              .map(
                  exec -> {
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

      ExecutionHistoryResponse response =
          new ExecutionHistoryResponse(
              normalizedSymbol,
              page,
              size,
              historyData.totalPages,
              historyData.totalElements,
              executionDtos);

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info(
          "✅ ExecutionHistory API Response [{}] - symbol: {}, responseSize: {}, totalElements: {}, "
              + "processingTime: {}ms",
          requestId,
          normalizedSymbol,
          executionDtos.size(),
          historyData.totalElements,
          totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error(
          "❌ ExecutionHistory API Error [{}] - processingTime: {}ms, error: {}",
          requestId,
          processingTime,
          e.getMessage(),
          e);
      return ResponseEntity.internalServerError()
          .body("Error getting execution history: " + e.getMessage());
    }
  }

  /**
   * 指定注文ID（ClOrdID）に紐づく約定一覧を取得。 注文キャンセルが「Order not found」で失敗した場合に、その注文が約定済みか・約定内容を確認する用途。 GET
   * /api/executions/by-order?clOrdID=xxx
   */
  @GetMapping("/by-order")
  public ResponseEntity<?> getExecutionsByOrder(@RequestParam(name = "clOrdID") String clOrdID) {

    String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);

    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(401).body("Authentication required");
      }
      String username = authentication.getName();

      if (clOrdID == null || clOrdID.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("clOrdID is required");
      }

      List<Execution> executions =
          executionQueueService.getExecutionsByOrderID(username, clOrdID.trim());

      List<ExecutionHistoryResponse.ExecutionHistoryDto> dtos =
          executions.stream()
              .map(
                  exec -> {
                    try {
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

      ExecutionHistoryResponse response =
          new ExecutionHistoryResponse(username, 0, dtos.size(), 1, dtos.size(), dtos);

      log.info(
          "✅ ExecutionsByOrder [{}] - user: {}, clOrdID: {}, count: {}",
          requestId,
          username,
          clOrdID,
          dtos.size());
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("❌ ExecutionsByOrder [{}] - error: {}", requestId, e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body("Error getting executions by order: " + e.getMessage());
    }
  }

  // Removed /db-info endpoint - no longer needed

}
