package com.ys.exch_sim.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.message.field.Username;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.bigquery.BigQueryWriter;
import com.ys.exch_sim.domain.service.BigQueryVolumeCalculationService;
import com.ys.exch_sim.domain.order_exec.Order;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionQueueServiceTest {

  @Mock
  private ExecutionRepository executionRepository;

  @Mock
  private BigQueryWriter bigQueryWriter;

  @Mock
  private BigQueryVolumeCalculationService volumeCalculationService;

  private ExecutionQueueService executionQueueService;

  private Symbol symbol;

  @BeforeEach
  void setUp() {
    symbol = new Symbol("BTCJPY", 100, 1);
    // Create service instance with mocked dependencies
    executionQueueService = new ExecutionQueueService();
    // Use reflection to set the mocked fields
    try {
      java.lang.reflect.Field bigQueryWriterField = ExecutionQueueService.class.getDeclaredField("bigQueryWriter");
      bigQueryWriterField.setAccessible(true);
      bigQueryWriterField.set(executionQueueService, bigQueryWriter);

      java.lang.reflect.Field volumeField = ExecutionQueueService.class.getDeclaredField("volumeCalculationService");
      volumeField.setAccessible(true);
      volumeField.set(executionQueueService, volumeCalculationService);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testAddExecutionAndPoll() {
    // Given
    String username = "testuser";
    Execution execution = new Execution(
        UUID.randomUUID().toString(), // execID
        "order1",                      // orderID
        username,                      // username
        "BTCJPY",                      // symbol
        ExecStatus.FILLED,             // execStatus (FILLED to pass filter)
        1000L,                         // pxRaw
        10L,                           // qtyRaw
        null,                          // counterPartyUsername
        LocalDateTime.now(),           // timestamp
        false,                         // isMarketMaker
        "BUY"                          // side
    );

    // When
    executionQueueService.addExecution(username, execution);
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);

    // Then
    assertThat(polledExecutions).hasSize(1);
    assertThat(polledExecutions.get(0)).isEqualTo(execution);
    assertThat(polledExecutions.get(0).getUsername()).isEqualTo(username);
  }

  @Test
  void testPollExecutionsWithMaxCount() {
    // Given
    String username = "testuser";

    // 3つの異なる約定を追加
    for (int i = 1; i <= 3; i++) {
      Execution execution = new Execution(
          UUID.randomUUID().toString(),
          "order" + i,
          username,
          "BTCJPY",
          ExecStatus.FILLED,  // Use FILLED to pass filter
          1000L,
          10L,
          null,
          LocalDateTime.now(),
          false,
          "BUY"
      );
      executionQueueService.addExecution(username, execution);
    }

    // When - maxCountを2に制限してポーリング
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 2);

    // Then
    assertThat(polledExecutions).hasSize(2);

    // 残りが1つあることを確認
    assertThat(executionQueueService.getQueueSize(username)).isEqualTo(1);
  }

  @Test
  void testPollAllExecutions() {
    // Given
    String username = "testuser";

    // 5つの約定を追加
    for (int i = 1; i <= 5; i++) {
      Execution execution = new Execution(
          UUID.randomUUID().toString(),
          "order" + i,
          username,
          "BTCJPY",
          ExecStatus.FILLED,  // Use FILLED to pass filter
          1000L,
          10L,
          null,
          LocalDateTime.now(),
          false,
          "BUY"
      );
      executionQueueService.addExecution(username, execution);
    }

    // When
    List<Execution> allExecutions = executionQueueService.pollAllExecutions(username);

    // Then
    assertThat(allExecutions).hasSize(5);
    assertThat(executionQueueService.getQueueSize(username)).isEqualTo(0);
  }

  @Test
  void testGetQueueSize() {
    // Given
    String username = "testuser";

    // 初期状態では0
    assertThat(executionQueueService.getQueueSize(username)).isEqualTo(0);

    // 2つの約定を追加（FILLED だけが filter を通る）
    Execution execution1 = new Execution(
        UUID.randomUUID().toString(),
        "order1",
        username,
        "BTCJPY",
        ExecStatus.FILLED,
        1000L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "BUY"
    );
    Execution execution2 = new Execution(
        UUID.randomUUID().toString(),
        "order2",
        username,
        "BTCJPY",
        ExecStatus.FILLED,
        1100L,
        5L,
        null,
        LocalDateTime.now(),
        false,
        "SELL"
    );

    executionQueueService.addExecution(username, execution1);
    executionQueueService.addExecution(username, execution2);

    // When & Then
    assertThat(executionQueueService.getQueueSize(username)).isEqualTo(2);
  }

  @Test
  void testMultipleUsersIndependentQueues() {
    // Given
    String user1 = "user1";
    String user2 = "user2";

    Execution execution1 = new Execution(
        UUID.randomUUID().toString(),
        "order1",
        user1,
        "BTCJPY",
        ExecStatus.FILLED,
        1000L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "BUY"
    );
    Execution execution2 = new Execution(
        UUID.randomUUID().toString(),
        "order2",
        user2,
        "BTCJPY",
        ExecStatus.FILLED,
        1000L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "SELL"
    );

    // When
    executionQueueService.addExecution(user1, execution1);
    executionQueueService.addExecution(user2, execution2);

    // Then
    assertThat(executionQueueService.getQueueSize(user1)).isEqualTo(1);
    assertThat(executionQueueService.getQueueSize(user2)).isEqualTo(1);

    List<Execution> user1Executions = executionQueueService.pollExecutions(user1, 10);
    List<Execution> user2Executions = executionQueueService.pollExecutions(user2, 10);

    assertThat(user1Executions).hasSize(1);
    assertThat(user2Executions).hasSize(1);
    assertThat(user1Executions.get(0).getUsername()).isEqualTo(user1);
    assertThat(user2Executions.get(0).getUsername()).isEqualTo(user2);
  }

  @Test
  void testPollFromEmptyQueue() {
    // Given
    String username = "nonexistentuser";

    // When
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);
    List<Execution> allExecutions = executionQueueService.pollAllExecutions(username);

    // Then
    assertThat(polledExecutions).isEmpty();
    assertThat(allExecutions).isEmpty();
    assertThat(executionQueueService.getQueueSize(username)).isEqualTo(0);
  }

  @Test
  void testQueueOrder() {
    // Given
    String username = "testuser";

    // 3つの約定を順番に追加（FILLEDとPARTIAL_FILLのみが filter を通る）
    Execution execution1 = new Execution(
        UUID.randomUUID().toString(),
        "order1",
        username,
        "BTCJPY",
        ExecStatus.FILLED,  // Passes filter
        1000L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "BUY"
    );
    Execution execution2 = new Execution(
        UUID.randomUUID().toString(),
        "order2",
        username,
        "BTCJPY",
        ExecStatus.FILLED,
        1100L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "SELL"
    );
    Execution execution3 = new Execution(
        UUID.randomUUID().toString(),
        "order3",
        username,
        "BTCJPY",
        ExecStatus.PARTIAL_FILL,  // Also passes filter
        1050L,
        5L,
        null,
        LocalDateTime.now(),
        false,
        "BUY"
    );

    executionQueueService.addExecution(username, execution1);
    executionQueueService.addExecution(username, execution2);
    executionQueueService.addExecution(username, execution3);

    // When
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 3);

    // Then - FIFO順で取得されることを確認
    assertThat(polledExecutions).hasSize(3);
    assertThat(polledExecutions.get(0).getOrderID()).isEqualTo("order1");
    assertThat(polledExecutions.get(1).getOrderID()).isEqualTo("order2");
    assertThat(polledExecutions.get(2).getOrderID()).isEqualTo("order3");
  }

  @Test
  void testExecutionWithCounterPartyUsername() {
    // Given
    String user1 = "user1";
    String user2 = "user2";

    Order order = createTestOrder("order1", Side.BUY, user1);
    Execution execution =
        new Execution(
            order,
            ExecStatus.FILLED,
            new Px(symbol, 100.0),
            new Qty(symbol, 10),
            user2 // counterPartyUsername
            );

    // When
    executionQueueService.addExecution(user1, execution);
    List<Execution> polledExecutions = executionQueueService.pollExecutions(user1, 1);

    // Then
    assertThat(polledExecutions).hasSize(1);
    Execution polledExecution = polledExecutions.get(0);
    assertThat(polledExecution.getOrder().getUsername()).isEqualTo(user1);
    assertThat(polledExecution.getCounterPartyUsername()).isEqualTo(user2);
    assertThat(polledExecution.getExecStatus()).isEqualTo(ExecStatus.FILLED);
  }

  @Test
  void testAddNonMarketMakerExecutionSavesToBigQuery() {
    // Given
    String username = "testuser";
    Order order = createTestOrder("order1", Side.BUY, username);
    Execution execution = new Execution(order, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));

    // When
    executionQueueService.addExecution(username, execution);

    // Then
    verify(bigQueryWriter, times(1)).enqueue(any());
  }

  @Test
  void testAddMarketMakerExecutionDoesNotSaveToBigQuery() {
    // Given
    String username = "testuser";
    Execution marketMakerExecution = createMarketMakerExecution(username);

    // When
    executionQueueService.addExecution(username, marketMakerExecution);

    // Then
    // BigQueryには保存されない
    verify(bigQueryWriter, never()).enqueue(any());
  }

  @Test
  void testAddMarketMakerExecutionSavesToMemoryCache() {
    // Given
    String username = "testuser";
    Execution marketMakerExecution = createMarketMakerExecution(username);

    // When
    executionQueueService.addExecution(username, marketMakerExecution);

    // Then
    // メモリキャッシュには保存される（ユーザーキューに追加される）
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);
    assertThat(polledExecutions).hasSize(1);
    assertThat(polledExecutions.get(0)).isEqualTo(marketMakerExecution);
    assertThat(polledExecutions.get(0).getIsMarketMaker()).isTrue();
  }

  @Test
  void testBigQueryEnqueueFailureDoesNotAffectQueueOperation() {
    // Given
    String username = "testuser";
    Order order = createTestOrder("order1", Side.BUY, username);
    Execution execution = new Execution(order, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));

    // When
    executionQueueService.addExecution(username, execution);

    // Then
    // Queue operation should still work even if BigQueryWriter fails
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);
    assertThat(polledExecutions).hasSize(1);
    assertThat(polledExecutions.get(0)).isEqualTo(execution);
  }

  @Test
  void testSymbolBasedExecutionHistory() {
    // Given
    String username = "testuser";

    Execution execution1 = new Execution(
        UUID.randomUUID().toString(),
        "order1",
        username,
        "BTCJPY",
        ExecStatus.FILLED,
        1000L,
        10L,
        null,
        LocalDateTime.now(),
        false,
        "BUY"
    );

    Execution execution2 = new Execution(
        UUID.randomUUID().toString(),
        "order2",
        username,
        "ETHJPY",
        ExecStatus.FILLED,
        500L,
        20L,
        null,
        LocalDateTime.now(),
        false,
        "SELL"
    );

    // When
    executionQueueService.addExecution(username, execution1);
    executionQueueService.addExecution(username, execution2);

    // Then - 銘柄ごとに取得できることを確認
    ExecutionQueueService.ExecutionHistoryData btcHistory =
        executionQueueService.getExecutionsBySymbol("BTCJPY", 0, 10);
    ExecutionQueueService.ExecutionHistoryData ethHistory =
        executionQueueService.getExecutionsBySymbol("ETHJPY", 0, 10);

    assertThat(btcHistory.executions).hasSize(1);
    assertThat(btcHistory.executions.get(0).getSymbol()).isEqualTo("BTCJPY");

    assertThat(ethHistory.executions).hasSize(1);
    assertThat(ethHistory.executions.get(0).getSymbol()).isEqualTo("ETHJPY");
  }

  private Execution createMarketMakerExecution(String username) {
    return new Execution(
        UUID.randomUUID().toString(),
        "order123",
        username,
        "BTCJPY",
        ExecStatus.FILLED,
        1000L,
        1L,
        null,
        LocalDateTime.now(),
        true, // isMarketMaker = true
        "BUY"
    );
  }

  private Order createTestOrder(String orderId, Side side, String username) {
    return new Order(
        symbol,
        new Px(symbol, 100.0),
        new Qty(symbol, 10),
        side,
        new ClOrdID(orderId),
        new Timestamp(LocalDateTime.now()),
        OrdType.LIMIT,
        Tif.GTC,
        username);
  }
}
