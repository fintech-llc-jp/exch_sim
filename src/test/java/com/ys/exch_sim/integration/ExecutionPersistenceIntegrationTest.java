package com.ys.exch_sim.integration;

import com.ys.exch_sim.domain.message.field.*;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExecutionPersistenceIntegrationTest {

    @Autowired
    private ExecutionQueueService executionQueueService;

    @Autowired
    private ExecutionRepository executionRepository;

    @Test
    void testNonMarketMakerExecutionPersistence() {
        // Given
        String username = "testuser";
        Order order = createTestOrder("order1", Side.BUY, username);
        Execution execution = new Execution(order, ExecStatus.FILLED, new Px(createSymbol(), 1000.0), new Qty(createSymbol(), 10));

        // When
        executionQueueService.addExecution(username, execution);

        // Then - Check database persistence
        List<Execution> persistedExecutions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(username);
        assertThat(persistedExecutions).hasSize(1);
        
        Execution persistedExecution = persistedExecutions.get(0);
        assertThat(persistedExecution.getUsername()).isEqualTo(username);
        assertThat(persistedExecution.getSymbol()).isEqualTo("BTCJPY");
        assertThat(persistedExecution.getExecStatus()).isEqualTo(ExecStatus.FILLED);
        // Check the raw value from database (1000.0 * 100 multiplier = 100000L)
        assertThat(persistedExecution.getLastPxRaw()).isEqualTo(100000L);
        // Check the raw value from database, not the reconstructed Qty object
        assertThat(persistedExecution.getLastQtyRaw()).isEqualTo(10L);
        assertThat(persistedExecution.getIsMarketMaker()).isFalse();

        // Check queue functionality still works
        List<Execution> queuedExecutions = executionQueueService.pollExecutions(username, 10);
        assertThat(queuedExecutions).hasSize(1);
    }

    @Test
    void testMarketMakerExecutionNotPersisted() {
        // Given
        String username = "marketmaker";
        Execution marketMakerExecution = createMarketMakerExecution(username);

        // When
        executionQueueService.addExecution(username, marketMakerExecution);

        // Then - Check no database persistence for market maker
        List<Execution> persistedExecutions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(username);
        assertThat(persistedExecutions).isEmpty();

        // But queue functionality should work
        List<Execution> queuedExecutions = executionQueueService.pollExecutions(username, 10);
        assertThat(queuedExecutions).hasSize(1);
    }

    @Test
    void testMultipleUsersExecutionPersistence() {
        // Given
        String user1 = "user1";
        String user2 = "user2";
        
        Order order1 = createTestOrder("order1", Side.BUY, user1);
        Order order2 = createTestOrder("order2", Side.SELL, user2);
        
        Execution execution1 = new Execution(order1, ExecStatus.FILLED, new Px(createSymbol(), 1000.0), new Qty(createSymbol(), 5));
        Execution execution2 = new Execution(order2, ExecStatus.PARTIAL_FILL, new Px(createSymbol(), 999.0), new Qty(createSymbol(), 3));

        // When
        executionQueueService.addExecution(user1, execution1);
        executionQueueService.addExecution(user2, execution2);

        // Then
        List<Execution> user1Executions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(user1);
        List<Execution> user2Executions = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(user2);

        assertThat(user1Executions).hasSize(1);
        assertThat(user2Executions).hasSize(1);
        
        assertThat(user1Executions.get(0).getUsername()).isEqualTo(user1);
        assertThat(user1Executions.get(0).getExecStatus()).isEqualTo(ExecStatus.FILLED);
        
        assertThat(user2Executions.get(0).getUsername()).isEqualTo(user2);
        assertThat(user2Executions.get(0).getExecStatus()).isEqualTo(ExecStatus.PARTIAL_FILL);
    }

    @Test
    void testExecutionHistoryRetrievalBySymbol() {
        // Given
        String user1 = "user1";
        String user2 = "user2";
        
        // Create executions for different symbols
        Order btcOrder1 = createTestOrderWithSymbol("order1", Side.BUY, user1, "BTCJPY");
        Order btcOrder2 = createTestOrderWithSymbol("order2", Side.SELL, user2, "BTCJPY");
        Order ethOrder = createTestOrderWithSymbol("order3", Side.BUY, user1, "ETHJPY");
        
        Execution btcExecution1 = new Execution(btcOrder1, ExecStatus.FILLED, new Px(createSymbol(), 1000.0), new Qty(createSymbol(), 5));
        Execution btcExecution2 = new Execution(btcOrder2, ExecStatus.FILLED, new Px(createSymbol(), 999.0), new Qty(createSymbol(), 3));
        Execution ethExecution = new Execution(ethOrder, ExecStatus.FILLED, new Px(createSymbol(), 2000.0), new Qty(createSymbol(), 2));

        // When
        executionQueueService.addExecution(user1, btcExecution1);
        executionQueueService.addExecution(user2, btcExecution2);
        executionQueueService.addExecution(user1, ethExecution);

        // Then
        List<Execution> btcExecutions = executionRepository.findBySymbolAndIsMarketMakerFalseOrderByCreatedAtDesc("BTCJPY");
        List<Execution> ethExecutions = executionRepository.findBySymbolAndIsMarketMakerFalseOrderByCreatedAtDesc("ETHJPY");

        assertThat(btcExecutions).hasSize(2);
        assertThat(ethExecutions).hasSize(1);
        
        assertThat(btcExecutions).allMatch(exec -> exec.getSymbol().equals("BTCJPY"));
        assertThat(ethExecutions.get(0).getSymbol()).isEqualTo("ETHJPY");
    }

    @Test
    void testRecentExecutionsQuery() {
        // Given
        String username = "testuser";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourAgo = now.minusHours(1);
        
        Order order = createTestOrder("order1", Side.BUY, username);
        Execution execution = new Execution(order, ExecStatus.FILLED, new Px(createSymbol(), 1000.0), new Qty(createSymbol(), 10));

        // When
        executionQueueService.addExecution(username, execution);

        // Then
        List<Execution> recentExecutions = executionRepository.findRecentExecutionsForUser(username, oneHourAgo);
        assertThat(recentExecutions).hasSize(1);
        assertThat(recentExecutions.get(0).getCreatedAt()).isAfter(oneHourAgo);

        // Test with future date - should return empty
        List<Execution> futureExecutions = executionRepository.findRecentExecutionsForUser(username, now.plusHours(1));
        assertThat(futureExecutions).isEmpty();
    }

    private Order createTestOrder(String orderId, Side side, String username) {
        return createTestOrderWithSymbol(orderId, side, username, "BTCJPY");
    }

    private Order createTestOrderWithSymbol(String orderId, Side side, String username, String symbolName) {
        Symbol symbol = new Symbol(symbolName, 100, 1);
        return new Order(
            symbol,
            new Px(symbol, 100.0),
            new Qty(symbol, 10),
            side,
            new ClOrdID(orderId),
            new Timestamp(LocalDateTime.now()),
            OrdType.LIMIT,
            Tif.GTC,
            username
        );
    }

    private Symbol createSymbol() {
        return new Symbol("BTCJPY", 100, 1);
    }

    private Execution createMarketMakerExecution(String username) {
        return new Execution(
            java.util.UUID.randomUUID().toString(),
            "order123",
            username,
            "BTCJPY",
            ExecStatus.FILLED,
            1000L,
            1L,
            null,
            LocalDateTime.now(),
            true // isMarketMaker = true
        );
    }
}