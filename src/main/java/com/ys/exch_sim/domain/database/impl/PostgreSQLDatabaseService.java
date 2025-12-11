package com.ys.exch_sim.domain.database.impl;

import com.ys.exch_sim.domain.database.DatabaseService;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionEntity;
import com.ys.exch_sim.domain.position.PositionRepository;
import com.ys.exch_sim.domain.position.TradeHistory;
import com.ys.exch_sim.domain.position.TradeHistoryEntity;
import com.ys.exch_sim.domain.position.TradeHistoryRepository;
import com.ys.exch_sim.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PostgreSQL実装のDatabaseService
 * Spring Data JPAを使用してデータベース操作を実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.database.type",
    havingValue = "postgresql",
    matchIfMissing = false)
public class PostgreSQLDatabaseService implements DatabaseService {

    private final ExecutionRepository executionRepository;
    private final PositionRepository positionRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void insertExecution(Execution execution) {
        try {
            executionRepository.save(execution);
            log.info("Successfully inserted execution to PostgreSQL: execID={}, username={}, symbol={}, qty={}", 
                execution.getExecID(), execution.getUsername(), execution.getSymbol(), execution.getLastQtyRaw());
        } catch (Exception e) {
            log.error("Error inserting execution to PostgreSQL: {}", execution.getExecID(), e);
            throw new RuntimeException("Failed to insert execution to PostgreSQL", e);
        }
    }

    @Override
    public List<Execution> queryRecentExecutions(LocalDateTime fromTime) {
        try {
            // ExecutionRepositoryにfromTime以降のクエリを追加する必要がある
            // 簡易実装として、全件取得してフィルタリング
            List<Execution> allExecutions = executionRepository.findAll();
            return allExecutions.stream()
                .filter(exec -> exec.getCreatedAt() != null && 
                               (exec.getCreatedAt().isAfter(fromTime) || exec.getCreatedAt().isEqual(fromTime)))
                .filter(exec -> exec.getIsMarketMaker() == null || !exec.getIsMarketMaker())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying recent executions from PostgreSQL", e);
            return List.of();
        }
    }

    @Override
    @Transactional
    public void upsertPosition(Position position) {
        try {
            PositionEntity entity = new PositionEntity(position);
            positionRepository.save(entity);
            log.debug("Successfully upserted position to PostgreSQL: {}_{}", 
                     position.getUsername(), position.getSymbol());
        } catch (Exception e) {
            log.error("Error upserting position to PostgreSQL: {}_{}", 
                     position.getUsername(), position.getSymbol(), e);
            throw new RuntimeException("Failed to upsert position to PostgreSQL", e);
        }
    }

    @Override
    public Position queryPosition(String username, String symbol) {
        try {
            return positionRepository.findByUsernameAndSymbol(username, symbol.toUpperCase())
                .map(PositionEntity::toPosition)
                .orElse(null);
        } catch (Exception e) {
            log.error("Error querying position from PostgreSQL for user: {} symbol: {}", username, symbol, e);
            return null;
        }
    }

    @Override
    public List<Position> queryAllPositions(String username) {
        try {
            List<PositionEntity> entities = positionRepository.findByUsername(username);
            return entities.stream()
                .map(PositionEntity::toPosition)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying all positions from PostgreSQL for user: {}", username, e);
            return List.of();
        }
    }

    @Override
    @Transactional
    public void insertTradeHistory(TradeHistory tradeHistory) {
        try {
            TradeHistoryEntity entity = new TradeHistoryEntity(tradeHistory);
            tradeHistoryRepository.save(entity);
            log.debug("Successfully inserted trade history to PostgreSQL: {}", tradeHistory.getExecID());
        } catch (Exception e) {
            log.error("Error inserting trade history to PostgreSQL: {}", tradeHistory.getExecID(), e);
            throw new RuntimeException("Failed to insert trade history to PostgreSQL", e);
        }
    }

    @Override
    public List<TradeHistory> queryTradeHistory(String username) {
        try {
            List<TradeHistoryEntity> entities = tradeHistoryRepository.findByUsernameOrderByTimestampDesc(username);
            return entities.stream()
                .map(TradeHistoryEntity::toTradeHistory)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying trade history from PostgreSQL for user: {}", username, e);
            return List.of();
        }
    }

