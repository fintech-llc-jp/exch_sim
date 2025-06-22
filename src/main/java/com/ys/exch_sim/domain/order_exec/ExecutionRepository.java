package com.ys.exch_sim.domain.order_exec;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, String> {
    
    List<Execution> findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(String username);
    
    List<Execution> findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(String username, String symbol);
    
    @Query("SELECT e FROM Execution e WHERE e.username = :username AND e.isMarketMaker = false AND e.createdAt >= :fromDate ORDER BY e.createdAt DESC")
    List<Execution> findRecentExecutionsForUser(@Param("username") String username, @Param("fromDate") LocalDateTime fromDate);
    
    List<Execution> findBySymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(String symbol);
}