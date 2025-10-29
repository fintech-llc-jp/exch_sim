package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.order_exec.Execution;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
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
  private BigQueryVolumeCalculationService volumeCalculationService;

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  // ユーザーごとの約定結果キュー
  private final ConcurrentHashMap<String, BlockingQueue<Execution>> userExecutionQueues =
      new ConcurrentHashMap<>();

  public void addExecution(String username, Execution execution) {
    userExecutionQueues
        .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
        .offer(execution);

    // MarketMaker以外の「実際の約定」のみをBigQueryに永続化
    // PARTIAL_FILL と FILLED のみを記録対象（NEW, REJECTED, CANCELED は除外）
    if (!execution.getIsMarketMaker() && isActualExecution(execution)) {
      try {
        // BigQueryにも非同期保存
        if (bigQueryEnabled && bigQueryService != null) {
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
    }

    log.info("Added execution to queue for user: {}", username);
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

  // BigQuery非同期保存メソッド
  private void saveExecutionToBigQueryAsync(Execution execution) {
    try {
      BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(execution);
      bigQueryService
          .insertExecutionAsync(bigQueryEntity)
          .thenRun(
              () -> log.debug("Execution saved to BigQuery (async): {}", execution.getExecID()))
          .exceptionally(
              throwable -> {
                log.error(
                    "Error saving execution to BigQuery (async): {}",
                    execution.getExecID(),
                    throwable);
                return null;
              });
    } catch (Exception e) {
      log.error("Error preparing execution for BigQuery (async): {}", execution.getExecID(), e);
    }
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
}
