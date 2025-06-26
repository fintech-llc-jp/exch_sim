package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionHistoryResponse;
import com.ys.exch_sim.domain.dto.ExecutionPollingResponse;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.security.service.CustomUserDetailsService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
@RequiredArgsConstructor
public class ExecutionPollingController {

  private final ExecutionQueueService executionQueueService;
  private final CustomUserDetailsService userDetailsService;
  private final ExecutionRepository executionRepository;

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
    // Assuming default multiplier of 100 for stored data
    return rawPx.doubleValue() / 100.0;
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
      
      // 基本統計
      long totalCount = executionRepository.count();
      List<Execution> allExecutions = executionRepository.findAll();
      List<Execution> userExecutions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(username);
      
      // 新しいフィルタでの統計
      Page<Execution> filledExecutions = executionRepository.findFilledExecutionsByUsernameOrderByCreatedAtDesc(
          username, PageRequest.of(0, 10));
      
      // 全ユーザーのFILLED/PARTIAL_FILL統計
      List<Execution> allFilledExecutions = executionRepository.findAll().stream()
          .filter(e -> e.getExecStatus().toString().equals("FILLED") || e.getExecStatus().toString().equals("PARTIAL_FILL"))
          .toList();
          
      // ユーザー別の統計
      Map<String, Long> statusCounts = userExecutions.stream()
          .collect(java.util.stream.Collectors.groupingBy(
              e -> e.getExecStatus().toString(), 
              java.util.stream.Collectors.counting()));
      
      Map<String, Object> debug = Map.of(
        "totalExecutionsInDb", totalCount,
        "allExecutionsSize", allExecutions.size(),
        "userExecutionsSize", userExecutions.size(),
        "filledExecutionsForUser", filledExecutions.getTotalElements(),
        "allFilledExecutionsInDb", allFilledExecutions.size(),
        "userExecutionStatusCounts", statusCounts,
        "username", username,
        "sampleFilledExecution", filledExecutions.getContent().isEmpty() ? null : Map.of(
          "id", filledExecutions.getContent().get(0).getOrderID(),
          "username", filledExecutions.getContent().get(0).getUsername(),
          "symbol", filledExecutions.getContent().get(0).getSymbol(),
          "status", filledExecutions.getContent().get(0).getExecStatus().toString(),
          "isMarketMaker", filledExecutions.getContent().get(0).getIsMarketMaker()
        )
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
    try {
      log.info("✅ CORRECT API: /history endpoint called - page: {}, size: {}, symbol: {}, filledOnly: {}", page, size, symbol, filledOnly);
      
      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for execution history");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      log.info("Getting execution history for user: {}", username);

      // ページネーション設定
      Pageable pageable = PageRequest.of(page, size);
      log.info("Created pageable: page={}, size={}", page, size);

      // 実際のページネーション処理
      Page<Execution> executionPage;
      try {
        if (filledOnly) {
          // FILLED/PARTIAL_FILLのみを取得
          if (symbol != null && !symbol.trim().isEmpty()) {
            log.info("Querying FILLED executions with symbol filter: {}", symbol.toUpperCase());
            executionPage = executionRepository.findFilledExecutionsByUsernameAndSymbolOrderByCreatedAtDesc(
                username, symbol.toUpperCase(), pageable);
          } else {
            log.info("Querying FILLED executions without symbol filter for user: {}", username);
            executionPage = executionRepository.findFilledExecutionsByUsernameOrderByCreatedAtDesc(
                username, pageable);
          }
        } else {
          // 全ステータスを取得（デバッグ用）
          if (symbol != null && !symbol.trim().isEmpty()) {
            log.info("Querying ALL executions with symbol filter: {}", symbol.toUpperCase());
            executionPage = executionRepository.findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(
                username, symbol.toUpperCase(), pageable);
          } else {
            log.info("Querying ALL executions without symbol filter for user: {}", username);
            executionPage = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(
                username, pageable);
          }
        }
        log.info("Found {} total executions for user: {}, page contains: {} executions", 
                 executionPage.getTotalElements(), username, executionPage.getContent().size());
        
        // 各executionの詳細をログ出力
        for (Execution exec : executionPage.getContent()) {
          log.info("Execution: id={}, username={}, symbol={}, status={}, isMarketMaker={}", 
                   exec.getOrderID(), exec.getUsername(), exec.getSymbol(), 
                   exec.getExecStatus(), exec.getIsMarketMaker());
        }
        
      } catch (Exception e) {
        log.error("Database query error for user: {}", username, e);
        return ResponseEntity.internalServerError()
            .body("Database error: " + e.getMessage());
      }

      // レスポンス用DTOに変換
      List<ExecutionHistoryResponse.ExecutionHistoryDto> executionDtos =
          executionPage.getContent().stream()
              .map(exec -> {
                try {
                  return new ExecutionHistoryResponse.ExecutionHistoryDto(
                      exec.getExecID().getId(),
                      exec.getOrderID(),
                      exec.getSymbol(),
                      exec.getExecStatus().toString(),
                      getPxValueFromRaw(exec.getLastPxRaw()),
                      getQtyValueFromRaw(exec.getLastQtyRaw()),
                      exec.getCounterPartyUsername(),
                      exec.getSide(),
                      exec.getCreatedAt()
                  );
                } catch (Exception e) {
                  log.error("Error converting execution to DTO: {}", exec, e);
                  return null;
                }
              })
              .filter(dto -> dto != null)
              .collect(Collectors.toList());

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          username,
          page,
          size,
          executionPage.getTotalPages(),
          executionPage.getTotalElements(),
          executionDtos
      );

      log.info("Successfully retrieved {} execution history records for user: {}", 
               executionDtos.size(), username);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting execution history", e);
      return ResponseEntity.internalServerError()
          .body("Error getting execution history: " + e.getMessage());
    }
  }
}
