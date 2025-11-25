package com.ys.exch_sim.domain.market_board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketBoardSnapshotRepository extends JpaRepository<MarketBoardSnapshot, Long> {

    /**
     * 指定シンボルの指定時刻範囲のスナップショットを取得
     */
    @Query("SELECT s FROM MarketBoardSnapshot s WHERE s.symbol = :symbol AND s.timestamp >= :fromTime AND s.timestamp <= :toTime ORDER BY s.timestamp ASC")
    List<MarketBoardSnapshot> findBySymbolAndTimestampBetween(
        @Param("symbol") String symbol,
        @Param("fromTime") LocalDateTime fromTime,
        @Param("toTime") LocalDateTime toTime
    );

    /**
     * 指定シンボルの最新のスナップショットを取得
     */
    @Query("SELECT s FROM MarketBoardSnapshot s WHERE s.symbol = :symbol ORDER BY s.timestamp DESC")
    List<MarketBoardSnapshot> findLatestBySymbol(@Param("symbol") String symbol);

    /**
     * 古いスナップショットを削除（データ保持期間を超えたもの）
     */
    void deleteByTimestampBefore(LocalDateTime timestamp);
}

