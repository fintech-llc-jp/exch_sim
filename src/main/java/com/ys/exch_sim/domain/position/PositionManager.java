package com.ys.exch_sim.domain.position;

import com.ys.exch_sim.domain.bigquery.BigQueryPositionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryTradeHistoryEntity;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.infra.Pair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PositionManager {
    
    private final PositionRepository positionRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final BigQueryService bigQueryService;
    
    // Configuration flags
    @Value("${app.data-migration.memory-cache-enabled:true}")
    private boolean memoryCacheEnabled;
    
    @Value("${app.data-migration.database-persistence-enabled:true}")
    private boolean databasePersistenceEnabled;
    
    @Value("${app.data-migration.bigquery-enabled:false}")
    private boolean bigQueryEnabled;
    
    // ユーザー別・銘柄別のポジション管理（メモリキャッシュ）
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Position>> positionsCache = new ConcurrentHashMap<>();
    
    // 取引履歴（メモリキャッシュ）
    private final List<TradeHistory> tradeHistoriesCache = Collections.synchronizedList(new ArrayList<>());
    
    // Main constructor for Spring
    @Autowired
    public PositionManager(PositionRepository positionRepository, TradeHistoryRepository tradeHistoryRepository, 
                          @Autowired(required = false) BigQueryService bigQueryService) {
        this.positionRepository = positionRepository;
        this.tradeHistoryRepository = tradeHistoryRepository;
        this.bigQueryService = bigQueryService;
    }
    
    // Test-only constructor
    public PositionManager(PositionRepository positionRepository, TradeHistoryRepository tradeHistoryRepository, 
                          boolean memoryCacheEnabled, boolean databasePersistenceEnabled) {
        this.positionRepository = positionRepository;
        this.tradeHistoryRepository = tradeHistoryRepository;
        this.bigQueryService = null;
        this.memoryCacheEnabled = memoryCacheEnabled;
        this.databasePersistenceEnabled = databasePersistenceEnabled;
    }

    @Transactional
    public void processExecution(Execution execution) {
        if (execution == null || execution.getOrder() == null) {
            log.warn("Invalid execution or order is null");
            return;
        }

        String username = execution.getOrder().getUsername();
        String symbol = execution.getOrder().getSymbol().getName();
        Side side = execution.getOrder().getSide();
        long quantity = execution.getLastQty().getLongQty();
        double price = (double) execution.getLastPx().getLongPx() / execution.getLastPx().getSymbol().getPxMultiplier();
        
        // 相手方のユーザー名を取得（約定相手）
        String counterPartyUsername = execution.getCounterPartyUsername();
        
        log.info("Processing execution for user: {}, symbol: {}, side: {}, qty: {}, price: {}", 
                username, symbol, side, quantity, price);

        try {
            // ポジション更新
            Position position = getOrCreatePosition(username, symbol);
            
            if (side == Side.BUY) {
                position.addBuyTrade(quantity, price);
            } else if (side == Side.SELL) {
                position.addSellTrade(quantity, price);
            }

            // データベース永続化
            if (databasePersistenceEnabled) {
                savePositionToDatabase(position);
            }

            // 取引履歴を記録
            TradeHistory tradeHistory = new TradeHistory(
                execution.getExecID().getId(),
                username,
                symbol,
                side.toString(),
                quantity,
                price,
                counterPartyUsername,
                execution.getOrder().getClOrdID().getId()
            );
            
            // メモリキャッシュに追加
            if (memoryCacheEnabled) {
                tradeHistoriesCache.add(tradeHistory);
            }
            
            // データベース永続化
            if (databasePersistenceEnabled) {
                saveTradeHistoryToDatabase(tradeHistory);
            }

            log.info("Position updated for user: {}, symbol: {}, netQty: {}, realizedPnL: {}", 
                    username, symbol, position.getNetQty(), position.getRealizedPnL());

        } catch (Exception e) {
            log.error("Error processing execution for user: " + username, e);
        }
    }

    public Position getPosition(String username, String symbol) {
        if (memoryCacheEnabled) {
            ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
            if (userPositions != null) {
                return userPositions.get(symbol.toUpperCase());
            }
        }
        
        if (databasePersistenceEnabled) {
            return positionRepository.findByUsernameAndSymbol(username, symbol.toUpperCase())
                    .map(PositionEntity::toPosition)
                    .orElse(null);
        }
        
        return null;
    }

    public List<Position> getAllPositions(String username) {
        List<Position> positions = new ArrayList<>();
        
        if (memoryCacheEnabled) {
            ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
            if (userPositions != null) {
                positions.addAll(userPositions.values());
            }
        }
        
        if (databasePersistenceEnabled && positions.isEmpty()) {
            positions = positionRepository.findByUsername(username)
                    .stream()
                    .map(PositionEntity::toPosition)
                    .collect(Collectors.toList());
        }
        
        return positions;
    }

    public List<TradeHistory> getTradeHistory(String username) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()))
                    .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                    .collect(Collectors.toList());
        }
        
        if (databasePersistenceEnabled) {
            return tradeHistoryRepository.findByUsernameOrderByTimestampDesc(username)
                    .stream()
                    .map(TradeHistoryEntity::toTradeHistory)
                    .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }

    public List<TradeHistory> getTradeHistory(String username, String symbol) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()) && 
                                     symbol.equalsIgnoreCase(history.getSymbol()))
                    .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                    .collect(Collectors.toList());
        }
        
        if (databasePersistenceEnabled) {
            return tradeHistoryRepository.findByUsernameAndSymbolOrderByTimestampDesc(username, symbol)
                    .stream()
                    .map(TradeHistoryEntity::toTradeHistory)
                    .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }

    public List<TradeHistory> getTradeHistory(String username, int limit) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()))
                    .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                    .limit(limit)
                    .collect(Collectors.toList());
        }
        
        if (databasePersistenceEnabled) {
            return tradeHistoryRepository.findByUsernameOrderByTimestampDesc(username, 
                    org.springframework.data.domain.PageRequest.of(0, limit))
                    .stream()
                    .map(TradeHistoryEntity::toTradeHistory)
                    .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }

    public double getTotalRealizedPnL(String username) {
        if (memoryCacheEnabled) {
            ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
            if (userPositions != null) {
                return userPositions.values().stream()
                        .mapToDouble(Position::getRealizedPnL)
                        .sum();
            }
        }
        
        if (databasePersistenceEnabled) {
            return positionRepository.findByUsername(username)
                    .stream()
                    .mapToDouble(PositionEntity::getRealizedPnL)
                    .sum();
        }
        
        return 0.0;
    }

    public double getTotalUnrealizedPnL(String username, Map<String, Double> currentPrices) {
        if (memoryCacheEnabled) {
            ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
            if (userPositions != null) {
                return userPositions.values().stream()
                        .mapToDouble(position -> {
                            Double currentPrice = currentPrices.get(position.getSymbol());
                            return currentPrice != null ? position.getUnrealizedPnL(currentPrice) : 0.0;
                        })
                        .sum();
            }
        }
        
        if (databasePersistenceEnabled) {
            return positionRepository.findByUsername(username)
                    .stream()
                    .map(PositionEntity::toPosition)
                    .mapToDouble(position -> {
                        Double currentPrice = currentPrices.get(position.getSymbol());
                        return currentPrice != null ? position.getUnrealizedPnL(currentPrice) : 0.0;
                    })
                    .sum();
        }
        
        return 0.0;
    }

    public double getTotalPnL(String username, Map<String, Double> currentPrices) {
        return getTotalRealizedPnL(username) + getTotalUnrealizedPnL(username, currentPrices);
    }

    private Position getOrCreatePosition(String username, String symbol) {
        if (memoryCacheEnabled) {
            return positionsCache.computeIfAbsent(username, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(symbol.toUpperCase(), k -> {
                        log.info("Creating new position for user: {}, symbol: {}", username, symbol);
                        return new Position(username, symbol.toUpperCase());
                    });
        }
        
        if (databasePersistenceEnabled) {
            return positionRepository.findByUsernameAndSymbol(username, symbol.toUpperCase())
                    .map(PositionEntity::toPosition)
                    .orElseGet(() -> {
                        log.info("Creating new position for user: {}, symbol: {}", username, symbol);
                        return new Position(username, symbol.toUpperCase());
                    });
        }
        
        // Fallback
        log.info("Creating new position for user: {}, symbol: {}", username, symbol);
        return new Position(username, symbol.toUpperCase());
    }

    // 統計情報取得用メソッド
    public int getTotalTradeCount(String username) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return (int) tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()))
                    .count();
        }
        
        if (databasePersistenceEnabled) {
            Long count = tradeHistoryRepository.countByUsername(username);
            return count != null ? count.intValue() : 0;
        }
        
        return 0;
    }

    public double getTotalTradingVolume(String username) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()))
                    .mapToDouble(TradeHistory::getAmount)
                    .sum();
        }
        
        if (databasePersistenceEnabled) {
            Double amount = tradeHistoryRepository.sumAmountByUsername(username);
            return amount != null ? amount : 0.0;
        }
        
        return 0.0;
    }

    public Map<String, Long> getSymbolTradeCounts(String username) {
        if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
            return tradeHistoriesCache.stream()
                    .filter(history -> username.equals(history.getUsername()))
                    .collect(Collectors.groupingBy(
                        TradeHistory::getSymbol,
                        Collectors.counting()
                    ));
        }
        
        if (databasePersistenceEnabled) {
            return tradeHistoryRepository.countByUsernameGroupBySymbol(username)
                    .stream()
                    .collect(Collectors.toMap(
                        result -> (String) result[0],
                        result -> (Long) result[1]
                    ));
        }
        
        return new HashMap<>();
    }

    // テスト用メソッド
    public void clearAllData() {
        if (memoryCacheEnabled) {
            positionsCache.clear();
            tradeHistoriesCache.clear();
        }
        
        if (databasePersistenceEnabled) {
            positionRepository.deleteAll();
            tradeHistoryRepository.deleteAll();
        }
        
        log.info("All position and trade history data cleared");
    }
    
    // Helper methods for database operations
    private void savePositionToDatabase(Position position) {
        try {
            PositionEntity entity = new PositionEntity(position);
            positionRepository.save(entity);
            log.debug("Position saved to database: {}", entity.getId());
            
            // BigQueryにも保存
            if (bigQueryEnabled && bigQueryService != null) {
                savePositionToBigQuery(position);
            }
        } catch (Exception e) {
            log.error("Error saving position to database: " + position.getUsername() + "_" + position.getSymbol(), e);
        }
    }
    
    private void saveTradeHistoryToDatabase(TradeHistory tradeHistory) {
        try {
            TradeHistoryEntity entity = new TradeHistoryEntity(tradeHistory);
            tradeHistoryRepository.save(entity);
            log.debug("Trade history saved to database: {}", entity.getExecId());
            
            // BigQueryにも保存
            if (bigQueryEnabled && bigQueryService != null) {
                saveTradeHistoryToBigQuery(tradeHistory);
            }
        } catch (Exception e) {
            log.error("Error saving trade history to database: " + tradeHistory.getExecID(), e);
        }
    }
    
    // BigQuery保存メソッド
    private void savePositionToBigQuery(Position position) {
        try {
            BigQueryPositionEntity bigQueryEntity = new BigQueryPositionEntity(position);
            bigQueryService.insertPosition(bigQueryEntity);
            log.debug("Position saved to BigQuery: {}_{}", position.getUsername(), position.getSymbol());
        } catch (Exception e) {
            log.error("Error saving position to BigQuery: " + position.getUsername() + "_" + position.getSymbol(), e);
        }
    }
    
    private void saveTradeHistoryToBigQuery(TradeHistory tradeHistory) {
        try {
            BigQueryTradeHistoryEntity bigQueryEntity = new BigQueryTradeHistoryEntity(tradeHistory);
            bigQueryService.insertTradeHistory(bigQueryEntity);
            log.debug("Trade history saved to BigQuery: {}", tradeHistory.getExecID());
        } catch (Exception e) {
            log.error("Error saving trade history to BigQuery: " + tradeHistory.getExecID(), e);
        }
    }
}