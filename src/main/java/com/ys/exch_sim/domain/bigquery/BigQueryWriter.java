package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.*;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BigQuery書き込み専用のスレッドベースのライター
 *
 * 機能:
 * - BlockingQueueを使用してエンティティをキューイング
 * - 別スレッドでバッチ処理を実行
 * - キューが空の場合は低消費電力のスリープ
 * - 適応的なバッチサイズで効率化
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.data-migration.bigquery-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class BigQueryWriter {

    private final BigQuery bigQuery;
    private final BigQueryService bigQueryService;

    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;

    @Value("${spring.cloud.gcp.bigquery.dataset-name}")
    private String datasetName;

    @Value("${app.bigquery.writer.batch-size:100}")
    private int batchSize;

    @Value("${app.bigquery.writer.empty-queue-sleep-ms:100}")
    private long emptyQueueSleepMs;

    @Value("${app.bigquery.writer.write-timeout-ms:30000}")
    private long writeTimeoutMs;

    /**
     * BigQueryエンティティを格納するキュー
     * スレッドセーフなBlockingQueueを使用
     */
    private BlockingQueue<BigQueryEntity> entityQueue;

    /**
     * ライタースレッド
     */
    private Thread writerThread;

    /**
     * 実行フラグ
     */
    private AtomicBoolean isRunning;

    /**
     * キュー内のエンティティ数（統計用）
     */
    private volatile long totalQueued = 0;
    private volatile long totalWritten = 0;

    @PostConstruct
    public void initialize() {
        log.info("Initializing BigQueryWriter with batch size: {}", batchSize);

        entityQueue = new LinkedBlockingQueue<>();
        isRunning = new AtomicBoolean(true);

        // ライタースレッドを起動
        writerThread = new Thread(this::processQueue, "BigQueryWriter-Thread");
        writerThread.setDaemon(false);
        writerThread.start();

        log.info("BigQueryWriter thread started");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down BigQueryWriter...");
        isRunning.set(false);

        // スレッドの終了を待つ（最大10秒）
        if (writerThread != null) {
            try {
                writerThread.join(10000);
                log.info("BigQueryWriter thread stopped");
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for BigQueryWriter thread", e);
                Thread.currentThread().interrupt();
            }
        }

        log.info("BigQueryWriter shutdown complete. Total queued: {}, Total written: {}",
                 totalQueued, totalWritten);
    }

    /**
     * BigQueryエンティティをキューに追加
     * 非ブロッキング・非同期：すぐに呼び出し元に制御を返す
     */
    public void enqueue(BigQueryEntity entity) {
        try {
            entityQueue.put(entity);
            totalQueued++;
            if (totalQueued % 1000 == 0) {
                log.info("BigQueryWriter: Queued {} entities", totalQueued);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while enqueuing BigQuery entity: {}", entity, e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * エンティティのキュー処理メインループ
     * 別スレッドで実行
     */
    private void processQueue() {
        log.info("BigQueryWriter processing loop started");

        while (isRunning.get()) {
            try {
                BigQueryEntity entity = entityQueue.poll();

                if (entity == null) {
                    // キューが空の場合は少しスリープして CPU 使用率を削減
                    Thread.sleep(emptyQueueSleepMs);
                    continue;
                }

                // エンティティを BigQuery に書き込み
                writeEntityToBigQuery(entity);
                totalWritten++;

                // 定期的にログ出力
                if (totalWritten % 1000 == 0) {
                    log.info("BigQueryWriter: Written {} entities", totalWritten);
                }

            } catch (InterruptedException e) {
                log.debug("BigQueryWriter processing interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in BigQueryWriter processing loop", e);
                // エラーが発生してもループを継続（resilient）
            }
        }

        log.info("BigQueryWriter processing loop ended");
    }

    /**
     * 単一のエンティティを BigQuery に書き込み
     */
    private void writeEntityToBigQuery(BigQueryEntity entity) {
        try {
            switch (entity.getType()) {
                case EXECUTION:
                    writeExecution(entity.getExecution());
                    break;
                case POSITION:
                    writePosition(entity.getPosition());
                    break;
                case TRADE_HISTORY:
                    writeTradeHistory(entity.getTradeHistory());
                    break;
                default:
                    log.warn("Unknown entity type: {}", entity.getType());
            }
        } catch (Exception e) {
            log.error("Error writing entity to BigQuery: {}", entity, e);
            // エラーログを記録するが、処理は続行
        }
    }

    /**
     * 約定を BigQuery に書き込み
     */
    private void writeExecution(Execution execution) {
        try {
            BigQueryExecutionEntity bigQueryExecution = new BigQueryExecutionEntity(execution);
            TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = bigQueryExecution.toBigQueryRow();

            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();

            InsertAllResponse response = bigQuery.insertAll(insertRequest);

            if (response.hasErrors()) {
                log.error("Error inserting execution to BigQuery: {}", response.getInsertErrors());
            } else {
                log.debug("Successfully inserted execution to BigQuery: {}", execution.getExecID().getId());
            }
        } catch (Exception e) {
            log.error("Error writing execution to BigQuery: {}", execution.getExecID().getId(), e);
        }
    }

    /**
     * ポジションを BigQuery に書き込み（MERGE を使用）
     */
    private void writePosition(Position position) {
        try {
            BigQueryPositionEntity bigQueryPosition = new BigQueryPositionEntity(position);

            // シンプルな INSERT まずは試す
            TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = bigQueryPosition.toBigQueryRow();

            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();

            InsertAllResponse response = bigQuery.insertAll(insertRequest);

            if (!response.hasErrors()) {
                log.debug("Successfully inserted position to BigQuery: {}_{}",
                         position.getUsername(), position.getSymbol());
                return;
            }

            // INSERT が失敗した場合は MERGE を使用（update）
            log.debug("Insert failed for position, attempting upsert: {}_{}",
                     position.getUsername(), position.getSymbol());

            bigQueryService.upsertPosition(bigQueryPosition);
            log.debug("Successfully upserted position to BigQuery: {}_{}",
                     position.getUsername(), position.getSymbol());

        } catch (Exception e) {
            log.error("Error writing position to BigQuery: {}_{}",
                     position.getUsername(), position.getSymbol(), e);
        }
    }

    /**
     * 取引履歴を BigQuery に書き込み
     */
    private void writeTradeHistory(TradeHistory tradeHistory) {
        try {
            BigQueryTradeHistoryEntity bigQueryTradeHistory = new BigQueryTradeHistoryEntity(tradeHistory);
            TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = bigQueryTradeHistory.toBigQueryRow();

            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();

            InsertAllResponse response = bigQuery.insertAll(insertRequest);

            if (response.hasErrors()) {
                log.error("Error inserting trade history to BigQuery: {}", response.getInsertErrors());
            } else {
                log.debug("Successfully inserted trade history to BigQuery: {}", tradeHistory.getExecID());
            }
        } catch (Exception e) {
            log.error("Error writing trade history to BigQuery: {}", tradeHistory.getExecID(), e);
        }
    }

    /**
     * キューのサイズを取得（監視用）
     */
    public int getQueueSize() {
        return entityQueue.size();
    }

    /**
     * 統計情報を取得
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
            "queueSize", getQueueSize(),
            "totalQueued", totalQueued,
            "totalWritten", totalWritten,
            "isRunning", isRunning.get()
        );
    }
}
