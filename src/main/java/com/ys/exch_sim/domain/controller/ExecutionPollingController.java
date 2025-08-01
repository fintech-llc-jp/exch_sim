package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionHistoryResponse;
import com.ys.exch_sim.domain.dto.ExecutionPollingResponse;
import com.ys.exch_sim.domain.dto.VolumeCalculationResponse;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.domain.service.BigQueryVolumeCalculationService;
import org.springframework.security.core.userdetails.UserDetailsService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
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
public class ExecutionPollingController {

  private final ExecutionQueueService executionQueueService;
  private final UserDetailsService userDetailsService;
  private final ExecutionRepository executionRepository;
  private final BigQueryVolumeCalculationService volumeCalculationService;
  
  public ExecutionPollingController(ExecutionQueueService executionQueueService, 
                                   UserDetailsService userDetailsService,
                                   ExecutionRepository executionRepository,
                                   @Autowired(required = false) BigQueryVolumeCalculationService volumeCalculationService) {
    this.executionQueueService = executionQueueService;
    this.userDetailsService = userDetailsService;
    this.executionRepository = executionRepository;
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
      
      // 基本統計
      long totalCount = executionRepository.count();
      List<Execution> allExecutions = executionRepository.findAll();
      List<Execution> userExecutions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(username);
      
      // 新しいフィルタでの統計
      Page<Execution> filledExecutions = executionRepository.findFilledExecutionsByUsernameOrderByCreatedAtDesc(
          username, ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, PageRequest.of(0, 10));
      
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
      log.info("📊 ExecutionHistory [{}] - Processing for user: {}", requestId, username);

      // ページネーション設定
      Pageable pageable = PageRequest.of(page, size);
      log.info("📊 ExecutionHistory [{}] - Pagination: page={}, size={}", requestId, page, size);

      // 実際のページネーション処理
      Page<Execution> executionPage;
      long queryStart = System.currentTimeMillis();
      try {
        if (filledOnly) {
          // FILLED/PARTIAL_FILLのみを取得
          if (symbol != null && !symbol.trim().isEmpty()) {
            log.info("🔍 ExecutionHistory [{}] - Querying FILLED executions with symbol: {}", requestId, symbol.toUpperCase());
            executionPage = executionRepository.findFilledExecutionsByUsernameAndSymbolOrderByCreatedAtDesc(
                username, symbol.toUpperCase(), ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, pageable);
          } else {
            log.info("🔍 ExecutionHistory [{}] - Querying FILLED executions (all symbols)", requestId);
            executionPage = executionRepository.findFilledExecutionsByUsernameOrderByCreatedAtDesc(
                username, ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, pageable);
          }
        } else {
          // 全ステータスを取得（デバッグ用）
          if (symbol != null && !symbol.trim().isEmpty()) {
            log.info("🔍 ExecutionHistory [{}] - Querying ALL executions with symbol: {}", requestId, symbol.toUpperCase());
            executionPage = executionRepository.findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(
                username, symbol.toUpperCase(), pageable);
          } else {
            log.info("🔍 ExecutionHistory [{}] - Querying ALL executions (all symbols)", requestId);
            executionPage = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(
                username, pageable);
          }
        }
        
        long queryTime = System.currentTimeMillis() - queryStart;
        log.info("📊 ExecutionHistory [{}] - Database query completed - totalRecords: {}, pageRecords: {}, queryTime: {}ms", 
                 requestId, executionPage.getTotalElements(), executionPage.getContent().size(), queryTime);
        
      } catch (Exception e) {
        long processingTime = System.currentTimeMillis() - startTime;
        log.error("❌ ExecutionHistory [{}] - Database query error - processingTime: {}ms, error: {}", 
                  requestId, processingTime, e.getMessage(), e);
        return ResponseEntity.internalServerError()
            .body("Database error: " + e.getMessage());
      }

      // レスポンス用DTOに変換
      long conversionStart = System.currentTimeMillis();
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
                  log.error("❌ ExecutionHistory [{}] - Error converting execution to DTO: {}", requestId, exec, e);
                  return null;
                }
              })
              .filter(dto -> dto != null)
              .collect(Collectors.toList());

      long conversionTime = System.currentTimeMillis() - conversionStart;
      log.info("📊 ExecutionHistory [{}] - DTO conversion completed - records: {}, conversionTime: {}ms", 
               requestId, executionDtos.size(), conversionTime);

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          username,
          page,
          size,
          executionPage.getTotalPages(),
          executionPage.getTotalElements(),
          executionDtos
      );

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info("✅ ExecutionHistory API Response [{}] - user: {}, page: {}, size: {}, totalRecords: {}, returnedRecords: {}, totalPages: {}, processingTime: {}ms", 
               requestId, username, page, size, executionPage.getTotalElements(), executionDtos.size(), 
               executionPage.getTotalPages(), totalProcessingTime);
      
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
      
      // ページネーション設定
      Pageable pageable = PageRequest.of(page, size);

      // 全ユーザーの約定を取得
      Page<Execution> executionPage;
      long queryStart = System.currentTimeMillis();
      try {
        if (symbol != null && !symbol.trim().isEmpty()) {
          log.info("🔍 GlobalExecutionHistory [{}] - Querying with symbol filter: {}", requestId, symbol.toUpperCase());
          executionPage = executionRepository.findAllFilledExecutionsBySymbolOrderByCreatedAtDesc(
              symbol.toUpperCase(), ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, pageable);
        } else {
          log.info("🔍 GlobalExecutionHistory [{}] - Querying all symbols", requestId);
          executionPage = executionRepository.findAllFilledExecutionsOrderByCreatedAtDesc(ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, pageable);
        }
        
        long queryTime = System.currentTimeMillis() - queryStart;
        log.info("📊 GlobalExecutionHistory [{}] - Database query completed - totalRecords: {}, pageRecords: {}, queryTime: {}ms", 
                 requestId, executionPage.getTotalElements(), executionPage.getContent().size(), queryTime);
        
      } catch (Exception e) {
        long processingTime = System.currentTimeMillis() - startTime;
        log.error("❌ GlobalExecutionHistory [{}] - Database query error - processingTime: {}ms, error: {}", 
                  requestId, processingTime, e.getMessage(), e);
        return ResponseEntity.internalServerError()
            .body("Database error: " + e.getMessage());
      }

      // レスポンス用DTOに変換
      long conversionStart = System.currentTimeMillis();
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
                  log.error("❌ GlobalExecutionHistory [{}] - Error converting execution to DTO: {}", requestId, exec, e);
                  return null;
                }
              })
              .filter(dto -> dto != null)
              .collect(Collectors.toList());

      long conversionTime = System.currentTimeMillis() - conversionStart;
      log.info("📊 GlobalExecutionHistory [{}] - DTO conversion completed - records: {}, conversionTime: {}ms", 
               requestId, executionDtos.size(), conversionTime);

      ExecutionHistoryResponse response = new ExecutionHistoryResponse(
          "ALL_USERS", // グローバル約定なので特別な値
          page,
          size,
          executionPage.getTotalPages(),
          executionPage.getTotalElements(),
          executionDtos
      );

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info("✅ GlobalExecutionHistory API Response [{}] - page: {}, size: {}, totalRecords: {}, returnedRecords: {}, totalPages: {}, processingTime: {}ms", 
               requestId, page, size, executionPage.getTotalElements(), executionDtos.size(), 
               executionPage.getTotalPages(), totalProcessingTime);
      
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
        log.warn("BigQuery volume calculation service is not available, falling back to H2");
        return calculateVolumeFromH2(symbol, fromTime, toTime);
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
  
  /**
   * H2データベースを使用したフォールバック取引量計算
   */
  private ResponseEntity<?> calculateVolumeFromH2(String symbol, String fromTime, String toTime) {
    try {
      // Parse time parameters (assuming UTC)
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
      LocalDateTime fromDateTime;
      LocalDateTime toDateTime;
      
      try {
        fromDateTime = LocalDateTime.parse(fromTime, formatter);
        toDateTime = LocalDateTime.parse(toTime, formatter);
      } catch (DateTimeParseException e) {
        log.error("Invalid time format. Expected format: yyyy-MM-ddTHH:mm:ss (UTC)", e);
        return ResponseEntity.badRequest()
            .body("Invalid time format. Expected format: yyyy-MM-ddTHH:mm:ss (UTC)");
      }

      Long volumeRaw;
      Long executionCount;
      
      if (symbol.equalsIgnoreCase("ALL")) {
        volumeRaw = executionRepository.calculateTotalVolumeByTimeRange(fromDateTime, toDateTime);
        executionCount = executionRepository.countTotalExecutionsByTimeRange(fromDateTime, toDateTime);
      } else {
        volumeRaw = executionRepository.calculateVolumeBySymbolAndTimeRange(symbol.toUpperCase(), fromDateTime, toDateTime);
        executionCount = executionRepository.countExecutionsBySymbolAndTimeRange(symbol.toUpperCase(), fromDateTime, toDateTime);
      }
      
      Double totalVolume = volumeRaw != null ? volumeRaw.doubleValue() / 1000.0 : 0.0;
      
      String timeRangeDescription = String.format("From %s to %s (H2 fallback)", 
          fromDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
          toDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

      VolumeCalculationResponse response = new VolumeCalculationResponse(
          symbol.toUpperCase(),
          fromDateTime,
          toDateTime,
          totalVolume,
          executionCount,
          timeRangeDescription
      );

      return ResponseEntity.ok(response);
      
    } catch (Exception e) {
      log.error("H2 volume calculation failed", e);
      return ResponseEntity.internalServerError()
          .body("H2 volume calculation failed: " + e.getMessage());
    }
  }

  @GetMapping("/db-info")
  public ResponseEntity<?> getDatabaseInfo() {
    try {
      log.info("🔍 Database info request");
      
      // Basic statistics
      long totalExecutions = executionRepository.count();
      
      // Get recent executions with their creation times
      List<Execution> recentExecutions = executionRepository.findAll()
          .stream()
          .sorted((e1, e2) -> e2.getCreatedAt().compareTo(e1.getCreatedAt()))
          .limit(10)
          .toList();
      
      // Time zone analysis
      String systemTimeZone = ZoneId.systemDefault().toString();
      LocalDateTime systemTime = LocalDateTime.now();
      LocalDateTime utcTime = LocalDateTime.now(ZoneOffset.UTC);
      
      // Sample data analysis
      Map<String, Object> sampleData = recentExecutions.stream()
          .limit(5)
          .collect(Collectors.toMap(
              e -> e.getExecID().getId(),
              e -> Map.of(
                  "createdAt", e.getCreatedAt().toString(),
                  "symbol", e.getSymbol(),
                  "username", e.getUsername(),
                  "execStatus", e.getExecStatus().toString()
              )
          ));
      
      Map<String, Object> dbInfo = Map.of(
          "totalExecutions", totalExecutions,
          "systemTimeZone", systemTimeZone,
          "currentSystemTime", systemTime.toString(),
          "currentUtcTime", utcTime.toString(),
          "timeDifferenceHours", java.time.Duration.between(utcTime, systemTime).toHours(),
          "recentExecutionsCount", recentExecutions.size(),
          "oldestRecentExecution", recentExecutions.isEmpty() ? null : recentExecutions.get(recentExecutions.size()-1).getCreatedAt().toString(),
          "newestRecentExecution", recentExecutions.isEmpty() ? null : recentExecutions.get(0).getCreatedAt().toString(),
          "sampleExecutions", sampleData
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
      
      // H2データベースの統計情報
      try {
        long totalExecutions = executionRepository.count();
        debugInfo.put("h2TotalExecutions", totalExecutions);
        
        // 最近の約定10件を取得
        List<Execution> recentExecutions = executionRepository.findAll()
            .stream()
            .sorted((e1, e2) -> e2.getCreatedAt().compareTo(e1.getCreatedAt()))
            .limit(10)
            .collect(java.util.stream.Collectors.toList());
            
        List<Map<String, Object>> recentExecutionInfo = recentExecutions.stream()
            .map(e -> {
                Map<String, Object> execInfo = new java.util.HashMap<>();
                execInfo.put("symbol", e.getSymbol());
                execInfo.put("qty", e.getLastQtyRaw() != null ? e.getLastQtyRaw() : "null");
                execInfo.put("createdAt", e.getCreatedAt().toString());
                execInfo.put("isMarketMaker", e.getIsMarketMaker() != null ? e.getIsMarketMaker() : "null");
                return execInfo;
            })
            .collect(java.util.stream.Collectors.toList());
            
        debugInfo.put("recentExecutions", recentExecutionInfo);
        
      } catch (Exception e) {
        debugInfo.put("h2Error", e.getMessage());
      }
      
      return ResponseEntity.ok(debugInfo);
      
    } catch (Exception e) {
      log.error("Error getting volume debug info", e);
      return ResponseEntity.internalServerError()
          .body("Error getting volume debug info: " + e.getMessage());
    }
  }
}
