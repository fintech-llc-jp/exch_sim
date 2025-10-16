package com.ys.exch_sim.domain.market_data.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * マーケットデータ処理用の非同期設定
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Value("${market-data.async.core-pool-size:4}")
    private int corePoolSize;
    
    @Value("${market-data.async.max-pool-size:8}")
    private int maxPoolSize;
    
    @Value("${market-data.async.queue-capacity:1000}")
    private int queueCapacity;
    
    @Value("${market-data.async.thread-name-prefix:MarketData-}")
    private String threadNamePrefix;

    @Value("${market-data.bigquery.async.core-pool-size:8}")
    private int bigQueryCorePoolSize;

    @Value("${market-data.bigquery.async.max-pool-size:16}")
    private int bigQueryMaxPoolSize;

    @Value("${market-data.bigquery.async.queue-capacity:5000}")
    private int bigQueryQueueCapacity;

    /**
     * マーケットデータ処理用のスレッドプール
     * WebSocketスレッドをブロックしないための専用Executor
     */
    @Bean("marketDataTaskExecutor")
    public Executor marketDataTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 基本設定
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        
        // 拒否ポリシー：呼び出し元スレッドで実行（WebSocketスレッドでの実行を避ける）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // スレッドプールの初期化待機
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        // アイドルスレッドの自動削除
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        
        executor.initialize();
        
        log.info("🚀 MarketData TaskExecutor initialized - Core: {}, Max: {}, Queue: {}", 
            corePoolSize, maxPoolSize, queueCapacity);
        
        return executor;
    }
    
    /**
     * BigQuery非同期処理用のスレッドプール
     */
    @Bean("bigQueryAsyncExecutor")
    public Executor bigQueryAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // BigQuery専用の設定（application.propertiesから読み込み）
        executor.setCorePoolSize(bigQueryCorePoolSize);
        executor.setMaxPoolSize(bigQueryMaxPoolSize);
        executor.setQueueCapacity(bigQueryQueueCapacity);
        executor.setThreadNamePrefix("BigQuery-Async-");

        // 拒否ポリシー：CallerRunsPolicy - キューが満杯の場合は呼び出し元スレッドで実行
        // これにより、データ損失を防ぎつつ、自然なバックプレッシャーを実現
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(120);

        executor.initialize();

        log.info("📊 BigQuery AsyncExecutor initialized - Core: {}, Max: {}, Queue: {}, RejectionPolicy: CallerRunsPolicy",
            bigQueryCorePoolSize, bigQueryMaxPoolSize, bigQueryQueueCapacity);

        return executor;
    }
}