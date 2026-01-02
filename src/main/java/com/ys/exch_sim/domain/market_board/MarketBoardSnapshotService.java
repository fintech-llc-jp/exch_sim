package com.ys.exch_sim.domain.market_board;

import com.ys.exch_sim.domain.dto.MarketBoardResponse;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.service.OrderService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 板データのスナップショットを1秒ごとに記録するサービス PostgreSQLモードの時のみ有効 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.database.type",
    havingValue = "postgresql",
    matchIfMissing = false)
public class MarketBoardSnapshotService {

  private final OrderService orderService;
  private final MarketBoardSnapshotRepository repository;
  private final PostgreSQLWriter postgreSQLWriter;
  private final ExecutionRepository executionRepository;

  @Value("${app.market-board.snapshot.retention-hours:24}")
  private int retentionHours;

  @Value("${app.market-board.snapshot.interval-ms:2000}")
  private long snapshotIntervalMs;

  @Value("${app.market-board.snapshot.max-levels:10}")
  private int maxLevels;

  @Value("${app.market-board.snapshot.enabled:true}")
  private boolean snapshotEnabled;

  /** 指定間隔ごとに全シンボルの板データを記録 非同期キューに追加してすぐにリターン（ブロッキングしない） */
  @Scheduled(fixedRateString = "${app.market-board.snapshot.interval-ms:2000}") // デフォルト2秒
  public void captureMarketBoardSnapshots() {
    if (!snapshotEnabled) {
      return; // スナップショット機能が無効化されている場合は何もしない
    }
    try {
      List<String> symbols = orderService.getAvailableSymbols();

      if (symbols.isEmpty()) {
        log.debug("No symbols available for snapshot capture");
        return;
      }

      LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
      int queuedCount = 0;

      for (String symbol : symbols) {
        try {
          MarketBoardResponse board =
              orderService.getMarketBoard(symbol, maxLevels); // 設定可能な最大レベル数まで記録

          if (board == null || (board.getBids().isEmpty() && board.getAsks().isEmpty())) {
            log.debug("Skipping empty board for symbol: {}", symbol);
            continue;
          }

          MarketBoardSnapshot snapshot = new MarketBoardSnapshot();
          snapshot.setSymbol(symbol);
          snapshot.setTimestamp(now);
          snapshot.setBids(new ArrayList<>());
          snapshot.setAsks(new ArrayList<>());

          // Bidsを記録
          if (board.getBids() != null) {
            for (int i = 0; i < board.getBids().size(); i++) {
              var bid = board.getBids().get(i);
              MarketBoardSnapshot.PriceLevel priceLevel = new MarketBoardSnapshot.PriceLevel();
              priceLevel.setSnapshot(snapshot);
              priceLevel.setPrice(bid.getPrice());
              priceLevel.setQuantity(bid.getQuantity());
              priceLevel.setSide("BID");
              priceLevel.setLevelIndex(i);
              snapshot.getBids().add(priceLevel);
            }
          }

          // Asksを記録
          if (board.getAsks() != null) {
            for (int i = 0; i < board.getAsks().size(); i++) {
              var ask = board.getAsks().get(i);
              MarketBoardSnapshot.PriceLevel priceLevel = new MarketBoardSnapshot.PriceLevel();
              priceLevel.setSnapshot(snapshot);
              priceLevel.setPrice(ask.getPrice());
              priceLevel.setQuantity(ask.getQuantity());
              priceLevel.setSide("ASK");
              priceLevel.setLevelIndex(i);
              snapshot.getAsks().add(priceLevel);
            }
          }

          // 非同期キューに追加（ブロッキングしない）
          postgreSQLWriter.enqueue(snapshot);
          queuedCount++;

        } catch (Exception e) {
          log.warn("Failed to capture snapshot for symbol: {}", symbol, e);
        }
      }

      if (queuedCount > 0) {
        log.debug(
            "Queued {} market board snapshots at {} (queue size: {})",
            queuedCount,
            now,
            postgreSQLWriter.getQueueSize());
      }

    } catch (Exception e) {
      log.error("Error capturing market board snapshots", e);
    }
  }

  /** 古いデータを削除（market_board_snapshots と executions） 起動時に実行 */
  @Transactional
  public void cleanupOldData() {
    try {
      LocalDateTime cutoffDate = LocalDateTime.now().minusHours(retentionHours);

      // market_board_snapshotsの削除
      long snapshotDeleteCount = repository.deleteByTimestampBefore(cutoffDate);
      log.info(
          "Cleaned up {} market board snapshots older than {} (retention: {} hours)",
          snapshotDeleteCount,
          cutoffDate,
          retentionHours);

      // executionsテーブルの削除
      long executionDeleteCount = executionRepository.deleteByCreatedAtBefore(cutoffDate);
      log.info(
          "Cleaned up {} executions older than {} (retention: {} hours)",
          executionDeleteCount,
          cutoffDate,
          retentionHours);

    } catch (Exception e) {
      log.error("Error cleaning up old data", e);
    }
  }
}
