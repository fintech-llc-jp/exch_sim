package com.ys.exch_sim.domain.market_data.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.queue.OrderedTradeProcessor;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.service.OrderService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 統合マーケットデータサービス
 *
 * WebSocketクライアントから受信した外部マーケットデータを処理し、MarketBoardに反映します。
 * DirectMarketDataServiceとMarketDataSyncServiceを統合し、責務を明確化しました。
 *
 * 主な機能:
 * - 外部ボードデータの処理（レート制限、変換、MarketBoard更新）
 * - 外部トレードデータの処理（順序保証付き）
 * - シンボルマッピング
 * - 同期制御（シンボル別ロック）
 */
@Slf4j
@Service
public class MarketDataService {

  private final OrderService orderService;
  private final InstrumentConfig instrumentConfig;
  private final MarketDataClientConfig clientConfig;
  private final OrderedTradeProcessor orderedTradeProcessor;

  // Rate limiting: track last update time per symbol
  private final ConcurrentHashMap<String, Instant> lastBoardUpdateTime = new ConcurrentHashMap<>();

  // Concurrency control: symbol-specific locks to prevent race conditions
  private final ConcurrentHashMap<String, Object> symbolLocks = new ConcurrentHashMap<>();

  @Value("${market-data.update-interval-ms:1000}")
  private long updateIntervalMs;

  public MarketDataService(
      OrderService orderService,
      InstrumentConfig instrumentConfig,
      MarketDataClientConfig clientConfig,
      @Autowired(required = false) OrderedTradeProcessor orderedTradeProcessor) {
    this.orderService = orderService;
    this.instrumentConfig = instrumentConfig;
    this.clientConfig = clientConfig;
    this.orderedTradeProcessor = orderedTradeProcessor;

    log.info("✅ MarketDataService initialized with updateIntervalMs={}",
        updateIntervalMs);
  }

  /**
   * 非同期マーケットボード処理 - WebSocketスレッドをブロックしない
   */
  @Async("marketDataTaskExecutor")
  public CompletableFuture<Void> processMarketBoardAsync(ExternalMarketBoardData data) {
    return CompletableFuture.runAsync(() -> processMarketBoard(data));
  }

