package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.database.DatabaseService;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.order_exec.Execution;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ExecutionQueueService {

  @Autowired(required = false)
  private DatabaseService databaseService;

  // ユーザーごとの約定結果キュー（リアルタイム通知用）
  private final ConcurrentHashMap<String, BlockingQueue<Execution>> userExecutionQueues =
      new ConcurrentHashMap<>();

  // 銘柄ごとの約定履歴（データベースから読み込んだデータ + 新規約定）
  // メモリ内でのみ管理し、/allエンドポイント用
  private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Execution>>
      executionHistoryBySymbol = new ConcurrentHashMap<>();

  /** 起動時に24時間以内の約定をデータベースから読み込む (非同期) */
  @PostConstruct
  public void initializeExecutionHistory() {
    long startTime = System.currentTimeMillis();
    log.info("========== EXECUTION_QUEUE_SERVICE START ==========");
    log.info("🔄 Starting async initialization of execution history...");
    // Start async initialization to avoid blocking Spring Boot startup
    initializeExecutionHistoryAsync();
    long endTime = System.currentTimeMillis();
    log.info("========== EXECUTION_QUEUE_SERVICE @PostConstruct COMPLETE ==========");
    log.info("✅ Execution queue service initialization started asynchronously in {} ms", (endTime - startTime));
  }

  /** Async initialization of execution history from database */
  @Async
  private void initializeExecutionHistoryAsync() {
    long asyncStartTime = System.currentTimeMillis();
    try {
      log.info("========== EXECUTION_QUEUE_SERVICE ASYNC START ==========");
      log.info("🔄 Initializing execution history from database...");

      LocalDateTime fromTime = LocalDateTime.now().minusHours(24);

      if (databaseService != null) {
        try {
          List<Execution> recentExecutions = databaseService.queryRecentExecutions(fromTime);

          if (recentExecutions != null && !recentExecutions.isEmpty()) {
            for (Execution execution : recentExecutions) {
              String symbol = execution.getSymbol();
              executionHistoryBySymbol
                  .computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
                  .addLast(execution);

              String username = execution.getUsername();
              userExecutionQueues
                  .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
                  .offer(execution);
            }
            log.info(
                "✅ Loaded {} recent executions from database into memory and user queues",
                recentExecutions.size());
          } else {
            log.info("⏭️ No recent executions found in database (last 24 hours)");
          }
        } catch (Exception e) {
          log.warn(
              "⚠️ DatabaseService failed to load execution history: {}",
              e.getMessage());
        }
      } else {
        log.info("⏭️ Database service not available, starting with empty execution history");
      }

      long asyncEndTime = System.currentTimeMillis();
      log.info(
          "✅ Execution history initialization completed. Total symbols: {}, Total users: {}",
          executionHistoryBySymbol.size(),
          userExecutionQueues.size());
      log.info("========== EXECUTION_QUEUE_SERVICE ASYNC COMPLETE ==========");
      log.info("✅ Async execution history initialization completed in {} ms", (asyncEndTime - asyncStartTime));
    } catch (Exception e) {
      log.error("❌ Error initializing execution history", e);
    }
  }

  public void addExecution(String username, Execution execution) {
    if (isActualExecution(execution)) {
      userExecutionQueues
          .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
          .offer(execution);

      String symbol = execution.getSymbol();
      executionHistoryBySymbol
          .computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
          .addLast(execution);

      try {
        if (!execution.getIsMarketMaker() && databaseService != null) {
          databaseService.insertExecution(execution);
          log.info("Persisted execution to database for user: {}", username);
        }
      } catch (Exception e) {
        log.error("Failed to persist execution to database for user: {}", username, e);
      }

      int userQueueSize = userExecutionQueues.get(username).size();
      int symbolCacheSize = executionHistoryBySymbol.get(symbol).size();
      log.info(
          "✅ Added actual execution to cache - user: {}, symbol: {}, status: {}, px: {}, qty: {}, "
              + "isMarketMaker: {}, userQueueSize: {}, symbolCacheSize: {}",
          username,
          symbol,
          execution.getExecStatus(),
          execution.getLastPxRaw(),
          execution.getLastQtyRaw(),
          execution.getIsMarketMaker(),
          userQueueSize,
          symbolCacheSize);
    } else {
      log.debug(
          "⏭️ Skipped non-actual execution: symbol={}, isMarketMaker={}, execStatus={}",
          execution.getSymbol(),
          execution.getIsMarketMaker(),
          execution.getExecStatus());
    }
  }

  public List<Execution> pollExecutions(String username, int maxCount) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    if (userQueue == null) {
      return Collections.emptyList();
    }

    List<Execution> executions = new ArrayList<>();
    userQueue.drainTo(executions, maxCount);

    log.info("Polled {} executions for user: {}", executions.size(), username);
    return executions;
  }

  public List<Execution> pollAllExecutions(String username) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    if (userQueue == null) {
      return Collections.emptyList();
    }

    List<Execution> executions = new ArrayList<>();
    userQueue.drainTo(executions);

    log.info("Polled all {} executions for user: {}", executions.size(), username);
    return executions;
  }

  public int getQueueSize(String username) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    return userQueue != null ? userQueue.size() : 0;
  }

  /**
   * ユーザーの約定履歴を取得（ページネーション対応）
   *
   * @param username ユーザー名
   * @param page ページ番号（0から始まる）
   * @param size 1ページあたりの件数
   * @return ExecutionHistoryResponse用の履歴データ
   */
  public ExecutionHistoryData getExecutionHistory(String username, int page, int size) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    if (userQueue == null) {
      return new ExecutionHistoryData(username, page, size, 0, 0L, Collections.emptyList());
    }

    // キューから全ての約定を取得（キューは破壊されない）
    List<Execution> allExecutions = new ArrayList<>(userQueue);

    // 全体の件数
    long totalElements = allExecutions.size();
    int totalPages = (int) Math.ceil((double) totalElements / size);

    // ページネーション
    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, allExecutions.size());

    List<Execution> pageData =
        fromIndex < allExecutions.size()
            ? allExecutions.subList(fromIndex, toIndex)
            : Collections.emptyList();

    log.info(
        "Retrieved execution history for user: {}, page: {}, size: {}, totalElements: {},"
            + " totalPages: {}",
        username,
        page,
        size,
        totalElements,
        totalPages);

    return new ExecutionHistoryData(username, page, size, totalPages, totalElements, pageData);
  }

  /**
   * 指定銘柄の約定履歴を取得（ページネーション対応） メモリ内の銘柄別履歴リストから読み込む（Poll不可、Pollされない）
   *
   * @param symbol 銘柄名（必須）
   * @param page ページ番号（0から始まる）
   * @param size 1ページあたりの件数
   * @return ExecutionHistoryResponse用の履歴データ
   */
  public ExecutionHistoryData getExecutionsBySymbol(String symbol, int page, int size) {
    // 銘柄別履歴リストをコピー
    ConcurrentLinkedDeque<Execution> symbolDeque = executionHistoryBySymbol.get(symbol);
    List<Execution> allExecutions =
        symbolDeque != null ? new ArrayList<>(symbolDeque) : Collections.emptyList();

    // 全体の件数
    long totalElements = allExecutions.size();
    int totalPages = (int) Math.ceil((double) totalElements / size);

    // ページネーション
    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, allExecutions.size());

    List<Execution> pageData =
        fromIndex < allExecutions.size()
            ? allExecutions.subList(fromIndex, toIndex)
            : Collections.emptyList();

    log.info(
        "📊 Retrieved execution history for symbol: {}, totalElements: {}, totalPages: {}, "
            + "page: {}, size: {}",
        symbol,
        totalElements,
        totalPages,
        page,
        size);

    return new ExecutionHistoryData(symbol, page, size, totalPages, totalElements, pageData);
  }

  /** 実際の約定かどうかを判定 PARTIAL_FILL と FILLED のみが実際の約定 NEW, REJECTED, CANCELED は約定ではなく注文状態変化 */
  private boolean isActualExecution(Execution execution) {
    ExecStatus status = execution.getExecStatus();
    return status == ExecStatus.PARTIAL_FILL || status == ExecStatus.FILLED;
  }

  /** 約定履歴データを保持するInnerクラス */
  public static class ExecutionHistoryData {
    public final String username;
    public final int page;
    public final int size;
    public final int totalPages;
    public final long totalElements;
    public final List<Execution> executions;

    public ExecutionHistoryData(
        String username,
        int page,
        int size,
        int totalPages,
        long totalElements,
        List<Execution> executions) {
      this.username = username;
      this.page = page;
      this.size = size;
      this.totalPages = totalPages;
      this.totalElements = totalElements;
      this.executions = executions;
    }
  }
}
