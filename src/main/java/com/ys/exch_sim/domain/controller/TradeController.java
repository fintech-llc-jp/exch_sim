package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.dto.TradeInsertRequest;
import com.ys.exch_sim.domain.dto.TradeInsertResponse;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.*;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.service.BigQueryVolumeCalculationService;
import com.ys.exch_sim.domain.service.OrderService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/trade")
public class TradeController {

  private final OrderService orderService;
  private final ExecutionRepository executionRepository;
  private final InstrumentConfig instrumentConfig;
  private final BigQueryService bigQueryService;
  private final BigQueryVolumeCalculationService volumeCalculationService;

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  public TradeController(
      OrderService orderService,
      ExecutionRepository executionRepository,
      InstrumentConfig instrumentConfig,
      @Autowired(required = false) BigQueryService bigQueryService,
      @Autowired(required = false) BigQueryVolumeCalculationService volumeCalculationService) {
    this.orderService = orderService;
    this.executionRepository = executionRepository;
    this.instrumentConfig = instrumentConfig;
    this.bigQueryService = bigQueryService;
    this.volumeCalculationService = volumeCalculationService;
  }

  @PostMapping("/insert")
  public ResponseEntity<?> insertTrade(@RequestBody TradeInsertRequest request) {
    long startTime = System.currentTimeMillis();
    String requestId = UUID.randomUUID().toString().substring(0, 8);

    try {
      log.info(
          "🔄 TradeInsert API Request [{}] - symbol: {}, side: {}, price: {}, quantity: {}",
          requestId,
          request.symbol(),
          request.side(),
          request.price(),
          request.quantity());

      // Authentication
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(401).body("Authentication required");
      }
      String username = authentication.getName();

      // Validation
      if (request.symbol() == null
          || request.side() == null
          || request.price() == null
          || request.quantity() == null) {
        return ResponseEntity.badRequest().body("Missing required fields");
      }

      if (!request.side().equals("BUY") && !request.side().equals("SELL")) {
        return ResponseEntity.badRequest().body("Side must be 'BUY' or 'SELL'");
      }

      if (request.price() <= 0 || request.quantity() <= 0) {
        return ResponseEntity.badRequest().body("Price and quantity must be positive");
      }

      // Get symbol configuration
      if (!instrumentConfig.isValidSymbol(request.symbol())) {
        return ResponseEntity.badRequest().body("Invalid symbol: " + request.symbol());
      }

      InstrumentConfig.InstrumentDefinition instrumentDef =
          instrumentConfig.getInstrument(request.symbol());
      Symbol symbol =
          new Symbol(
              request.symbol().toUpperCase(),
              instrumentDef.getPriceMultiplier(),
              instrumentDef.getQtyMultiplier());

      // Get market board for the symbol using OrderService's method
      MarketBoard marketBoard = getMarketBoardFromOrderService(request.symbol());
      if (marketBoard == null) {
        return ResponseEntity.internalServerError()
            .body("Market board not found for symbol: " + request.symbol());
      }

      Side side = request.side().equals("BUY") ? Side.BUY : Side.SELL;

      // Check if there are matching orders on the board
      boolean hasMatchingOrders = checkForMatchingOrders(marketBoard, side, request.price());
      log.info("🔍 TradeInsert [{}] - Matching orders check: {}", requestId, hasMatchingOrders);

      ResponseEntity<?> response;
      if (hasMatchingOrders) {
        // Place order and let it match naturally
        log.info("📈 TradeInsert [{}] - Processing via order matching", requestId);
        response =
            placeOrder(
                symbol, side, request.price(), request.quantity(), username, requestId, startTime);
      } else {
        // Insert execution directly
        log.info("💾 TradeInsert [{}] - Processing via direct execution", requestId);
        response =
            insertExecutionDirectly(
                symbol, side, request.price(), request.quantity(), username, requestId, startTime);
      }

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info(
          "✅ TradeInsert API Response [{}] - totalProcessingTime: {}ms",
          requestId,
          totalProcessingTime);
      return response;

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error(
          "❌ TradeInsert API Error [{}] - processingTime: {}ms, error: {}",
          requestId,
          processingTime,
          e.getMessage(),
          e);
      return ResponseEntity.internalServerError()
          .body("Error processing trade insert: " + e.getMessage());
    }
  }

  private boolean checkForMatchingOrders(MarketBoard marketBoard, Side side, Double price) {
    try {
      log.info("Checking for matching orders - side: {}, price: {}", side, price);

      if (side == Side.BUY) {
        // For BUY orders, check if there are ASK orders at or below the price
        for (int i = 0; i < 10; i++) { // Check top 10 levels
          var ask = marketBoard.getAsk(i);
          if (ask.getLeft() == 0) break; // No more levels

          double askPrice =
              ask.getLeft().doubleValue(); // No conversion needed - already in same units
          log.info("Comparing BUY price {} with ASK price {}", price, askPrice);
          if (askPrice <= price) {
            log.info("Found matching ASK order at price: {} (BUY price: {})", askPrice, price);
            return true;
          }
        }
        log.info("No matching ASK orders found for BUY price: {}", price);
      } else {
        // For SELL orders, check if there are BID orders at or above the price
        for (int i = 0; i < 10; i++) { // Check top 10 levels
          var bid = marketBoard.getBid(i);
          if (bid.getLeft() == 0) break; // No more levels

          double bidPrice =
              bid.getLeft().doubleValue(); // No conversion needed - already in same units
          log.info("Comparing SELL price {} with BID price {}", price, bidPrice);
          if (bidPrice >= price) {
            log.info("Found matching BID order at price: {} (SELL price: {})", bidPrice, price);
            return true;
          }
        }
        log.info("No matching BID orders found for SELL price: {}", price);
      }
      return false;
    } catch (Exception e) {
      log.error("Error checking for matching orders", e);
      return false;
    }
  }

  private ResponseEntity<?> placeOrder(
      Symbol symbol,
      Side side,
      Double price,
      Double quantity,
      String username,
      String requestId,
      long startTime) {
    try {
      log.info(
          "🔄 TradeInsert [{}] - Placing order for matching - symbol: {}, side: {}, price: {},"
              + " quantity: {}",
          requestId,
          symbol.getName(),
          side,
          price,
          quantity);

      // Create order
      Order order =
          new Order(
              symbol,
              new Px(symbol, price),
              new Qty(symbol, quantity),
              side,
              new ClOrdID(UUID.randomUUID().toString()),
              new Timestamp(LocalDateTime.now(ZoneOffset.UTC)),
              OrdType.LIMIT,
              Tif.IOC, // Immediate or Cancel to avoid lingering orders
              username);

      // Process order through OrderService and ensure board updates are reflected
      long orderProcessingStart = System.currentTimeMillis();
      List<Execution> executions = processOrderThroughOrderService(order);
      long orderProcessingTime = System.currentTimeMillis() - orderProcessingStart;

      log.info(
          "📊 TradeInsert [{}] - Order processing completed - executions: {}, processingTime: {}ms",
          requestId,
          executions.size(),
          orderProcessingTime);

      List<TradeInsertResponse.ExecutionSummary> executionSummaries =
          executions.stream()
              .map(
                  e ->
                      new TradeInsertResponse.ExecutionSummary(
                          e.getExecID().getId(),
                          e.getExecStatus().toString(),
                          e.getLastPx() != null
                              ? (double) e.getLastPx().getLongPx() / symbol.getPxMultiplier()
                              : null,
                          e.getLastQty() != null
                              ? (double) e.getLastQty().getLongQty() / symbol.getQtyMultiplier()
                              : null))
              .collect(Collectors.toList());

      // ログ出力：約定詳細
      for (TradeInsertResponse.ExecutionSummary exec : executionSummaries) {
        log.info(
            "💰 TradeInsert [{}] - Execution: id={}, status={}, price={}, quantity={}",
            requestId,
            exec.execId(),
            exec.execStatus(),
            exec.price(),
            exec.quantity());
      }

      TradeInsertResponse response =
          new TradeInsertResponse(
              "ORDER_PLACED",
              symbol.getName(),
              side.toString(),
              price,
              quantity,
              "Order placed and matched against existing orders",
              executionSummaries);

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info(
          "✅ TradeInsert [{}] - Order placement completed - totalExecutions: {},"
              + " totalProcessingTime: {}ms",
          requestId,
          executionSummaries.size(),
          totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error(
          "❌ TradeInsert [{}] - Order placement error - processingTime: {}ms, error: {}",
          requestId,
          processingTime,
          e.getMessage(),
          e);
      return ResponseEntity.internalServerError().body("Error placing order: " + e.getMessage());
    }
  }

  private ResponseEntity<?> insertExecutionDirectly(
      Symbol symbol,
      Side side,
      Double price,
      Double quantity,
      String username,
      String requestId,
      long startTime) {
    try {
      log.info(
          "💾 TradeInsert [{}] - Inserting execution directly - symbol: {}, side: {}, price: {},"
              + " quantity: {}",
          requestId,
          symbol.getName(),
          side,
          price,
          quantity);

      // Create execution directly
      Execution execution =
          new Execution(
              UUID.randomUUID().toString(), // execID
              UUID.randomUUID().toString(), // orderID (fake)
              username,
              symbol.getName(),
              ExecStatus.FILLED,
              (long) (price * symbol.getPxMultiplier()), // Convert to internal price
              (long) (quantity * symbol.getQtyMultiplier()), // Convert to internal quantity
              "SYSTEM", // counterPartyUsername
              LocalDateTime.now(ZoneOffset.UTC),
              false, // isMarketMaker
              side.toString());

      // Save to database
      long dbSaveStart = System.currentTimeMillis();
      executionRepository.save(execution);
      long dbSaveTime = System.currentTimeMillis() - dbSaveStart;
      log.info(
          "💾 TradeInsert [{}] - Database save completed - saveTime: {}ms", requestId, dbSaveTime);

      // BigQueryにも非同期保存
      if (bigQueryEnabled && bigQueryService != null) {
        long bigQueryStart = System.currentTimeMillis();
        saveExecutionToBigQueryAsync(execution);
        long bigQueryTime = System.currentTimeMillis() - bigQueryStart;
        log.info(
            "☁️ TradeInsert [{}] - BigQuery async save initiated - initTime: {}ms",
            requestId,
            bigQueryTime);
      }

      // 取引量を更新（BigQueryが無効でもvolumeCalculationServiceが利用可能な場合は更新）
      if (volumeCalculationService != null) {
        long volumeUpdateStart = System.currentTimeMillis();
        volumeCalculationService.updateVolumeOnTrade(execution);
        long volumeUpdateTime = System.currentTimeMillis() - volumeUpdateStart;
        log.info(
            "📊 TradeInsert [{}] - Volume update completed - updateTime: {}ms",
            requestId,
            volumeUpdateTime);
      }

      TradeInsertResponse.ExecutionSummary executionSummary =
          new TradeInsertResponse.ExecutionSummary(
              execution.getExecID().getId(), execution.getExecStatus().toString(), price, quantity);

      log.info(
          "💰 TradeInsert [{}] - Direct execution created: id={}, status={}, price={}, quantity={}",
          requestId,
          execution.getExecID().getId(),
          execution.getExecStatus().toString(),
          price,
          quantity);

      TradeInsertResponse response =
          new TradeInsertResponse(
              "EXECUTION_INSERTED",
              symbol.getName(),
              side.toString(),
              price,
              quantity,
              "Execution inserted directly (no matching orders found)",
              List.of(executionSummary));

      long totalProcessingTime = System.currentTimeMillis() - startTime;
      log.info(
          "✅ TradeInsert [{}] - Direct execution completed - totalProcessingTime: {}ms",
          requestId,
          totalProcessingTime);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      long processingTime = System.currentTimeMillis() - startTime;
      log.error(
          "❌ TradeInsert [{}] - Direct execution error - processingTime: {}ms, error: {}",
          requestId,
          processingTime,
          e.getMessage(),
          e);
      return ResponseEntity.internalServerError()
          .body("Error inserting execution: " + e.getMessage());
    }
  }

  private MarketBoard getMarketBoardFromOrderService(String symbolName) {
    try {
      // Use OrderService's getMarketBoard method to ensure we get the same instance
      var response = orderService.getMarketBoard(symbolName, 1);

      // Now access the actual MarketBoard using reflection
      java.lang.reflect.Field field = OrderService.class.getDeclaredField("marketBoards");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      java.util.concurrent.ConcurrentHashMap<String, MarketBoard> marketBoards =
          (java.util.concurrent.ConcurrentHashMap<String, MarketBoard>) field.get(orderService);

      return marketBoards.get(symbolName.toUpperCase());
    } catch (Exception e) {
      log.error("Error accessing MarketBoard from OrderService", e);
      return getMarketBoardInternal(symbolName); // Fallback
    }
  }

  private MarketBoard getMarketBoardInternal(String symbolName) {
    try {
      // Use reflection to access private field from OrderService
      java.lang.reflect.Field field = OrderService.class.getDeclaredField("marketBoards");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      java.util.concurrent.ConcurrentHashMap<String, MarketBoard> marketBoards =
          (java.util.concurrent.ConcurrentHashMap<String, MarketBoard>) field.get(orderService);

      MarketBoard marketBoard = marketBoards.get(symbolName.toUpperCase());
      if (marketBoard == null) {
        // Create new MarketBoard if it doesn't exist
        InstrumentConfig.InstrumentDefinition instrumentDef =
            instrumentConfig.getInstrument(symbolName);
        Symbol symbol =
            new Symbol(
                symbolName.toUpperCase(),
                instrumentDef.getPriceMultiplier(),
                instrumentDef.getQtyMultiplier());
        marketBoard = new MarketBoard(symbol);
        marketBoards.put(symbolName.toUpperCase(), marketBoard);
      }
      return marketBoard;
    } catch (Exception e) {
      log.error("Error accessing MarketBoard", e);
      return null;
    }
  }

  private List<Execution> processOrderThroughOrderService(Order order) {
    try {
      log.info("Processing order through OrderService for proper board updates");

      // Create NewOrderRequest to use OrderService's public API
      NewOrderRequest orderRequest = new NewOrderRequest();
      orderRequest.setSymbol(order.getSymbol().getName());
      orderRequest.setPrice(
          (double) order.getOrderPx().getLongPx() / order.getSymbol().getPxMultiplier());
      orderRequest.setQuantity(
          (double) order.getOrderQty().getLongQty() / order.getSymbol().getQtyMultiplier());
      orderRequest.setSide(order.getSide().toString());
      orderRequest.setOrdType("MARKET");
      orderRequest.setTif("IOC");
      orderRequest.setIsMarketMake(false);

      // Use OrderService's public processNewOrder method
      log.info(
          "Calling OrderService.processNewOrder with: symbol={}, price={}, quantity={}, side={}",
          orderRequest.getSymbol(),
          orderRequest.getPrice(),
          orderRequest.getQuantity(),
          orderRequest.getSide());

      OrderResponse response = orderService.processNewOrder(order.getUsername(), orderRequest);

      log.info(
          "OrderService returned: status={}, executions={}",
          response.getStatus(),
          response.getExecutions() != null ? response.getExecutions().size() : 0);

      // The actual board updates happen inside OrderService.processNewOrder()
      // We just need to return the executions for the API response

      // Convert OrderResponse.ExecutionDto back to Execution objects
      List<Execution> executions = new ArrayList<>();
      if (response.getExecutions() != null) {
        for (OrderResponse.ExecutionDto execDto : response.getExecutions()) {
          // Create Execution from DTO (simplified approach)
          Execution execution =
              new Execution(
                  execDto.getExecID(),
                  response.getClOrdID(),
                  order.getUsername(),
                  order.getSymbol().getName(),
                  ExecStatus.valueOf(execDto.getExecStatus()),
                  execDto.getLastPx() != null
                      ? (long) (execDto.getLastPx() * order.getSymbol().getPxMultiplier())
                      : null,
                  execDto.getLastQty() != null
                      ? (long) (execDto.getLastQty() * order.getSymbol().getQtyMultiplier())
                      : null,
                  "SYSTEM",
                  LocalDateTime.now(ZoneOffset.UTC),
                  false,
                  order.getSide().toString());
          executions.add(execution);
        }
      }

      log.info("Converted {} executions from OrderResponse", executions.size());
      return executions;

    } catch (Exception e) {
      log.error("Error processing order through OrderService", e);
      // Don't fall back - this should work
      throw new RuntimeException("Failed to process order through OrderService", e);
    }
  }

  // BigQuery保存メソッド（同期版）
  private void saveExecutionToBigQuery(Execution execution) {
    try {
      BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(execution);
      bigQueryService.insertExecution(bigQueryEntity);
      log.debug("TradeInsert execution saved to BigQuery: {}", execution.getExecID());
    } catch (Exception e) {
      log.error("Error saving TradeInsert execution to BigQuery: " + execution.getExecID(), e);
    }
  }

  // BigQuery非同期保存メソッド
  private void saveExecutionToBigQueryAsync(Execution execution) {
    try {
      BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(execution);
      bigQueryService
          .insertExecutionAsync(bigQueryEntity)
          .thenRun(
              () ->
                  log.debug(
                      "TradeInsert execution saved to BigQuery (async): {}", execution.getExecID()))
          .exceptionally(
              throwable -> {
                log.error(
                    "Error saving TradeInsert execution to BigQuery (async): {}",
                    execution.getExecID(),
                    throwable);
                return null;
              });
    } catch (Exception e) {
      log.error(
          "Error preparing TradeInsert execution for BigQuery (async): {}",
          execution.getExecID(),
          e);
    }
  }
}
