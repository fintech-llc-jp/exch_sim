package com.ys.exch_sim.domain.config;

import com.ys.exch_sim.domain.service.RedisPubSubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redis.pubsub.enabled", havingValue = "true", matchIfMissing = false)
public class RedisPubSubInitializer {

    private final RedisPubSubService redisPubSubService;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void initializeRedisPubSubAfterStartup() {
        log.info("🚀 Application ready - starting delayed Redis Pub/Sub initialization");
        
        // Wait a bit to ensure all beans are fully initialized
        try {
            Thread.sleep(2000); // 2 second delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Redis Pub/Sub initialization interrupted during delay");
            return;
        }

        // Attempt initialization with retry
        initializeWithRetry();
    }

    private void initializeWithRetry() {
        int maxRetries = 3;
        int retryDelay = 5; // seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("🔄 Redis Pub/Sub initialization attempt {}/{}", attempt, maxRetries);
                
                redisPubSubService.initializePubSub();
                
                if (redisPubSubService.isInitialized()) {
                    log.info("✅ Redis Pub/Sub initialization completed successfully on attempt {}", attempt);
                    return;
                }
                
            } catch (Exception e) {
                log.warn("❌ Redis Pub/Sub initialization failed on attempt {}: {}", attempt, e.getMessage());
            }

            // Wait before retry (except on last attempt)
            if (attempt < maxRetries) {
                try {
                    log.info("⏳ Waiting {} seconds before retry...", retryDelay);
                    Thread.sleep(retryDelay * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Redis Pub/Sub retry interrupted");
                    break;
                }
            }
        }
        
        log.error("💥 Redis Pub/Sub initialization failed after {} attempts. Application will continue without Pub/Sub functionality.", maxRetries);
    }
}