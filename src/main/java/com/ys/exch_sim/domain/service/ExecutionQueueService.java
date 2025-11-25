package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.bigquery.BigQueryEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryWriter;
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

  @Autowired(required = false)
  private BigQueryService bigQueryService;

  @Autowired(required = false)
  private BigQueryWriter bigQueryWriter;

  @Autowired(required = false)
  private BigQueryVolumeCalculationService volumeCalculationService;

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

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
    // Start async initialization to avoid blocking Spring Boot startup
    initializeExecutionHistoryAsync();
  }

  /** Async initialization of execution history from database */
  @Async
  private void initializeExecutionHistoryAsync() {
    try {
      log.info("🔄 Initializing execution history from database...");

      LocalDateTime fromTime = LocalDateTime.now().minusHours(24);

      // DatabaseServiceを優先的に使用
      if (databaseService != null) {
        try {
          List<Execution> recentExecutions = databaseService.queryRecentExecutions(fromTime);

          if (recentExecutions != null && !recentExecutions.isEmpty()) {
            // Executionを銘柄別履歴と各ユーザーキューに追加
            for (Execution execution : recentExecutions) {
              // 銘柄別履歴リストに追加（/allエンドポイント用）
              String symbol = execution.getSymbol();
              executionHistoryBySymbol
                  .computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
                  .addLast(execution);

              // ユーザーキューにも追加（/pollエンドポイント用）
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
              "⚠️ DatabaseService is available but failed to load execution history: {}",
              e.getMessage());
        }
      } else if (bigQueryEnabled && bigQueryService != null) {
        // 後方互換性のため、BigQueryServiceを直接使用する場合
        try {
          log.info("⚠️ Using BigQueryService directly (DatabaseService not available)");
          List<BigQueryExecutionEntity> recentExecutions = bigQueryService.queryRecentExecutions();

          if (recentExecutions != null && !recentExecutions.isEmpty()) {
            // BigQueryExecutionEntity をExecution に変換して銘柄別履歴と各ユーザーキューに追加
            for (BigQueryExecutionEntity bqEntity : recentExecutions) {
              Execution execution = convertBigQueryEntityToExecution(bqEntity);

              // 銘柄別履歴リストに追加（/allエンドポイント用）
              String symbol = bqEntity.getSymbol();
              executionHistoryBySymbol
                  .computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
                  .addLast(execution);

              // ユーザーキューにも追加（/pollエンドポイント用）
              String username = bqEntity.getUsername();
              userExecutionQueues
                  .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
                  .offer(execution);
            }
            log.info(
                "✅ Loaded {} recent executions from BigQuery into memory and user queues",
                recentExecutions.size());
          } else {
            log.info("⏭️ No recent executions found in BigQuery (last 24 hours)");
          }
        } catch (Exception e) {
          log.warn(
              "⚠️ BigQuery is enabled but failed to load execution history: {}", e.getMessage());
        }
      } else {
        log.info("⏭️ Database service not available, starting with empty execution history");
      }

      log.info(
          "✅ Execution history initialization completed. Total symbols: {}, Total users: {}",
          executionHistoryBySymbol.size(),
          userExecutionQueues.size());
    } catch (Exception e) {
      log.error("❌ Error initializing execution history", e);
    }
  }

  /** BigQueryExecutionEntity をExecution に変換する */
  private Execution convertBigQueryEntityToExecution(BigQueryExecutionEntity entity) {
    return new Execution(
        entity.getExecId(),
        entity.getOrderId(),
        entity.getUsername(),
        entity.getSymbol(),
        ExecStatus.valueOf(entity.getExecStatus()),
        entity.getLastPx(),
        entity.getLastQty(),
        entity.getCounterPartyUsername(),
        LocalDateTime.parse(entity.getCreatedAt()),
        entity.getIsMarketMaker(),
        entity.getSide());
  }

  public void addExecution(String username, Execution execution) {
    // 「実際の約定」のみをメモリキャッシュに保存
    // PARTIAL_FILL と FILLED のみを記録対象（NEW, REJECTED, CANCELED は除外）
    if (isActualExecution(execution)) {
      userExecutionQueues
          .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
          .offer(execution);

      // 銘柄別履歴リストに追加（/allエンドポイント用）
      // MarketMaker約定も含めてすべての実際の約定を記録
      String symbol = execution.getSymbol();
      executionHistoryBySymbol
          .computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
          .addLast(execution);

      try {
        // DatabaseServiceを使用して約定を保存（MarketMaker以外のみ）
        if (!execution.getIsMarketMaker()) {
          if (databaseService != null) {
            databaseService.insertExecution(execution);
            log.info("Persisted execution to database for user: {}", username);
          } else if (bigQueryWriter != null) {
            // 後方互換性のため、BigQueryWriterを使用する場合
            saveExecutionToBigQueryAsync(execution);
            log.info("Persisted execution to BigQuery for user: {}", username);
          }
        }

        // 取引量を更新
        if (bigQueryEnabled && volumeCalculationService != null) {
          volumeCalculationService.updateVolumeOnTrade(execution);
        }
      } catch (Exception e) {
        log.error("Failed to persist execution to database for user: {}", username, e);
      }

      // キャッシュサイズをログに出力
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
      // NEW/REJECTED/CANCELED状態は記録しない
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

  // BigQuery保存メソッド（同期版）
  private void saveExecutionToBigQuery(Execution execution) {
    try {
      BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(execution);
      bigQueryService.insertExecution(bigQueryEntity);
      log.debug("Execution saved to BigQuery: {}", execution.getExecID());
    } catch (Exception e) {
      log.error("Error saving execution to BigQuery: " + execution.getExecID(), e);
    }
  }

  // BigQuery キューベースの非ブロッキング保存メソッド
  private void saveExecutionToBigQueryAsync(Execution execution) {
    try {
      if (bigQueryWriter != null) {
        // キューに追加（非ブロッキング）
        bigQueryWriter.enqueue(BigQueryEntity.execution(execution));
        log.debug("Execution enqueued to BigQuery writer: {}", execution.getExecID());
      }
    } catch (Exception e) {
      log.error("Error enqueuing execution to BigQuery: {}", execution.getExecID(), e);
    }
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