  /**
   * 同期マーケットボード処理
   *
   * レート制限、シンボルマッピング、ロック制御、Board更新を一連の処理として実行します。
   * これにより、トランザクション境界が明確になり、競合条件のリスクが低減されます。
   */
  public void processMarketBoard(ExternalMarketBoardData data) {
    try {
      // Step 1: Symbol mapping
      String targetSymbol = mapSymbol(data.exchange(), data.symbol());
      if (targetSymbol == null) {
        log.debug("🔍 No symbol mapping found for {}:{}", data.exchange(), data.symbol());
        return;
      }

      // Step 2: Rate limiting check
      Instant now = Instant.now();
      Instant lastUpdate = lastBoardUpdateTime.get(targetSymbol);

      if (lastUpdate != null) {
        long elapsedMs = Duration.between(lastUpdate, now).toMillis();
        if (elapsedMs < updateIntervalMs) {
          log.debug(
              "⏱️ Throttling board update for {} - {}ms since last update (min: {}ms)",
              targetSymbol,
              elapsedMs,
              updateIntervalMs);
          return; // Skip this update
        }
      }

      log.debug(
          "📊 Processing MarketBoard for {} -> {} with {} bids, {} asks",
          data.exchange() + ":" + data.symbol(),
          targetSymbol,
          data.bids().size(),
          data.asks().size());

      // Step 3: Validate symbol
      if (!instrumentConfig.isValidSymbol(targetSymbol)) {
        log.warn("❌ MarketBoard Update - Invalid symbol: {}", targetSymbol);
        return;
      }

      // Step 4: Create target data with mapped symbol
      ExternalMarketBoardData targetData = new ExternalMarketBoardData(
          data.exchange(),
          targetSymbol,
          data.bids(),
          data.asks(),
          data.timestamp()
      );

      // Step 5: Record update time
      lastBoardUpdateTime.put(targetSymbol, now);

      // Step 6: Update MarketBoard with symbol-specific lock
      updateMarketBoard(targetData);

    } catch (Exception e) {
      log.error(
          "❌ Error processing market board for {}:{} - {}",
          data.exchange(),
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  /**
   * MarketBoard更新処理（内部メソッド）
   *
   * シンボル別のロックを取得し、既存のBoard内容をクリアしてから
   * 新しいマーケットメーカー注文を作成・追加します。
   */
  private void updateMarketBoard(ExternalMarketBoardData data) {
    String symbolName = data.symbol();

    // Get or create a lock for this specific symbol
    Object lock = symbolLocks.computeIfAbsent(symbolName, k -> new Object());

    // Synchronize on the symbol-specific lock to prevent race conditions
    synchronized (lock) {
      log.debug("📊 MarketBoard Update - Getting board for symbol: {}", symbolName);
      MarketBoard marketBoard = getOrCreateMarketBoard(symbolName);

      // Clear existing market maker orders
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
          Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.BUY);
          List<Execution> executions = marketBoard.addMarketMakerOrder(marketMakerOrder);

          // Process executions for user notifications and position updates
          orderService.processExecutionsForQueue(executions);
        }
      }

      // Update ask levels and create corresponding orders
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
  }

  /**
   * 非同期取引データ処理 - WebSocketスレッドをブロックしない
   */
  @Async("marketDataTaskExecutor")
  public CompletableFuture<Void> processTradeAsync(ExternalTradeData data) {
    return CompletableFuture.runAsync(() -> processTrade(data));
  }

  /**
   * 同期取引データ処理 - 順序保証付きで処理
   *
   * 外部マーケットからのトレードデータを受信し、OrderedTradeProcessorに委譲します。
   * 注意: 外部トレードデータは参照用であり、永続化されません（設計方針）。
   */
  public void processTrade(ExternalTradeData data) {
    try {
      String targetSymbol = mapSymbol(data.exchange(), data.symbol());
      if (targetSymbol == null) {
        log.debug("🔍 No symbol mapping found for {}:{}", data.exchange(), data.symbol());
        return;
      }

      log.debug(
          "💰 Processing Trade for {} -> {} - side: {}, price: {}, quantity: {}",
          data.exchange() + ":" + data.symbol(),
          targetSymbol,
          data.side(),
          data.price(),
          data.quantity());

      // Delegate to OrderedTradeProcessor for sequential processing per symbol
      if (orderedTradeProcessor != null) {
        orderedTradeProcessor.submitTrade(targetSymbol, data);
      } else {
        log.warn("⚠️ OrderedTradeProcessor not available, trade processing skipped");
      }

    } catch (Exception e) {
      log.error(
          "❌ Error processing trade for {}:{} - {}",
          data.exchange(),
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  /**
   * シンボルマッピング - 外部取引所のシンボルを内部シンボルに変換
   */
  private String mapSymbol(String exchange, String symbol) {
    String mappedSymbol = clientConfig.mapSymbol(exchange.toUpperCase(), symbol);

    if (mappedSymbol != null && instrumentConfig.isValidSymbol(mappedSymbol)) {
      return mappedSymbol;
    }

    return null;
  }

  /**
   * MarketBoard取得または作成
   */
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

  /**
   * マーケットメーカー注文の作成
   */
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
        "MARKET_MAKER",
        null); // openClose not specified for market maker orders
  }

  /**
   * サービス統計情報を取得
   */
  public String getServiceStats() {
    int instrumentCount =
        instrumentConfig.getInstruments() != null ? instrumentConfig.getInstruments().size() : 0;
    int trackedSymbols = lastBoardUpdateTime.size();
    return String.format(
        "MarketDataService [Instruments: %d, Tracked symbols: %d, Update interval: %dms]",
        instrumentCount,
        trackedSymbols,
        updateIntervalMs);
  }
}
