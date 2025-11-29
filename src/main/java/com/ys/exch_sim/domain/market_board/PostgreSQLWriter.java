package com.ys.exch_sim.domain.market_board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL書き込み専用のスレッドベースのライター
 * 
 * 機能:
 * - BlockingQueueを使用してMarketBoardSnapshotをキューイング
 * - 別スレッドでバッチ処理を実行
 * - キューが空の場合は低消費電力のスリープ
 * - 適応的なバッチサイズで効率化
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.database.type",
    havingValue = "postgresql",
    matchIfMissing = false
)
public class PostgreSQLWriter {

    private final MarketBoardSnapshotRepository repository;

    @Value("${app.postgresql.writer.batch-size:10}")
    private int batchSize;

    @Value("${app.postgresql.writer.empty-queue-sleep-ms:100}")
    private long emptyQueueSleepMs;

    /**
     * MarketBoardSnapshotを格納するキュー
     * スレッドセーフなBlockingQueueを使用
     */
    private BlockingQueue<MarketBoardSnapshot> snapshotQueue;

    /**
     * ライタースレッド
     */
    private Thread writerThread;

    /**
     * 実行フラグ
     */
    private AtomicBoolean isRunning;

    /**
     * キュー内のスナップショット数（統計用）
     */
    private volatile long totalQueued = 0;
    private volatile long totalWritten = 0;

    @PostConstruct
    public void initialize() {
        long startTime = System.currentTimeMillis();
        log.info("========== POSTGRESQL_WRITER START ==========");
        log.info("Initializing PostgreSQLWriter with batch size: {}", batchSize);

        snapshotQueue = new LinkedBlockingQueue<>();
        isRunning = new AtomicBoolean(true);

        // ライタースレッドを起動
        writerThread = new Thread(this::processQueue, "PostgreSQLWriter-Thread");
        writerThread.setDaemon(false);
        writerThread.start();

        long endTime = System.currentTimeMillis();
        log.info("========== POSTGRESQL_WRITER COMPLETE ==========");
        log.info("PostgreSQLWriter thread started in {} ms", (endTime - startTime));
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PostgreSQLWriter...");
        isRunning.set(false);

        // スレッドの終了を待つ（最大10秒）
        if (writerThread != null) {
            try {
                writerThread.join(10000);
                log.info("PostgreSQLWriter thread stopped");
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for PostgreSQLWriter thread", e);
                Thread.currentThread().interrupt();
            }
        }

        log.info("PostgreSQLWriter shutdown complete. Total queued: {}, Total written: {}",
                 totalQueued, totalWritten);
    }

    /**
     * MarketBoardSnapshotをキューに追加
     * 非ブロッキング・非同期：すぐに呼び出し元に制御を返す
     */
    public void enqueue(MarketBoardSnapshot snapshot) {
        try {
            snapshotQueue.put(snapshot);
            totalQueued++;
            if (totalQueued % 100 == 0) {
                log.debug("PostgreSQLWriter: Queued {} snapshots", totalQueued);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while enqueuing snapshot: {}", snapshot.getSymbol(), e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * スナップショットのキュー処理メインループ
     * 別スレッドで実行
     */
    private void processQueue() {
        log.info("PostgreSQLWriter processing loop started");

        while (isRunning.get() || !snapshotQueue.isEmpty()) {
            try {
                // バッチサイズ分のスナップショットを取得
                java.util.List<MarketBoardSnapshot> batch = new java.util.ArrayList<>();
                
                // 最初の1つを取得（タイムアウト付きでブロッキング）
                MarketBoardSnapshot first = snapshotQueue.poll();
                if (first == null) {
                    // キューが空の場合は少しスリープしてCPU使用率を削減
                    Thread.sleep(emptyQueueSleepMs);
                    continue;
                }
                batch.add(first);

                // 残りを非ブロッキングで取得
                for (int i = 1; i < batchSize; i++) {
                    MarketBoardSnapshot snapshot = snapshotQueue.poll();
                    if (snapshot == null) {
                        break;
                    }
                    batch.add(snapshot);
                }

                // バッチをPostgreSQLに書き込み
                writeBatchToPostgreSQL(batch);
                totalWritten += batch.size();

                // 定期的にログ出力
                if (totalWritten % 100 == 0) {
                    log.debug("PostgreSQLWriter: Written {} snapshots", totalWritten);
                }

            } catch (InterruptedException e) {
                log.debug("PostgreSQLWriter processing interrupted", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in PostgreSQLWriter processing loop", e);
                // エラーが発生してもループを継続（resilient）
            }
        }

        log.info("PostgreSQLWriter processing loop ended");
    }

    /**
     * バッチのスナップショットをPostgreSQLに書き込み
     */
    private void writeBatchToPostgreSQL(java.util.List<MarketBoardSnapshot> batch) {
        try {
            repository.saveAll(batch);
            log.debug("PostgreSQLWriter: Saved batch of {} snapshots", batch.size());
        } catch (Exception e) {
            log.error("Error writing batch to PostgreSQL (size: {})", batch.size(), e);
            // 個別にリトライ
            for (MarketBoardSnapshot snapshot : batch) {
                try {
                    repository.save(snapshot);
                } catch (Exception ex) {
                    log.error("Failed to save snapshot for symbol: {}", snapshot.getSymbol(), ex);
                }
            }
        }
    }

    /**
     * キューのサイズを取得（統計用）
     */
    public int getQueueSize() {
        return snapshotQueue != null ? snapshotQueue.size() : 0;
    }

    /**
     * 統計情報を取得
     */
    public String getStatistics() {
        return String.format("PostgreSQLWriter - Queued: %d, Written: %d, Queue Size: %d",
                totalQueued, totalWritten, getQueueSize());
    }
}

