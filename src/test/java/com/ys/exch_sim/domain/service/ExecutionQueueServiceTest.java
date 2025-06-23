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

  @InjectMocks
  private ExecutionQueueService executionQueueService;

  private Symbol symbol;

  @BeforeEach
  void setUp() {
    symbol = new Symbol("BTCJPY", 100, 1);
  }

  @Test
  void testAddExecutionAndPoll() {
    // Given
    String username = "testuser";
    Order order = createTestOrder("order1", Side.BUY, username);
    Execution execution =
        new Execution(order, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));

    // When
    executionQueueService.addExecution(username, execution);
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);

    // Then
    assertThat(polledExecutions).hasSize(1);
    assertThat(polledExecutions.get(0)).isEqualTo(execution);
    assertThat(polledExecutions.get(0).getOrder().getUsername()).isEqualTo(username);
  }

  @Test
  void testPollExecutionsWithMaxCount() {
    // Given
    String username = "testuser";

    // 3つの異なる約定を追加
    for (int i = 1; i <= 3; i++) {
      Order order = createTestOrder("order" + i, Side.BUY, username);
      Execution execution =
          new Execution(order, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));
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
      Order order = createTestOrder("order" + i, Side.BUY, username);
      Execution execution =
          new Execution(order, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));
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

    // 2つの約定を追加
    Order order1 = createTestOrder("order1", Side.BUY, username);
    Order order2 = createTestOrder("order2", Side.SELL, username);
    Execution execution1 =
        new Execution(order1, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));
    Execution execution2 =
        new Execution(order2, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));

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

    Order order1 = createTestOrder("order1", Side.BUY, user1);
    Order order2 = createTestOrder("order2", Side.SELL, user2);
    Execution execution1 =
        new Execution(order1, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));
    Execution execution2 =
        new Execution(order2, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));

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
    assertThat(user1Executions.get(0).getOrder().getUsername()).isEqualTo(user1);
    assertThat(user2Executions.get(0).getOrder().getUsername()).isEqualTo(user2);
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

    // 3つの約定を順番に追加
    Order order1 = createTestOrder("order1", Side.BUY, username);
    Order order2 = createTestOrder("order2", Side.SELL, username);
    Order order3 = createTestOrder("order3", Side.BUY, username);

    Execution execution1 =
        new Execution(order1, ExecStatus.NEW, new Px(symbol, 0.0), new Qty(symbol, 0));
    Execution execution2 =
        new Execution(order2, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));
    Execution execution3 =
        new Execution(order3, ExecStatus.PARTIAL_FILL, new Px(symbol, 99.0), new Qty(symbol, 5));

    executionQueueService.addExecution(username, execution1);
    executionQueueService.addExecution(username, execution2);
    executionQueueService.addExecution(username, execution3);

    // When
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 3);

    // Then - FIFO順で取得されることを確認
    assertThat(polledExecutions).hasSize(3);
    assertThat(polledExecutions.get(0).getOrder().getClOrdID().getId()).isEqualTo("order1");
    assertThat(polledExecutions.get(1).getOrder().getClOrdID().getId()).isEqualTo("order2");
    assertThat(polledExecutions.get(2).getOrder().getClOrdID().getId()).isEqualTo("order3");
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
  void testAddNonMarketMakerExecutionSavesToDatabase() {
    // Given
    String username = "testuser";
    Order order = createTestOrder("order1", Side.BUY, username);
    Execution execution = new Execution(order, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));
    
    when(executionRepository.save(any(Execution.class))).thenReturn(execution);

    // When
    executionQueueService.addExecution(username, execution);

    // Then
    verify(executionRepository, times(1)).save(execution);
  }

  @Test
  void testAddMarketMakerExecutionDoesNotSaveToDatabase() {
    // Given
    String username = "testuser";
    Execution marketMakerExecution = createMarketMakerExecution(username);

    // When
    executionQueueService.addExecution(username, marketMakerExecution);

    // Then
    verify(executionRepository, never()).save(any(Execution.class));
  }

  @Test
  void testDatabaseSaveFailureDoesNotAffectQueueOperation() {
    // Given
    String username = "testuser";
    Order order = createTestOrder("order1", Side.BUY, username);
    Execution execution = new Execution(order, ExecStatus.FILLED, new Px(symbol, 100.0), new Qty(symbol, 10));
    
    when(executionRepository.save(any(Execution.class))).thenThrow(new RuntimeException("Database error"));

    // When
    executionQueueService.addExecution(username, execution);

    // Then
    verify(executionRepository, times(1)).save(execution);
    
    // Queue operation should still work
    List<Execution> polledExecutions = executionQueueService.pollExecutions(username, 10);
    assertThat(polledExecutions).hasSize(1);
    assertThat(polledExecutions.get(0)).isEqualTo(execution);
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
