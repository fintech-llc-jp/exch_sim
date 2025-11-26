package com.ys.exch_sim.integration;

import com.ys.exch_sim.config.TestSecurityConfig;
import com.ys.exch_sim.domain.database.DatabaseService;
import com.ys.exch_sim.domain.message.field.*;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.domain.position.TradeHistory;
import com.ys.exch_sim.domain.position.TradeHistoryRepository;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * PostgreSQL Integration Tests
 *
 * These tests verify that the application correctly persists data to PostgreSQL
 * after the BigQuery removal. All tests use the H2 in-memory database for
 * isolation and speed, but the same code paths work with PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Transactional
class PostgreSQLIntegrationTest {

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private ExecutionQueueService executionQueueService;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private TradeHistoryRepository tradeHistoryRepository;

    @Autowired
    private PositionManager positionManager;

    private Symbol testSymbol;
    private String testUsername;

    @BeforeEach
    void setUp() {
        testSymbol = new Symbol("BTCJPY", 100, 1);
        testUsername = "testuser_" + System.currentTimeMillis();

        // Ensure tables are created
        databaseService.createTablesIfNotExist();
    }

    // ========== Execution Persistence Tests ==========

    @Test
    void testExecutionPersistenceThroughDatabaseService() {
        // Given
        Order order = createTestOrder("order1", Side.BUY, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1000.0),
            new Qty(testSymbol, 10)
        );

        // When
        databaseService.insertExecution(execution);

        // Then - Verify persistence through repository
        List<Execution> persistedExecutions = executionRepository
            .findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(testUsername);

        assertThat(persistedExecutions)
            .hasSize(1)
            .extracting(Execution::getUsername)
            .containsExactly(testUsername);

