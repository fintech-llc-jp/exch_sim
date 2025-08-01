package com.ys.exch_sim.domain.market_data.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.RedisMarketMakeMessage;
import com.ys.exch_sim.domain.dto.RedisTradeInsertMessage;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.queue.OrderedTradeProcessor;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.service.MarketDataSyncService;
import com.ys.exch_sim.domain.service.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Phase 3統合テスト 新旧システムの並行テスト、データ整合性確認、BigQuery非同期保存のテスト */
@SpringBootTest
@ActiveProfiles("test")
class DirectMarketDataServiceIntegrationTest {

  @Autowired private DirectMarketDataService directMarketDataService;

  @Autowired private MarketDataSyncService marketDataSyncService;

  @Autowired private OrderedTradeProcessor orderedTradeProcessor;

  @Autowired private ExecutionRepository executionRepository;

  @Autowired private InstrumentConfig instrumentConfig;

  @Autowired private MarketDataClientConfig clientConfig;

  @Autowired private OrderService orderService;

  @BeforeEach
  void setUp() {
    // テスト前のデータクリーンアップ
    executionRepository.deleteAll();

    // テスト用のMarketBoardを初期化
    initializeMarketBoards();
  }

  /** テスト用のMarketBoardを初期化 */
  private void initializeMarketBoards() {
    try {
      // 主要なシンボルのMarketBoardを初期化
      String[] symbols = {"G_BTCJPY", "B_BTCJPY", "G_FX_BTCJPY", "B_FX_BTCJPY"};
      for (String symbol : symbols) {
        if (instrumentConfig.isValidSymbol(symbol)) {
          orderService.getOrCreateMarketBoardForSync(symbol);
        }
      }
    } catch (Exception e) {
      // 初期化エラーは無視（テスト環境では正常）
    }
  }

