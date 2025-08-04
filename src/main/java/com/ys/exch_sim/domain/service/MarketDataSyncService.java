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
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.infra.Pair;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
  private final ExecutionRepository executionRepository;
  private final InstrumentConfig instrumentConfig;
  private final Optional<BigQueryService> bigQueryService;

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  public MarketDataSyncService(
      OrderService orderService,
      ExecutionRepository executionRepository,
      InstrumentConfig instrumentConfig,
      @Autowired(required = false) BigQueryService bigQueryService) {
    this.orderService = orderService;
    this.executionRepository = executionRepository;
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
      for (int i = 0; i < data.bids().size() && i < 10; i++) {
        var bidLevel = data.bids().get(i);
        if (bidLevel.price() != null && bidLevel.quantity() != null && bidLevel.quantity() > 0) {
          long price = (long) (bidLevel.price() * instrumentDef.getPriceMultiplier());
          long quantity = (long) (bidLevel.quantity() * instrumentDef.getQtyMultiplier());
          marketBoard.setBid(i, new Pair<>(price, quantity));

          // Create a market maker order for this price level
          Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.BUY);
          marketBoard.addMarketMakerOrder(marketMakerOrder);
        }
      }

      // Update ask levels and create corresponding orders
      for (int i = 0; i < data.asks().size() && i < 10; i++) {
        var askLevel = data.asks().get(i);
        if (askLevel.price() != null && askLevel.quantity() != null && askLevel.quantity() > 0) {
          long price = (long) (askLevel.price() * instrumentDef.getPriceMultiplier());
          long quantity = (long) (askLevel.quantity() * instrumentDef.getQtyMultiplier());
          marketBoard.setAsk(i, new Pair<>(price, quantity));

          // Create a market maker order for this price level
          Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.SELL);
          marketBoard.addMarketMakerOrder(marketMakerOrder);
        }
      }

      log.info(
          "✅ MarketBoard Update - Updated board for symbol: {} with {} bid levels, {} ask levels",
          symbolName,
          data.bids().size(),
          data.asks().size());

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

      Side side = "BUY".equals(data.side()) ? Side.BUY : Side.SELL;

      // Create execution record
      Execution execution =
          new Execution(
              UUID.randomUUID().toString(), // execID
              UUID.randomUUID().toString(), // orderID (fake)
              "EXTERNAL_FEED", // username
              symbolName,
              ExecStatus.FILLED,
              (long)
                  (data.price() * instrumentDef.getPriceMultiplier()), // Convert to internal price
              (long)
                  (data.quantity()
                      * instrumentDef.getQtyMultiplier()), // Convert to internal quantity
              "MARKET", // counterPartyUsername
              LocalDateTime.now(ZoneOffset.UTC),
              false, // isMarketMaker
              side.toString());

      // Save to H2 database
      executionRepository.save(execution);

      // Asynchronously save to BigQuery if enabled
      if (bigQueryEnabled && bigQueryService.isPresent()) {
        try {
          BigQueryExecutionEntity bigQueryExecution = new BigQueryExecutionEntity(execution);
          bigQueryService.get().insertExecutionAsync(bigQueryExecution);
          log.debug(
              "🔄 BigQuery async insert initiated for execution: {}",
              execution.getExecID().getId());
        } catch (Exception e) {
          log.warn(
              "⚠️ Failed to initiate BigQuery async insert for execution: {} - Error: {}",
              execution.getExecID().getId(),
              e.getMessage());
          // Continue processing - BigQuery failure should not stop the main flow
        }
      }

      log.info(
          "✅ Trade Insert - Saved execution for symbol: {} - side: {}, price: {}, quantity: {},"
              + " execId: {}",
          symbolName,
          data.side(),
          data.price(),
          data.quantity(),
          execution.getExecID().getId());

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
