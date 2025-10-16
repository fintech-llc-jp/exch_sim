package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.infra.Pair;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MarketDataSyncService {

  private final OrderService orderService;
  private final InstrumentConfig instrumentConfig;
  private final Optional<BigQueryService> bigQueryService;

  // Symbol-specific locks to prevent race conditions during board updates
  private final java.util.concurrent.ConcurrentHashMap<String, Object> symbolLocks =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  public MarketDataSyncService(
      OrderService orderService,
      InstrumentConfig instrumentConfig,
      @Autowired(required = false) BigQueryService bigQueryService) {
    this.orderService = orderService;
    this.instrumentConfig = instrumentConfig;
    this.bigQueryService = Optional.ofNullable(bigQueryService);
  }

  // MarketBoard更新メソッド
  public void updateMarketBoard(ExternalMarketBoardData data) {
    try {
      String symbolName = data.symbol();

      if (!instrumentConfig.isValidSymbol(symbolName)) {
        log.warn("❌ MarketBoard Update - Invalid symbol received from External: {}", symbolName);
        return;
      }

      // Get or create a lock for this specific symbol
      Object lock = symbolLocks.computeIfAbsent(symbolName, k -> new Object());

      // Synchronize on the symbol-specific lock to prevent race conditions
      synchronized (lock) {
        log.debug("📊 MarketBoard Update - Getting board for symbol: {}", symbolName);
        MarketBoard marketBoard = getOrCreateMarketBoard(symbolName);

        // Clear existing levels
        marketBoard.clearBids();
        marketBoard.clearAsks();

      InstrumentConfig.InstrumentDefinition instrumentDef =
          instrumentConfig.getInstrument(symbolName);
      Symbol symbol =
          new Symbol(
              symbolName.toUpperCase(),
              instrumentDef.getPriceMultiplier(),
              instrumentDef.getQtyMultiplier());

      // Update bid levels and create corresponding orders
      // IMPORTANT: Do NOT call setBid before addMarketMakerOrder to prevent bid/ask inversion
      for (int i = 0; i < data.bids().size() && i < 10; i++) {
        var bidLevel = data.bids().get(i);
        if (bidLevel.price() != null && bidLevel.quantity() != null && bidLevel.quantity() > 0) {
          // Normalize quantity to minimum unit (truncate extra precision)
          double normalizedQuantity = instrumentDef.normalizeQuantity(bidLevel.quantity());

          // Skip if quantity is too small after normalization
          if (normalizedQuantity <= 0) {
            log.debug(
                "Skipping bid level with quantity too small: original={}, normalized={}, minQty={}",
                bidLevel.quantity(),
                normalizedQuantity,
                instrumentDef.getMinimumQuantity());
            continue;
          }

          long price = (long) (bidLevel.price() * instrumentDef.getPriceMultiplier());
          long quantity = (long) (normalizedQuantity * instrumentDef.getQtyMultiplier());

          // Create a market maker order for this price level
          // The order will be matched against existing orders and automatically added to the board
          Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.BUY);
          List<Execution> executions = marketBoard.addMarketMakerOrder(marketMakerOrder);

          // Process executions for user notifications and position updates
          orderService.processExecutionsForQueue(executions);
        }
      }

      // Update ask levels and create corresponding orders
      // IMPORTANT: Do NOT call setAsk before addMarketMakerOrder to prevent bid/ask inversion
      for (int i = 0; i < data.asks().size() && i < 10; i++) {
        var askLevel = data.asks().get(i);
        if (askLevel.price() != null && askLevel.quantity() != null && askLevel.quantity() > 0) {
          // Normalize quantity to minimum unit (truncate extra precision)
          double normalizedQuantity = instrumentDef.normalizeQuantity(askLevel.quantity());

          // Skip if quantity is too small after normalization
          if (normalizedQuantity <= 0) {
            log.debug(
                "Skipping ask level with quantity too small: original={}, normalized={}, minQty={}",
                askLevel.quantity(),
                normalizedQuantity,
                instrumentDef.getMinimumQuantity());
            continue;
          }

          long price = (long) (askLevel.price() * instrumentDef.getPriceMultiplier());
          long quantity = (long) (normalizedQuantity * instrumentDef.getQtyMultiplier());

          // Create a market maker order for this price level
          // The order will be matched against existing orders and automatically added to the board
          Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.SELL);
          List<Execution> executions = marketBoard.addMarketMakerOrder(marketMakerOrder);

          // Process executions for user notifications and position updates
          orderService.processExecutionsForQueue(executions);
        }
      }

        log.info(
            "✅ MarketBoard Update - Updated board for symbol: {} with {} bid levels, {} ask levels",
            symbolName,
            data.bids().size(),
            data.asks().size());
      } // end synchronized block

    } catch (Exception e) {
      log.error(
          "❌ MarketBoard Update - Error updating market board for symbol: {}, error: {}",
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  // Trade挿入メソッド
  public void insertTrade(ExternalTradeData data) {
    try {
      String symbolName = data.symbol();

      if (!instrumentConfig.isValidSymbol(symbolName)) {
        log.warn("❌ Trade Insert - Invalid symbol received from External: {}", symbolName);
        return;
      }

      log.debug(
          "💾 Trade Insert - Processing symbol: {}, side: {}, price: {}, quantity: {}",
          symbolName,
          data.side(),
          data.price(),
          data.quantity());

      InstrumentConfig.InstrumentDefinition instrumentDef =
          instrumentConfig.getInstrument(symbolName);

      // NOTE: External market data (EXTERNAL_FEED) is NOT saved anywhere
      // Only user executions (from OrderService/TradeController) are saved to BigQuery
      log.debug(
          "✅ External market data NOT persisted (EXTERNAL_FEED data is not stored) - symbol: {}, side: {}, price: {}, quantity: {}",
          symbolName,
          data.side(),
          data.price(),
          data.quantity());

      log.info(
          "✅ Trade Insert - Processed external market data for symbol: {} - side: {}, price: {}, quantity: {}",
          symbolName,
          data.side(),
          data.price(),
          data.quantity());

    } catch (Exception e) {
      log.error(
          "❌ Trade Insert - Error inserting trade for symbol: {}, error: {}",
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  // 非同期版メソッド（BigQuery非同期保存用）
  @Async("bigQueryAsyncExecutor")
  public CompletableFuture<Void> insertTradeAsync(ExternalTradeData data) {
    try {
      // 同期でH2に保存（順序保証のため）
      insertTrade(data);

      // BigQueryへの非同期保存は既にinsertTrade内で実行されている
      return CompletableFuture.completedFuture(null);

    } catch (Exception e) {
      log.error(
          "❌ Async Trade Insert - Error inserting trade for symbol: {}, error: {}",
          data.symbol(),
          e.getMessage(),
          e);
      return CompletableFuture.failedFuture(e);
    }
  }

  private MarketBoard getOrCreateMarketBoard(String symbolName) {
    MarketBoard marketBoard = orderService.getMarketBoard(symbolName);
    if (marketBoard == null) {
      throw new RuntimeException(
          "MarketBoard not found for symbol: "
              + symbolName
              + ". Available symbols: "
              + orderService.getAvailableSymbols());
    }
    return marketBoard;
  }

  private Order createMarketMakerOrder(Symbol symbol, long price, long quantity, Side side) {
    return new Order(
        symbol,
        new Px(symbol, price),
        new Qty(symbol, quantity),
        side,
        new ClOrdID("MM_" + UUID.randomUUID().toString()),
        new Timestamp(LocalDateTime.now(ZoneOffset.UTC)),
        OrdType.LIMIT,
        Tif.DAY,
        "MARKET_MAKER");
  }
}
