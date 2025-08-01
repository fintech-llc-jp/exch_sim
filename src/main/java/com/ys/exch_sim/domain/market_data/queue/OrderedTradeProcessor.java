package com.ys.exch_sim.domain.market_data.queue;

import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
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

/** 順序保証取引処理サービス シンボル別に専用スレッドで取引を順序処理 H2への同期保存とBigQueryへの非同期保存を分離 */
@Slf4j
@Service
public class OrderedTradeProcessor {

  private final MarketDataSyncService marketDataSyncService;
  private final ExecutionRepository executionRepository;
  private final InstrumentConfig instrumentConfig;
  private final Map<String, SingleThreadExecutor> symbolExecutors = new ConcurrentHashMap<>();

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  private final BigQueryService bigQueryService;

  public OrderedTradeProcessor(
      @Autowired MarketDataSyncService marketDataSyncService,
      @Autowired ExecutionRepository executionRepository,
      @Autowired InstrumentConfig instrumentConfig,
      @Autowired(required = false) BigQueryService bigQueryService) {
    this.marketDataSyncService = marketDataSyncService;
    this.executionRepository = executionRepository;
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

  /** 同期的にトレードを処理（シンボル専用スレッド内で実行） H2への同期保存とBigQueryへの非同期保存を分離 */
  private void processTradeSynchronously(String symbol, ExternalTradeData tradeData) {
    try {
      log.debug(
          "🔄 Processing trade synchronously for symbol: {} - side: {}, price: {}, quantity: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity());

      // 1. H2への同期保存（順序保証のため）
      Execution execution = createExecutionFromTradeData(symbol, tradeData);
      executionRepository.save(execution);

      log.debug(
          "✅ H2 database saved for symbol: {} - execId: {}", symbol, execution.getExecID().getId());

      // 2. BigQueryへの非同期保存（パフォーマンス重視）
      if (bigQueryEnabled && bigQueryService != null) {
        CompletableFuture.runAsync(
            () -> {
              try {
                BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(execution);
                bigQueryService.insertExecutionAsync(bigQueryEntity);
                log.debug(
                    "🔄 BigQuery async save initiated for symbol: {} - execId: {}",
                    symbol,
                    execution.getExecID().getId());
              } catch (Exception e) {
                log.error(
                    "❌ BigQuery async save failed for symbol: {} - execId: {} - Error: {}",
                    symbol,
                    execution.getExecID().getId(),
                    e.getMessage(),
                    e);
              }
            });
      }

      log.info(
          "✅ Trade processed successfully for symbol: {} - side: {}, price: {}, quantity: {},"
              + " execId: {}",
          symbol,
          tradeData.side(),
          tradeData.price(),
          tradeData.quantity(),
          execution.getExecID().getId());

    } catch (Exception e) {
      log.error(
          "❌ Synchronous trade processing failed for symbol: {}, trade: {}", symbol, tradeData, e);
      throw e; // Re-throw to ensure error handling in submit thread
    }
  }

  /** ExternalTradeDataからExecutionエンティティを作成 */
  private Execution createExecutionFromTradeData(String symbol, ExternalTradeData tradeData) {
    InstrumentConfig.InstrumentDefinition instrumentDef = instrumentConfig.getInstrument(symbol);
    Side side = "BUY".equals(tradeData.side()) ? Side.BUY : Side.SELL;

    return new Execution(
        UUID.randomUUID().toString(), // execID
        UUID.randomUUID().toString(), // orderID (fake)
        "EXTERNAL_FEED", // username
        symbol,
        ExecStatus.FILLED,
        (long)
            (tradeData.price() * instrumentDef.getPriceMultiplier()), // Convert to internal price
        (long)
            (tradeData.quantity()
                * instrumentDef.getQtyMultiplier()), // Convert to internal quantity
        "MARKET", // counterPartyUsername
        LocalDateTime.now(ZoneOffset.UTC),
        false, // isMarketMaker
        side.toString());
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
