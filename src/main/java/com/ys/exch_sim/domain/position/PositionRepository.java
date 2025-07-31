package com.ys.exch_sim.domain.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, String> {
    
    /**
     * Find all positions for a specific user
     */
    List<PositionEntity> findByUsername(String username);
    
    /**
     * Find a specific position by username and symbol
     */
    Optional<PositionEntity> findByUsernameAndSymbol(String username, String symbol);
    
    /**
     * Check if a position exists for a specific user and symbol
     */
    boolean existsByUsernameAndSymbol(String username, String symbol);
    
    /**
     * Find all positions for a specific symbol (across all users)
     */
    List<PositionEntity> findBySymbol(String symbol);
    
    /**
     * Delete all positions for a specific user
     */
    void deleteByUsername(String username);
    
    /**
     * Delete a specific position by username and symbol
     */
    void deleteByUsernameAndSymbol(String username, String symbol);
}