package com.ys.exch_sim.domain.order_exec;

import com.ys.exch_sim.domain.message.field.ExecStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, String> {

  List<Execution> findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(String username);

  List<Execution> findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(
      String username, String symbol);

  @Query(
      "SELECT e FROM Execution e WHERE e.username = :username AND e.isMarketMaker = false AND"
          + " (e.execStatus = :filledStatus OR e.execStatus = :partialFillStatus) AND e.createdAt"
          + " >= :fromDate ORDER BY e.createdAt DESC")
  List<Execution> findRecentExecutionsForUser(
      @Param("username") String username,
      @Param("filledStatus") ExecStatus filledStatus,
      @Param("partialFillStatus") ExecStatus partialFillStatus,
      @Param("fromDate") LocalDateTime fromDate);

  List<Execution> findBySymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(String symbol);

  // Pagination methods
  Page<Execution> findByUsernameAndIsMarketMakerFalseOrderByCreatedAtDesc(
      String username, Pageable pageable);

  Page<Execution> findByUsernameAndSymbolAndIsMarketMakerFalseOrderByCreatedAtDesc(
      String username, String symbol, Pageable pageable);

  // Pagination methods with execution status filter (FILLED and PARTIAL_FILL only)
  @Query(
      "SELECT e FROM Execution e WHERE e.username = :username AND e.isMarketMaker = false AND"
          + " (e.execStatus = :filledStatus OR e.execStatus = :partialFillStatus) ORDER BY"
          + " e.createdAt DESC")
  Page<Execution> findFilledExecutionsByUsernameOrderByCreatedAtDesc(
      @Param("username") String username,
      @Param("filledStatus") ExecStatus filledStatus,
      @Param("partialFillStatus") ExecStatus partialFillStatus,
      Pageable pageable);

  @Query(
      "SELECT e FROM Execution e WHERE e.username = :username AND e.symbol = :symbol AND"
          + " e.isMarketMaker = false AND (e.execStatus = :filledStatus OR e.execStatus ="
          + " :partialFillStatus) ORDER BY e.createdAt DESC")
  Page<Execution> findFilledExecutionsByUsernameAndSymbolOrderByCreatedAtDesc(
      @Param("username") String username,
      @Param("symbol") String symbol,
      @Param("filledStatus") ExecStatus filledStatus,
      @Param("partialFillStatus") ExecStatus partialFillStatus,
      Pageable pageable);

  // Global execution history methods (all users)
  @Query(
      "SELECT e FROM Execution e WHERE e.isMarketMaker = false AND (e.execStatus = :filledStatus OR"
          + " e.execStatus = :partialFillStatus) ORDER BY e.createdAt DESC")
  Page<Execution> findAllFilledExecutionsOrderByCreatedAtDesc(
      @Param("filledStatus") ExecStatus filledStatus,
      @Param("partialFillStatus") ExecStatus partialFillStatus,
      Pageable pageable);

  @Query(
      "SELECT e FROM Execution e WHERE e.symbol = :symbol AND e.isMarketMaker = false AND"
          + " (e.execStatus = :filledStatus OR e.execStatus = :partialFillStatus) ORDER BY"
          + " e.createdAt DESC")
  Page<Execution> findAllFilledExecutionsBySymbolOrderByCreatedAtDesc(
      @Param("symbol") String symbol,
      @Param("filledStatus") ExecStatus filledStatus,
      @Param("partialFillStatus") ExecStatus partialFillStatus,
      Pageable pageable);

  // Time-based volume calculation methods with optimized indexes
  @Query(
      value =
          "SELECT COALESCE(SUM(e.last_qty), 0) FROM executions e WHERE e.symbol = :symbol AND"
              + " e.is_market_maker = false AND e.exec_status IN ('FILLED', 'PARTIAL_FILL') AND"
              + " e.created_at >= :fromTime AND e.created_at <= :toTime",
      nativeQuery = true)
  Long calculateVolumeBySymbolAndTimeRange(
      @Param("symbol") String symbol,
      @Param("fromTime") LocalDateTime fromTime,
      @Param("toTime") LocalDateTime toTime);

  @Query(
      value =
          "SELECT COALESCE(SUM(e.last_qty), 0) FROM executions e WHERE e.is_market_maker = false"
              + " AND e.exec_status IN ('FILLED', 'PARTIAL_FILL') AND e.created_at >= :fromTime AND"
              + " e.created_at <= :toTime",
      nativeQuery = true)
  Long calculateTotalVolumeByTimeRange(
      @Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

  @Query(
      value =
          "SELECT COUNT(*) FROM executions e WHERE e.symbol = :symbol AND e.is_market_maker = false"
              + " AND e.exec_status IN ('FILLED', 'PARTIAL_FILL') AND e.created_at >= :fromTime AND"
              + " e.created_at <= :toTime",
      nativeQuery = true)
  Long countExecutionsBySymbolAndTimeRange(
      @Param("symbol") String symbol,
      @Param("fromTime") LocalDateTime fromTime,
      @Param("toTime") LocalDateTime toTime);

  @Query(
      value =
          "SELECT COUNT(*) FROM executions e WHERE e.is_market_maker = false AND e.exec_status IN"
              + " ('FILLED', 'PARTIAL_FILL') AND e.created_at >= :fromTime AND e.created_at <="
              + " :toTime",
      nativeQuery = true)
  Long countTotalExecutionsByTimeRange(
      @Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

  /**
   * 古い実行記録を削除（データ保持期間を超えたもの）
   * @return 削除された件数
   */
  long deleteByCreatedAtBefore(LocalDateTime timestamp);
}
