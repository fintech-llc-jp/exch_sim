package com.ys.exch_sim.domain.market_data.queue;

import com.ys.exch_sim.domain.bigquery.BigQueryEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryWriter;
import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
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

/** 順序保証取引処理サービス シンボル別に専用スレッドで取引を順序処理 */
@Slf4j
@Service
public class OrderedTradeProcessor {

  private final InstrumentConfig instrumentConfig;
  private final ExecutionQueueService executionQueueService;
  private final Map<String, SingleThreadExecutor> symbolExecutors = new ConcurrentHashMap<>();

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  private final BigQueryWriter bigQueryWriter;

  public OrderedTradeProcessor(
      @Autowired InstrumentConfig instrumentConfig,
      @Autowired ExecutionQueueService executionQueueService,
      @Autowired(required = false) BigQueryWriter bigQueryWriter) {
    this.instrumentConfig = instrumentConfig;
    this.executionQueueService = executionQueueService;
    this.bigQueryWriter = bigQueryWriter;
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

  /** 同期的にトレードを処理（シンボル専用スレッド内で実行） ExternalTradeDataをExecutionに変換して保存 */
  private void processTradeSynchronously(String symbol, ExternalTradeData tradeData) {
    try {
      log.debug(
          "🔄 Processing trade synchronously for symbol: {} - side: {}, price: {}, quantity: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity());

      // ExternalTradeData → Execution への変換
      InstrumentConfig.InstrumentDefinition instrumentDef =
          instrumentConfig.getInstrument(symbol);

      if (instrumentDef == null) {
        log.warn("⚠️ Instrument definition not found for symbol: {}", symbol);
        return;
      }

      String sideStr = "BUY".equals(tradeData.side()) ? "BUY" : "SELL";

      // Calculate quantity: ensure minimum value of 1 to avoid qty=0 data
      long pxRaw = (long) (tradeData.price() * instrumentDef.getPriceMultiplier());
      long qtyRaw = Math.max(1, (long) (tradeData.quantity() * instrumentDef.getQtyMultiplier()));

      Execution execution =
          new Execution(
              UUID.randomUUID().toString(), // execID
              UUID.randomUUID().toString(), // orderID (fake)
              "EXTERNAL_FEED", // username
              symbol,
              ExecStatus.FILLED,
              pxRaw,
              qtyRaw,
              "EXTERNAL_FEED", // counterPartyUsername
              LocalDateTime.now(ZoneOffset.UTC),
              false, // isMarketMaker
              sideStr);

      // メモリキャッシュに保存
      executionQueueService.addExecution("EXTERNAL_FEED", execution);

      // BigQueryに保存（有効な場合）
      if (bigQueryWriter != null) {
        bigQueryWriter.enqueue(BigQueryEntity.execution(execution));
      }

      log.info(
          "✅ External trade converted to Execution: {} - {} {} @ {} (execID: {})",
          symbol,
          tradeData.side(),
          tradeData.quantity(),
          tradeData.price(),
          execution.getExecID().getId());

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
