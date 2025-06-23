package com.ys.exch_sim.domain.order_exec;

import com.ys.exch_sim.domain.message.field.ExecStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ExecutionRepositoryTest {

    @Autowired
    private ExecutionRepository executionRepository;

    @Test
    void testSaveAndFindExecution() {
        // Given
        Execution execution = createTestExecution("user1", "BTCJPY", false);
        
        // When
        Execution saved = executionRepository.save(execution);
        
        // Then
        assertThat(saved.getExecID()).isNotNull();
        assertThat(saved.getUsername()).isEqualTo("user1");
        assertThat(saved.getSymbol()).isEqualTo("BTCJPY");
        assertThat(saved.getIsMarketMaker()).isFalse();
    }

    @Test
    void testFindByUsernameAndIsMarketMakerFalse() {
        // Given
        Execution normalExecution = createTestExecution("user1", "BTCJPY", false);
        Execution marketMakerExecution = createTestExecution("user1", "BTCJPY", true);
        Execution otherUserExecution = createTestExecution("user2", "BTCJPY", false);
        
        executionRepository.save(normalExecution);
        executionRepository.save(marketMakerExecution);
        executionRepository.save(otherUserExecution);
        
        // When
        List<Execution> results = executionRepository.findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc("user1");
        
        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUsername()).isEqualTo("user1");
        assertThat(results.get(0).getIsMarketMaker()).isFalse();
    }

    @Test
    void testFindByUsernameAndSymbolAndIsMarketMakerFalse() {
        // Given
        Execution btcExecution = createTestExecution("user1", "BTCJPY", false);
        Execution ethExecution = createTestExecution("user1", "ETHJPY", false);
        Execution marketMakerExecution = createTestExecution("user1", "BTCJPY", true);
        
        executionRepository.save(btcExecution);
        executionRepository.save(ethExecution);
        executionRepository.save(marketMakerExecution);
        
        // When
        List<Execution> results = executionRepository.findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc("user1", "BTCJPY");
        
        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSymbol()).isEqualTo("BTCJPY");
        assertThat(results.get(0).getIsMarketMaker()).isFalse();
    }

    @Test
    void testFindRecentExecutionsForUser() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yesterday = now.minusDays(1);
        LocalDateTime twoDaysAgo = now.minusDays(2);
        
        Execution recentExecution = createTestExecutionWithDate("user1", "BTCJPY", false, now);
        Execution oldExecution = createTestExecutionWithDate("user1", "BTCJPY", false, twoDaysAgo);
        
        executionRepository.save(recentExecution);
        executionRepository.save(oldExecution);
        
        // When
        List<Execution> results = executionRepository.findRecentExecutionsForUser("user1", yesterday);
        
        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getCreatedAt()).isAfter(yesterday);
    }

    @Test
    void testFindBySymbolAndIsMarketMakerFalse() {
        // Given
        Execution user1Execution = createTestExecution("user1", "BTCJPY", false);
        Execution user2Execution = createTestExecution("user2", "BTCJPY", false);
        Execution marketMakerExecution = createTestExecution("user3", "BTCJPY", true);
        Execution differentSymbolExecution = createTestExecution("user1", "ETHJPY", false);
        
        executionRepository.save(user1Execution);
        executionRepository.save(user2Execution);
        executionRepository.save(marketMakerExecution);
        executionRepository.save(differentSymbolExecution);
        
        // When
        List<Execution> results = executionRepository.findBySymbolAndIsMarketMakerFalseOrderByCreatedAtDesc("BTCJPY");
        
        // Then
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(execution -> execution.getSymbol().equals("BTCJPY"));
        assertThat(results).allMatch(execution -> !execution.getIsMarketMaker());
    }

    private Execution createTestExecution(String username, String symbol, boolean isMarketMaker) {
        return createTestExecutionWithDate(username, symbol, isMarketMaker, LocalDateTime.now());
    }

    private Execution createTestExecutionWithDate(String username, String symbol, boolean isMarketMaker, LocalDateTime createdAt) {
        return new Execution(
            UUID.randomUUID().toString(),
            "order123",
            username,
            symbol,
            ExecStatus.FILLED,
            1000L,
            1L,
            null,
            createdAt,
            isMarketMaker,
            "BUY"
        );
    }
}