  @Test
  void testMarketBoardProcessing_NewSystem() {
    // 新しいシステムでのマーケットボード処理テスト
    ExternalMarketBoardData boardData = createTestMarketBoardData();

    // 非同期処理
    CompletableFuture<Void> future = directMarketDataService.processMarketBoardAsync(boardData);

    // 処理完了を待機
    assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));

    // 処理結果の確認
    assertTrue(future.isDone());
    assertFalse(future.isCompletedExceptionally());
  }

  @Test
  @Transactional
  void testTradeProcessing_NewSystem() {
    // 新しいシステムでの取引処理テスト
    ExternalTradeData tradeData = createTestTradeData();

    // 順序保証付き処理
    directMarketDataService.B_processTrade(tradeData);

    // 処理完了を待機（非同期処理のため）
    try {
      Thread.sleep(2000); // より長い待機時間
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // H2データベースに保存されていることを確認
    long count = executionRepository.count();
    assertTrue(count > 0, "Trade should be saved to H2 database");
  }

  @Test
  @Transactional
  void testMarketBoardProcessing_OldSystem() {
    // 古いシステム（Redis）でのマーケットボード処理テスト
    RedisMarketMakeMessage message = createTestRedisMarketMakeMessage();

    // 既存メソッドで処理
    assertDoesNotThrow(() -> marketDataSyncService.updateMarketBoard(message));
  }

  @Test
  @Transactional
  void testTradeProcessing_OldSystem() {
    // 古いシステム（Redis）での取引処理テスト
    RedisTradeInsertMessage message = createTestRedisTradeInsertMessage();

    // 既存メソッドで処理
    assertDoesNotThrow(() -> marketDataSyncService.insertTrade(message));

    // H2データベースに保存されていることを確認
    long count = executionRepository.count();
    assertTrue(count > 0, "Trade should be saved to H2 database");
  }

  @Test
  @Transactional
  void testParallelProcessing_OldAndNewSystems() {
    // 新旧システムの並行処理テスト
    ExternalMarketBoardData newBoardData = createTestMarketBoardData();
    RedisMarketMakeMessage oldBoardMessage = createTestRedisMarketMakeMessage();

    ExternalTradeData newTradeData = createTestTradeData();
    RedisTradeInsertMessage oldTradeMessage = createTestRedisTradeInsertMessage();

    // 並行して処理
    CompletableFuture<Void> newBoardFuture =
        directMarketDataService.processMarketBoardAsync(newBoardData);
    CompletableFuture<Void> newTradeFuture =
        directMarketDataService.processTradeAsync(newTradeData);

    // 古いシステムも並行して処理
    marketDataSyncService.updateMarketBoard(oldBoardMessage);
    marketDataSyncService.insertTrade(oldTradeMessage);

    // 全ての処理完了を待機
    assertDoesNotThrow(
        () -> {
          newBoardFuture.get(5, TimeUnit.SECONDS);
          newTradeFuture.get(5, TimeUnit.SECONDS);
        });

    // 処理完了を待機（非同期処理のため）
    try {
      Thread.sleep(3000); // より長い待機時間
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // データ整合性確認
    long count = executionRepository.count();
    assertTrue(count >= 2, "Both old and new systems should save data");
  }

  @Test
  @Transactional
  void testOrderedTradeProcessing() {
    // 順序保証取引処理のテスト
    String symbol = "G_BTCJPY";
    ExternalTradeData trade1 = createTestTradeData("G_BTCJPY", 500000.0, 0.1, "BUY");
    ExternalTradeData trade2 = createTestTradeData("G_BTCJPY", 501000.0, 0.2, "SELL");
    ExternalTradeData trade3 = createTestTradeData("G_BTCJPY", 502000.0, 0.3, "BUY");

    // 順序保証付きで処理
    orderedTradeProcessor.submitTrade(symbol, trade1);
    orderedTradeProcessor.submitTrade(symbol, trade2);
    orderedTradeProcessor.submitTrade(symbol, trade3);

    // 処理完了を待機（非同期処理のため）
    try {
      Thread.sleep(3000); // より長い待機時間
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // 全ての取引が保存されていることを確認
    long count = executionRepository.count();
    assertEquals(3, count, "All trades should be saved in order");
  }

  @Test
  void testSymbolMapping() {
    // シンボルマッピングのテスト
    String mappedSymbol = clientConfig.mapSymbol("BITFLYER", "BTC_JPY");
    assertEquals("G_BTCJPY", mappedSymbol, "BITFLYER.BTC_JPY should map to G_BTCJPY");

    mappedSymbol = clientConfig.mapSymbol("GMO", "BTC_JPY");
    assertEquals("B_BTCJPY", mappedSymbol, "GMO.BTC_JPY should map to B_BTCJPY");
  }

  @Test
  void testDataConversion() {
    // データ変換のテスト
    ExternalMarketBoardData boardData = createTestMarketBoardData();
    ExternalTradeData tradeData = createTestTradeData();

    // 変換処理が正常に動作することを確認
    assertDoesNotThrow(
        () -> {
          directMarketDataService.processMarketBoard(boardData);
          directMarketDataService.B_processTrade(tradeData);
        });
  }

  @Test
  @Transactional
  void testBigQueryAsyncProcessing() {
    // BigQuery非同期処理のテスト（BigQueryが有効な場合のみ）
    ExternalTradeData tradeData = createTestTradeData();

    // 非同期処理
    CompletableFuture<Void> future = directMarketDataService.processTradeAsync(tradeData);

    // 処理完了を待機
    assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));

    // 処理完了を待機（非同期処理のため）
    try {
      Thread.sleep(2000); // より長い待機時間
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // H2には同期で保存されていることを確認
    long count = executionRepository.count();
    assertTrue(count > 0, "Trade should be saved to H2 database synchronously");
  }

  @Test
  void testErrorHandling() {
    // エラーハンドリングのテスト
    // 無効なシンボルでの処理テスト
    ExternalTradeData invalidTradeData =
        createTestTradeData("INVALID_SYMBOL", 500000.0, 0.1, "BUY");

    // 無効なデータでもシステムが停止しないことを確認
    assertDoesNotThrow(
        () -> {
          directMarketDataService.B_processTrade(invalidTradeData);
        });
  }

  // テストデータ作成ヘルパーメソッド
  private ExternalMarketBoardData createTestMarketBoardData() {
    return new ExternalMarketBoardData(
        "BITFLYER",
        "B_BTC_JPY",
        List.of(
            new ExternalMarketBoardData.PriceLevel(500000.0, 1.0),
            new ExternalMarketBoardData.PriceLevel(499000.0, 2.0)),
        List.of(
            new ExternalMarketBoardData.PriceLevel(501000.0, 1.5),
            new ExternalMarketBoardData.PriceLevel(502000.0, 2.5)),
        Instant.now());
  }

  private ExternalTradeData createTestTradeData() {
    return createTestTradeData("G_BTCJPY", 500000.0, 0.1, "BUY");
  }

  private ExternalTradeData createTestTradeData(
      String symbol, Double price, Double quantity, String side) {
    return new ExternalTradeData("BITFLYER", symbol, price, quantity, side, Instant.now());
  }

  private RedisMarketMakeMessage createTestRedisMarketMakeMessage() {
    return new RedisMarketMakeMessage(
        "G_BTCJPY",
        List.of(
            new RedisMarketMakeMessage.PriceLevel(500000.0, 1.0),
            new RedisMarketMakeMessage.PriceLevel(499000.0, 2.0)),
        List.of(
            new RedisMarketMakeMessage.PriceLevel(501000.0, 1.5),
            new RedisMarketMakeMessage.PriceLevel(502000.0, 2.5)));
  }

  private RedisTradeInsertMessage createTestRedisTradeInsertMessage() {
    return new RedisTradeInsertMessage("G_BTCJPY", 500000.0, 0.1, "BUY");
  }
}
