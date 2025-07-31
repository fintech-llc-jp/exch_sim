package com.ys.exch_sim.domain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "bigQueryAsyncExecutor")
    public Executor bigQueryAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // スレッドプール設定
        executor.setCorePoolSize(5);      // 最小スレッド数
        executor.setMaxPoolSize(20);      // 最大スレッド数
        executor.setQueueCapacity(100);   // キューサイズ
        executor.setKeepAliveSeconds(60); // アイドルスレッドの生存時間
        
        // スレッド名のプレフィックス
        executor.setThreadNamePrefix("BigQuery-Async-");
        
        // スレッドプールが満杯の場合の処理方針
        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                log.error("BigQuery async task rejected. Queue is full. Current pool size: {}, Active threads: {}, Queue size: {}",
                         executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size());
                // 呼び出し元スレッドで実行（フォールバック）
                r.run();
            }
        });
        
        // シャットダウン時の待機設定
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        log.info("✅ BigQuery async executor initialized successfully");
        log.info("   Core pool size: {}", executor.getCorePoolSize());
        log.info("   Max pool size: {}", executor.getMaxPoolSize());
        log.info("   Queue capacity: {}", executor.getQueueCapacity());
        log.info("   Keep alive seconds: {}", executor.getKeepAliveSeconds());
        log.info("   Thread name prefix: {}", executor.getThreadNamePrefix());
        
        return executor;
    }
}