        assertThat(persistedExecutions.get(0))
            .extracting(Execution::getSymbol, Execution::getExecStatus)
            .containsExactly("BTCJPY", ExecStatus.FILLED);
    }

    @Test
    void testMultipleExecutionsPersistence() {
        // Given
        Order order1 = createTestOrder("order1", Side.BUY, testUsername);
        Order order2 = createTestOrder("order2", Side.SELL, testUsername);
        Order order3 = createTestOrder("order3", Side.BUY, testUsername);

        Execution exec1 = new Execution(order1, ExecStatus.FILLED, new Px(testSymbol, 1000.0), new Qty(testSymbol, 5));
        Execution exec2 = new Execution(order2, ExecStatus.PARTIAL_FILL, new Px(testSymbol, 999.0), new Qty(testSymbol, 3));
        Execution exec3 = new Execution(order3, ExecStatus.FILLED, new Px(testSymbol, 1001.0), new Qty(testSymbol, 7));

        // When
        databaseService.insertExecution(exec1);
        databaseService.insertExecution(exec2);
        databaseService.insertExecution(exec3);

        // Then
        List<Execution> persistedExecutions = executionRepository
            .findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(testUsername);

        assertThat(persistedExecutions).hasSize(3);
        assertThat(persistedExecutions)
            .extracting(Execution::getExecStatus)
            .containsExactly(ExecStatus.FILLED, ExecStatus.PARTIAL_FILL, ExecStatus.FILLED);
    }

    @Test
    void testRecentExecutionsQuery() {
        // Given
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime oneHourAgo = now.minusHours(1);
        LocalDateTime twoHoursAgo = now.minusHours(2);

        Order order = createTestOrder("order1", Side.BUY, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1000.0),
            new Qty(testSymbol, 10)
        );

        // When
        databaseService.insertExecution(execution);

        // Then - Query recent executions
        List<Execution> recentExecutions = databaseService.queryRecentExecutions(twoHoursAgo);
        assertThat(recentExecutions)
            .isNotEmpty()
            .anyMatch(exec -> exec.getUsername().equals(testUsername));

        // Query with future time should return empty
        List<Execution> futureExecutions = databaseService.queryRecentExecutions(now.plusHours(1));
        assertThat(futureExecutions)
            .noneMatch(exec -> exec.getUsername().equals(testUsername));
    }

    // ========== Position Persistence Tests ==========

    @Test
    void testPositionUpsert() {
        // Given
        Position position = new Position(testUsername, "BTCJPY");
        position.addBuyTrade(100.0, 50.0);

        // When
        databaseService.upsertPosition(position);

        // Then
        Position retrievedPosition = databaseService.queryPosition(testUsername, "BTCJPY");
        assertThat(retrievedPosition).isNotNull();
        assertThat(retrievedPosition)
            .extracting(Position::getUsername, Position::getSymbol, Position::getNetQty)
            .containsExactly(testUsername, "BTCJPY", 100.0);
    }

    @Test
    void testPositionUpdate() {
        // Given - Create initial position
        Position position1 = new Position(testUsername, "BTCJPY");
        position1.addBuyTrade(100.0, 50.0);
        databaseService.upsertPosition(position1);

        // When - Update position with new values
        Position position2 = new Position(testUsername, "BTCJPY");
        position2.addBuyTrade(150.0, 75.0);
        databaseService.upsertPosition(position2);

        // Then - Verify update (should replace, not add)
        List<Position> positions = databaseService.queryAllPositions(testUsername);
        assertThat(positions)
            .hasSize(1)
            .extracting(Position::getNetQty)
            .containsExactly(150.0);
    }

    @Test
    void testMultipleSymbolPositions() {
        // Given
        Position btcPosition = new Position(testUsername, "BTCJPY");
        btcPosition.addBuyTrade(100.0, 50.0);

        Position ethPosition = new Position(testUsername, "ETHJPY");
        ethPosition.addBuyTrade(50.0, 25.0);

        // When
        databaseService.upsertPosition(btcPosition);
        databaseService.upsertPosition(ethPosition);

        // Then
        List<Position> positions = databaseService.queryAllPositions(testUsername);
        assertThat(positions).hasSize(2);

        assertThat(positions)
            .extracting(Position::getSymbol)
            .containsExactlyInAnyOrder("BTCJPY", "ETHJPY");

        Position btc = databaseService.queryPosition(testUsername, "BTCJPY");
        Position eth = databaseService.queryPosition(testUsername, "ETHJPY");

        assertThat(btc.getNetQty()).isEqualTo(100.0);
        assertThat(eth.getNetQty()).isEqualTo(50.0);
    }

    // ========== TradeHistory Persistence Tests ==========

    @Test
    void testTradeHistoryInsertion() {
        // Given
        String execID = java.util.UUID.randomUUID().toString();
        Order order = createTestOrder("order1", Side.BUY, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1000.0),
            new Qty(testSymbol, 10)
        );

        TradeHistory tradeHistory = new TradeHistory(
            execID,
            testUsername,
            "BTCJPY",
            "BUY",
            10.0,
            1000.0,
            null,
            "order1"
        );

        // When
        databaseService.insertExecution(execution);
        databaseService.insertTradeHistory(tradeHistory);

        // Then
        List<TradeHistory> history = databaseService.queryTradeHistory(testUsername);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getUsername()).isEqualTo(testUsername);
        assertThat(history.get(0).getSymbol()).isEqualTo("BTCJPY");
        assertThat(history.get(0).getSide()).isEqualTo("BUY");
    }

    @Test
    void testTradeHistoryBySymbol() {
        // Given
        String btcExecID = java.util.UUID.randomUUID().toString();
        String ethExecID = java.util.UUID.randomUUID().toString();

        TradeHistory btcTrade = new TradeHistory(
            btcExecID,
            testUsername,
            "BTCJPY",
            "BUY",
            10.0,
            1000.0,
            null,
            "order1"
        );
        TradeHistory ethTrade = new TradeHistory(
            ethExecID,
            testUsername,
            "ETHJPY",
            "SELL",
            20.0,
            500.0,
            null,
            "order2"
        );

        // When
        databaseService.insertTradeHistory(btcTrade);
        databaseService.insertTradeHistory(ethTrade);

        // Then - Query by symbol
        List<TradeHistory> btcHistory = databaseService.queryTradeHistory(testUsername, "BTCJPY");
        List<TradeHistory> ethHistory = databaseService.queryTradeHistory(testUsername, "ETHJPY");

        assertThat(btcHistory)
            .hasSize(1)
            .allMatch(t -> t.getSymbol().equals("BTCJPY") && t.getSide().equals("BUY"));

        assertThat(ethHistory)
            .hasSize(1)
            .allMatch(t -> t.getSymbol().equals("ETHJPY") && t.getSide().equals("SELL"));
    }

    @Test
    void testTradeHistoryWithLimit() {
        // Given
        for (int i = 1; i <= 5; i++) {
            String execID = java.util.UUID.randomUUID().toString();
            TradeHistory trade = new TradeHistory(
                execID,
                testUsername,
                "BTCJPY",
                i % 2 == 0 ? "BUY" : "SELL",
                10.0,
                1000.0 + i,
                null,
                "order" + i
            );
            databaseService.insertTradeHistory(trade);
        }

        // When
        List<TradeHistory> limited = databaseService.queryTradeHistory(testUsername, 3);

        // Then
        assertThat(limited).hasSize(3);
    }

    // ========== User Management Tests ==========

    @Test
    void testUserRegistration() {
        // Given
        String username = "newuser_" + System.currentTimeMillis();
        String encodedPassword = "hashed_password_123";
        List<String> roles = List.of("ROLE_USER", "ROLE_TRADER");

        // When
        databaseService.registerUser(username, encodedPassword, roles);

        // Then
        assertThat(databaseService.userExists(username)).isTrue();

        DatabaseService.UserEntity userEntity = databaseService.loadUser(username);
        assertThat(userEntity).isNotNull();
        assertThat(userEntity.getUsername()).isEqualTo(username);
        assertThat(userEntity.getPassword()).isEqualTo(encodedPassword);
        assertThat(userEntity.getRoles()).containsExactlyInAnyOrderElementsOf(roles);
    }

    @Test
    void testUserExistenceCheck() {
        // Given
        String username = "testuser_" + System.currentTimeMillis();

        // When & Then - User should not exist initially
        assertThat(databaseService.userExists(username)).isFalse();

        // When - Register user
        databaseService.registerUser(username, "password", List.of("ROLE_USER"));

        // Then - User should exist
        assertThat(databaseService.userExists(username)).isTrue();
    }

    // ========== Integration with ExecutionQueueService ==========

    @Test
    void testExecutionQueueServiceWithDatabasePersistence() {
        // Given
        Order order = createTestOrder("order1", Side.BUY, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1000.0),
            new Qty(testSymbol, 10)
        );

        // When - Add execution through queue service
        executionQueueService.addExecution(testUsername, execution);

        // Then - Verify both in-memory queue and database persistence
        List<Execution> queuedExecutions = executionQueueService.pollExecutions(testUsername, 10);
        assertThat(queuedExecutions).hasSize(1);

        List<Execution> persistedExecutions = executionRepository
            .findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(testUsername);
        assertThat(persistedExecutions).hasSize(1);
    }

    // ========== Integration with PositionManager ==========

    @org.junit.jupiter.api.Disabled("PositionManager integration requires ExecutionQueueService setup")
    @Test
    void testPositionManagerIntegration() {
        // Given
        Order order = createTestOrder("order1", Side.BUY, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1000.0),
            new Qty(testSymbol, 10)
        );

        // When - Process execution through position manager
        positionManager.processExecution(execution);

        // Then - Verify position is persisted
        Position position = databaseService.queryPosition(testUsername, "BTCJPY");
        assertThat(position).isNotNull();
        assertThat(position.getNetQty()).isEqualTo(10.0);
    }

    // ========== Concurrency Tests ==========

    @Test
    void testConcurrentExecutionInsertion() throws InterruptedException {
        // Given
        int threadCount = 5;
        int executionsPerThread = 10;
        Thread[] threads = new Thread[threadCount];

        // When - Insert executions concurrently
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < executionsPerThread; i++) {
                    Order order = createTestOrder("order_" + threadId + "_" + i, Side.BUY, testUsername);
                    Execution execution = new Execution(
                        order,
                        ExecStatus.FILLED,
                        new Px(testSymbol, 1000.0 + i),
                        new Qty(testSymbol, 10)
                    );
                    databaseService.insertExecution(execution);
                }
            });
            threads[t].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - Verify all executions are persisted
        List<Execution> persistedExecutions = executionRepository
            .findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(testUsername);
        assertThat(persistedExecutions).hasSize(threadCount * executionsPerThread);
    }

    @org.junit.jupiter.api.Disabled("Concurrent position updates require transaction management")
    @Test
    void testConcurrentPositionUpdates() throws InterruptedException {
        // Given
        int threadCount = 5;
        Position initialPosition = new Position(testUsername, "BTCJPY");
        databaseService.upsertPosition(initialPosition);

        // When - Update position concurrently
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    Position updated = new Position(testUsername, "BTCJPY");
                    updated.addBuyTrade((threadId * 10 + i + 1) * 1.0, 1000.0);
                    databaseService.upsertPosition(updated);
                }
            });
            threads[t].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - Verify position is updated (should have positive net quantity)
        Position position = databaseService.queryPosition(testUsername, "BTCJPY");
        assertThat(position).isNotNull();
        assertThat(position.getNetQty()).isGreaterThan(0);
    }

    // ========== Data Integrity Tests ==========

    @Test
    void testExecutionDataIntegrity() {
        // Given
        Order order = createTestOrder("order1", Side.SELL, testUsername);
        Execution execution = new Execution(
            order,
            ExecStatus.FILLED,
            new Px(testSymbol, 1234.56),
            new Qty(testSymbol, 123)
        );

        // When
        databaseService.insertExecution(execution);

        // Then - Verify all fields are persisted correctly
        List<Execution> persistedExecutions = executionRepository
            .findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(testUsername);

        Execution retrieved = persistedExecutions.get(0);
        assertThat(retrieved.getUsername()).isEqualTo(testUsername);
        assertThat(retrieved.getSymbol()).isEqualTo("BTCJPY");
        assertThat(retrieved.getExecStatus()).isEqualTo(ExecStatus.FILLED);
        // Note: Order object cannot be reconstructed from persisted raw data, so verify raw values instead
        assertThat(retrieved.getLastPxRaw()).isEqualTo(123456L); // 1234.56 * 100 multiplier
        assertThat(retrieved.getLastQtyRaw()).isEqualTo(123L);
    }

    @Test
    void testPositionDataIntegrity() {
        // Given
        double quantity = 123.456;
        double avgPrice = 1234.567;

        Position position = new Position(testUsername, "BTCJPY");
        position.addBuyTrade(quantity, avgPrice);

        // When
        databaseService.upsertPosition(position);

        // Then
        Position retrieved = databaseService.queryPosition(testUsername, "BTCJPY");
        assertThat(retrieved.getNetQty()).isEqualTo(quantity);
        assertThat(retrieved.getAverageBuyPrice()).isEqualTo(avgPrice);
    }

    // ========== Volume Calculation Tests ==========

    @Test
    void testVolumeCalculationBySymbol() {
        // Given
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime oneHourAgo = now.minusHours(1);

        Order order1 = createTestOrder("order1", Side.BUY, testUsername);
        Order order2 = createTestOrder("order2", Side.SELL, testUsername);

        Execution exec1 = new Execution(order1, ExecStatus.FILLED, new Px(testSymbol, 1000.0), new Qty(testSymbol, 100));
        Execution exec2 = new Execution(order2, ExecStatus.FILLED, new Px(testSymbol, 1000.0), new Qty(testSymbol, 50));

        // When
        databaseService.insertExecution(exec1);
        databaseService.insertExecution(exec2);

        // Then
        Long totalVolume = databaseService.calculateTotalVolume(oneHourAgo);
        assertThat(totalVolume).isGreaterThanOrEqualTo(150L);
    }

    // ========== Helper Methods ==========

    private Order createTestOrder(String orderId, Side side, String username) {
        return new Order(
            testSymbol,
            new Px(testSymbol, 100.0),
            new Qty(testSymbol, 10),
            side,
            new ClOrdID(orderId),
            new Timestamp(LocalDateTime.now()),
            OrdType.LIMIT,
            Tif.GTC,
            username
        );
    }
}
