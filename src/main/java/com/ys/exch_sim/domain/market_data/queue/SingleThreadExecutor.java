package com.ys.exch_sim.domain.market_data.queue;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * シンボル別単一スレッド実行器
 * 取引順序保証のため、各シンボルごとに専用スレッドで処理
 */
@Slf4j
public class SingleThreadExecutor {
    
    private final String threadName;
    private final ExecutorService executor;
    
    public SingleThreadExecutor(String threadNamePrefix) {
        this.threadName = threadNamePrefix + System.currentTimeMillis();
        this.executor = Executors.newSingleThreadExecutor(r -> 
            new Thread(r, threadName));
        
        log.debug("🔧 Created SingleThreadExecutor: {}", threadName);
    }
    
    /**
     * タスクを順序保証で実行
     */
    public void submit(Runnable task) {
        if (executor.isShutdown()) {
            log.warn("⚠️ Executor {} is shut down, task rejected", threadName);
            return;
        }
        
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("❌ Task execution failed in {}: {}", threadName, e.getMessage(), e);
            }
        });
    }
    
    /**
     * エグゼキューターを正常に停止
     */
    public void shutdown() {
        log.debug("🛑 Shutting down executor: {}", threadName);
        executor.shutdown();
        
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("⚠️ Executor {} did not terminate within 5 seconds, forcing shutdown", threadName);
                executor.shutdownNow();
                
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("❌ Executor {} could not be terminated", threadName);
                }
            } else {
                log.debug("✅ Executor {} terminated successfully", threadName);
            }
        } catch (InterruptedException e) {
            log.warn("⚠️ Interrupted while shutting down executor {}", threadName);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * エグゼキューターの状態を取得
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }
    
    /**
     * エグゼキューターの終了状態を取得
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }
    
    /**
     * エグゼキューターの名前を取得
     */
    public String getThreadName() {
        return threadName;
    }
}