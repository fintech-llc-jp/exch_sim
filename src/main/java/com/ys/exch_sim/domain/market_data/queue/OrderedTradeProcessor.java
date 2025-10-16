package com.ys.exch_sim.domain.market_data.queue;

import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.MarketDataSyncService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 順序保証取引処理サービス シンボル別に専用スレッドで取引を順序処理 外部市場データは保存しない */
@Slf4j
@Service
public class OrderedTradeProcessor {

  private final MarketDataSyncService marketDataSyncService;
  private final InstrumentConfig instrumentConfig;
  private final Map<String, SingleThreadExecutor> symbolExecutors = new ConcurrentHashMap<>();

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  private final BigQueryService bigQueryService;

  public OrderedTradeProcessor(
      @Autowired MarketDataSyncService marketDataSyncService,
      @Autowired InstrumentConfig instrumentConfig,
      @Autowired(required = false) BigQueryService bigQueryService) {
    this.marketDataSyncService = marketDataSyncService;
    this.instrumentConfig = instrumentConfig;
    this.bigQueryService = bigQueryService;
    log.info("🔄 OrderedTradeProcessor initialized with BigQuery enabled: {}", bigQueryEnabled);
  }

  /** シンボル別に専用スレッドで順序処理 同一シンボルの取引は常に同じスレッドで順番に処理される */
  public void submitTrade(String symbol, ExternalTradeData tradeData) {
    if (symbol == null || tradeData == null) {
      log.warn("⚠️ Invalid trade submission: symbol={}, tradeData={}", symbol, tradeData);
      return;
    }

    SingleThreadExecutor executor =
        symbolExecutors.computeIfAbsent(
            symbol, k -> new SingleThreadExecutor("Trade-" + symbol + "-"));

    executor.submit(
        () -> {
          try {
            processTradeSynchronously(symbol, tradeData);
          } catch (Exception e) {
            log.error("❌ Trade processing failed for symbol: {}, trade: {}", symbol, tradeData, e);
          }
        });

    log.debug("📤 Trade submitted for ordered processing: {} - {}", symbol, tradeData.side());
  }

  /** 同期的にトレードを処理（シンボル専用スレッド内で実行） 外部市場データは保存しない */
  private void processTradeSynchronously(String symbol, ExternalTradeData tradeData) {
    try {
      log.debug(
          "🔄 Processing trade synchronously for symbol: {} - side: {}, price: {}, quantity: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity());

      // NOTE: External market data (EXTERNAL_FEED) is NOT saved anywhere
      // Only user executions (from OrderService/TradeController) are saved to BigQuery
      log.debug(
          "ℹ️ External market data NOT persisted (EXTERNAL_FEED data is not stored) - symbol: {} - side: {}, price: {}, quantity: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity());

      log.info(
          "✅ Trade processed successfully for symbol: {} - side: {}, price: {}, quantity: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity());

    } catch (Exception e) {
      log.error(
          "❌ Synchronous trade processing failed for symbol: {}, trade: {}", symbol, tradeData, e);
      throw e; // Re-throw to ensure error handling in submit thread
    }
  }

  /** 特定シンボルの処理統計情報を取得 */
  public String getSymbolProcessingStats(String symbol) {
    SingleThreadExecutor executor = symbolExecutors.get(symbol);
    if (executor == null) {
      return String.format("Symbol: %s [No executor - not processed yet]", symbol);
    }

    return String.format(
        "Symbol: %s [Executor: %s, Shutdown: %s, Terminated: %s]",
        symbol, executor.getThreadName(), executor.isShutdown(), executor.isTerminated());
  }

  /** 全体の処理統計情報を取得 */
  public String getProcessingStats() {
    int totalExecutors = symbolExecutors.size();
    long activeExecutors =
        symbolExecutors.values().stream().filter(executor -> !executor.isShutdown()).count();

    return String.format(
        "OrderedTradeProcessor [Total Executors: %d, Active: %d, Symbols: %s, BigQuery: %s]",
        totalExecutors, activeExecutors, symbolExecutors.keySet(), bigQueryEnabled);
  }

  /** アプリケーション停止時に全てのエグゼキューターを適切に停止 */
  @PreDestroy
  public void shutdown() {
    log.info("🛑 Shutting down OrderedTradeProcessor with {} executors", symbolExecutors.size());

    symbolExecutors.forEach(
        (symbol, executor) -> {
          log.debug("🛑 Shutting down executor for symbol: {}", symbol);
          executor.shutdown();
        });

    // Clear the map after shutdown
    symbolExecutors.clear();

    log.info("✅ OrderedTradeProcessor shutdown completed");
  }

  /** 特定シンボルのエグゼキューターを停止（テスト用） */
  public void shutdownSymbolExecutor(String symbol) {
    SingleThreadExecutor executor = symbolExecutors.remove(symbol);
    if (executor != null) {
      log.info("🛑 Shutting down executor for symbol: {}", symbol);
      executor.shutdown();
    }
  }
}
