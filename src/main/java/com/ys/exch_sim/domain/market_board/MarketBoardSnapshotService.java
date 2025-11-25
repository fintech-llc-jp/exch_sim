package com.ys.exch_sim.domain.market_board;

import com.ys.exch_sim.domain.dto.MarketBoardResponse;
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

  @Value("${app.market-board.snapshot.retention-days:30}")
  private int retentionDays;

  /** 1秒ごとに全シンボルの板データを記録 非同期キューに追加してすぐにリターン（ブロッキングしない） */
  @Scheduled(fixedRate = 1000) // 1秒 = 1000ミリ秒
  public void captureMarketBoardSnapshots() {
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
          MarketBoardResponse board = orderService.getMarketBoard(symbol, 20); // 最大20レベルまで記録

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

  /** 古いスナップショットを削除 毎日午前3時に実行 */
  @Scheduled(cron = "0 0 3 * * ?") // 毎日午前3時
  @Transactional
  public void cleanupOldSnapshots() {
    try {
      LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
      repository.deleteByTimestampBefore(cutoffDate);
      log.info(
          "Cleaned up market board snapshots older than {} (retention: {} days)",
          cutoffDate,
          retentionDays);
    } catch (Exception e) {
      log.error("Error cleaning up old snapshots", e);
    }
  }
}
