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
    try {
      log.info(
          "🔄 Trade insert request - symbol: {}, side: {}, price: {}, quantity: {}",
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

      if (hasMatchingOrders) {
        // Place order and let it match naturally
        return placeOrder(symbol, side, request.price(), request.quantity(), username);
      } else {
        // Insert execution directly
        return insertExecutionDirectly(symbol, side, request.price(), request.quantity(), username);
      }

    } catch (Exception e) {
      log.error("Error processing trade insert", e);
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
      Symbol symbol, Side side, Double price, Double quantity, String username) {
    try {
      log.info(
          "Placing order for matching - symbol: {}, side: {}, price: {}, quantity: {}",
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
      List<Execution> executions = processOrderThroughOrderService(order);

      // Force board refresh to ensure consistency
      log.info("Forcing board refresh after order processing");

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

      TradeInsertResponse response =
          new TradeInsertResponse(
              "ORDER_PLACED",
              symbol.getName(),
              side.toString(),
              price,
              quantity,
              "Order placed and matched against existing orders",
              executionSummaries);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error placing order", e);
      return ResponseEntity.internalServerError().body("Error placing order: " + e.getMessage());
    }
  }

  private ResponseEntity<?> insertExecutionDirectly(
      Symbol symbol, Side side, Double price, Double quantity, String username) {
    try {
      log.info(
          "Inserting execution directly - symbol: {}, side: {}, price: {}, quantity: {}",
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
      executionRepository.save(execution);

      // BigQueryにも非同期保存
      if (bigQueryEnabled && bigQueryService != null) {
        saveExecutionToBigQueryAsync(execution);
      }

      // 取引量を更新（BigQueryが無効でもvolumeCalculationServiceが利用可能な場合は更新）
      if (volumeCalculationService != null) {
        volumeCalculationService.updateVolumeOnTrade(execution);
      }

      TradeInsertResponse.ExecutionSummary executionSummary =
          new TradeInsertResponse.ExecutionSummary(
              execution.getExecID().getId(), execution.getExecStatus().toString(), price, quantity);

      TradeInsertResponse response =
          new TradeInsertResponse(
              "EXECUTION_INSERTED",
              symbol.getName(),
              side.toString(),
              price,
              quantity,
              "Execution inserted directly (no matching orders found)",
              List.of(executionSummary));

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error inserting execution directly", e);
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
