package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.bigquery.BigQueryEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryWriter;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.order_exec.Execution;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ExecutionQueueService {

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

  // 全約定履歴（BigQueryから読み込んだデータ + 新規約定）
  // メモリ内でのみ管理し、/allエンドポイント用
  private final ConcurrentLinkedDeque<Execution> executionHistory = new ConcurrentLinkedDeque<>();

  /**
   * 起動時に24時間以内の約定をBigQueryから読み込む
   */
  @PostConstruct
  public void initializeExecutionHistory() {
    try {
      log.info("🔄 Initializing execution history from BigQuery...");

      if (bigQueryEnabled && bigQueryService != null) {
        try {
          // BigQueryから24時間以内の約定を読み込む
          List<BigQueryExecutionEntity> recentExecutions = bigQueryService.queryRecentExecutions();

          if (recentExecutions != null && !recentExecutions.isEmpty()) {
            // BigQueryExecutionEntity をExecution に変換して履歴リストに追加
            for (BigQueryExecutionEntity bqEntity : recentExecutions) {
              Execution execution = convertBigQueryEntityToExecution(bqEntity);
              executionHistory.addLast(execution);
            }
            log.info("✅ Loaded {} recent executions from BigQuery into memory", recentExecutions.size());
          } else {
            log.info("⏭️ No recent executions found in BigQuery (last 24 hours)");
          }
        } catch (Exception e) {
          log.warn("⚠️ BigQuery is enabled but failed to load execution history: {}", e.getMessage());
        }
      } else {
        log.info("⏭️ BigQuery disabled, starting with empty execution history");
      }

      log.info("✅ Execution history initialization completed. Total: {}", executionHistory.size());
    } catch (Exception e) {
      log.error("❌ Error initializing execution history", e);
    }
  }

  /**
   * BigQueryExecutionEntity をExecution に変換する
   */
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
        entity.getSide()
    );
  }

  public void addExecution(String username, Execution execution) {
    // MarketMaker以外の「実際の約定」のみをメモリキャッシュとBigQueryに保存
    // PARTIAL_FILL と FILLED のみを記録対象（NEW, REJECTED, CANCELED は除外）
    if (!execution.getIsMarketMaker() && isActualExecution(execution)) {
      userExecutionQueues
          .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
          .offer(execution);

      // 履歴リストに追加（/allエンドポイント用）
      executionHistory.addLast(execution);

      try {
        // BigQueryにキューイング
        if (bigQueryWriter != null) {
          saveExecutionToBigQueryAsync(execution);
          log.info("Persisted non-MarketMaker execution to BigQuery for user: {}", username);
        }

        // 取引量を更新
        if (bigQueryEnabled && volumeCalculationService != null) {
          volumeCalculationService.updateVolumeOnTrade(execution);
        }
      } catch (Exception e) {
        log.error("Failed to persist execution to BigQuery for user: {}", username, e);
      }

      log.info("✅ Added actual execution to queue for user: {}, symbol: {}, status: {}, px: {}, qty: {}",
          username, execution.getSymbol(), execution.getExecStatus(),
          execution.getLastPxRaw(), execution.getLastQtyRaw());
    } else {
      // MarketMaker注文またはNEW/REJECTED/CANCELED状態は記録しない
      log.debug("⏭️ Skipped non-actual execution: symbol={}, isMarketMaker={}, execStatus={}",
          execution.getSymbol(), execution.getIsMarketMaker(), execution.getExecStatus());
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

    List<Execution> pageData = fromIndex < allExecutions.size()
        ? allExecutions.subList(fromIndex, toIndex)
        : Collections.emptyList();

    log.info("Retrieved execution history for user: {}, page: {}, size: {}, totalElements: {}, totalPages: {}",
        username, page, size, totalElements, totalPages);

    return new ExecutionHistoryData(username, page, size, totalPages, totalElements, pageData);
  }

  /**
   * 全ユーザーの約定履歴を取得（ページネーション対応）
   * メモリ内の履歴リストから読み込む（Poll不可、Pollされない）
   * @param page ページ番号（0から始まる）
   * @param size 1ページあたりの件数
   * @return ExecutionHistoryResponse用の履歴データ
   */
  public ExecutionHistoryData getAllExecutionHistory(int page, int size) {
    // メモリ内の履歴リストをコピー
    List<Execution> allExecutions = new ArrayList<>(executionHistory);

    // 全体の件数
    long totalElements = allExecutions.size();
    int totalPages = (int) Math.ceil((double) totalElements / size);

    // ページネーション
    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, allExecutions.size());

    List<Execution> pageData = fromIndex < allExecutions.size()
        ? allExecutions.subList(fromIndex, toIndex)
        : Collections.emptyList();

    log.info("Retrieved all execution history from memory, page: {}, size: {}, totalElements: {}, totalPages: {}",
        page, size, totalElements, totalPages);

    return new ExecutionHistoryData("ALL_USERS", page, size, totalPages, totalElements, pageData);
  }

  /**
   * 実際の約定かどうかを判定
   * PARTIAL_FILL と FILLED のみが実際の約定
   * NEW, REJECTED, CANCELED は約定ではなく注文状態変化
   */
  private boolean isActualExecution(Execution execution) {
    ExecStatus status = execution.getExecStatus();
    return status == ExecStatus.PARTIAL_FILL || status == ExecStatus.FILLED;
  }

  /**
   * 約定履歴データを保持するInnerクラス
   */
  public static class ExecutionHistoryData {
    public final String username;
    public final int page;
    public final int size;
    public final int totalPages;
    public final long totalElements;
    public final List<Execution> executions;

    public ExecutionHistoryData(String username, int page, int size,
                                int totalPages, long totalElements,
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