    @Override
    public List<TradeHistory> queryTradeHistory(String username, String symbol) {
        try {
            List<TradeHistoryEntity> entities = tradeHistoryRepository
                .findByUsernameAndSymbolOrderByTimestampDesc(username, symbol.toUpperCase());
            return entities.stream()
                .map(TradeHistoryEntity::toTradeHistory)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying trade history from PostgreSQL for user: {} symbol: {}", username, symbol, e);
            return List.of();
        }
    }

    @Override
    public List<TradeHistory> queryTradeHistory(String username, int limit) {
        try {
            List<TradeHistoryEntity> entities = tradeHistoryRepository
                .findByUsernameOrderByTimestampDesc(username);
            return entities.stream()
                .limit(limit)
                .map(TradeHistoryEntity::toTradeHistory)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying trade history from PostgreSQL for user: {} with limit: {}", username, limit, e);
            return List.of();
        }
    }

    @Override
    public List<TradeHistory> queryTradeHistoryByClOrdId(String clOrdId) {
        try {
            List<TradeHistoryEntity> entities = tradeHistoryRepository.findByClOrdId(clOrdId);
            return entities.stream()
                .map(TradeHistoryEntity::toTradeHistory)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error querying trade history from PostgreSQL for clOrdId: {}", clOrdId, e);
            return List.of();
        }
    }

    @Override
    @Transactional
    public void registerUser(String username, String encodedPassword, List<String> roles) {
        try {
            com.ys.exch_sim.domain.user.UserEntity user = new com.ys.exch_sim.domain.user.UserEntity();
            user.setUsername(username);
            user.setPassword(encodedPassword);
            user.setRoles(roles != null ? roles : List.of("USER"));
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            
            userRepository.save(user);
            log.info("Successfully registered user in PostgreSQL: {}", username);
        } catch (Exception e) {
            log.error("Error registering user in PostgreSQL: {}", username, e);
            throw new RuntimeException("Failed to register user in PostgreSQL", e);
        }
    }

    @Override
    public boolean userExists(String username) {
        try {
            return userRepository.existsByUsername(username);
        } catch (Exception e) {
            log.error("Error checking user existence in PostgreSQL: {}", username, e);
            return false;
        }
    }

    @Override
    public DatabaseService.UserEntity loadUser(String username) {
        try {
            return userRepository.findByUsername(username)
                .map(user -> new DatabaseService.UserEntity(
                    user.getUsername(),
                    user.getPassword(),
                    user.getRoles()))
                .orElse(null);
        } catch (Exception e) {
            log.error("Error loading user from PostgreSQL: {}", username, e);
            return null;
        }
    }

    @Override
    public void createTablesIfNotExist() {
        // PostgreSQL + JPAでは、spring.jpa.hibernate.ddl-auto=update で自動的にテーブルが作成される
        // または、Flyway/Liquibaseなどのマイグレーションツールを使用
        log.info("PostgreSQL tables will be created automatically by JPA/Hibernate or migration tool");
    }

    @Override
    public Map<String, Long> calculateVolumeBySymbol(LocalDateTime fromTime) {
        try {
            // ExecutionRepositoryにcalculateVolumeBySymbolAndTimeRangeメソッドがあるが、
            // 全銘柄を取得する必要があるため、簡易実装として空のMapを返す
            // 後で実装を追加する必要がある
            log.warn("calculateVolumeBySymbol is not yet fully implemented for PostgreSQL");
            return new HashMap<>();
        } catch (Exception e) {
            log.error("Error calculating volume by symbol from PostgreSQL", e);
            return new HashMap<>();
        }
    }

    @Override
    public Long calculateTotalVolume(LocalDateTime fromTime) {
        try {
            // ExecutionRepositoryにcalculateTotalVolumeByTimeRangeメソッドがあるが、
            // LocalDateTime toTimeが必要なため、現在時刻を使用
            LocalDateTime toTime = LocalDateTime.now();
            Long volume = executionRepository.calculateTotalVolumeByTimeRange(fromTime, toTime);
            return volume != null ? volume : 0L;
        } catch (Exception e) {
            log.error("Error calculating total volume from PostgreSQL", e);
            return 0L;
        }
    }
}

