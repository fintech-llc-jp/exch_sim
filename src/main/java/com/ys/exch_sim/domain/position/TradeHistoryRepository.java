package com.ys.exch_sim.domain.position;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistoryEntity, String> {
    
    /**
     * Find all trade history for a specific user, ordered by timestamp descending
     */
    List<TradeHistoryEntity> findByUsernameOrderByTimestampDesc(String username);
    
    /**
     * Find trade history for a specific user and symbol, ordered by timestamp descending
     */
    List<TradeHistoryEntity> findByUsernameAndSymbolOrderByTimestampDesc(String username, String symbol);
    
    /**
     * Find paginated trade history for a specific user
     */
    Page<TradeHistoryEntity> findByUsernameOrderByTimestampDesc(String username, Pageable pageable);
    
    /**
     * Find paginated trade history for a specific user and symbol
     */
    Page<TradeHistoryEntity> findByUsernameAndSymbolOrderByTimestampDesc(String username, String symbol, Pageable pageable);
    
    /**
     * Find trade history by timestamp range
     */
    List<TradeHistoryEntity> findByUsernameAndTimestampBetweenOrderByTimestampDesc(
        String username, LocalDateTime start, LocalDateTime end);
    
    /**
     * Find trade history by symbol and timestamp range
     */
    List<TradeHistoryEntity> findByUsernameAndSymbolAndTimestampBetweenOrderByTimestampDesc(
        String username, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * Find trade history by client order ID (for FIFO P/L calculation)
     */
    List<TradeHistoryEntity> findByClOrdId(String clOrdId);

    /**
     * Get total trade count for a user
     */
    @Query("SELECT COUNT(t) FROM TradeHistoryEntity t WHERE t.username = :username")
    Long countByUsername(@Param("username") String username);
    
    /**
     * Get total trading volume for a user
     */
    @Query("SELECT SUM(t.amount) FROM TradeHistoryEntity t WHERE t.username = :username")
    Double sumAmountByUsername(@Param("username") String username);
    
    /**
     * Get trade count by symbol for a user
     */
    @Query("SELECT t.symbol, COUNT(t) FROM TradeHistoryEntity t WHERE t.username = :username GROUP BY t.symbol")
    List<Object[]> countByUsernameGroupBySymbol(@Param("username") String username);
    
    /**
     * Find all trade history for a specific symbol (across all users)
     */
    List<TradeHistoryEntity> findBySymbolOrderByTimestampDesc(String symbol);
    
    /**
     * Delete all trade history for a specific user
     */
    void deleteByUsername(String username);
}