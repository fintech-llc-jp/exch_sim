package com.ys.exch_sim.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redis.pubsub.enabled", havingValue = "true", matchIfMissing = false)
public class RedisPubSubService {

    private final RedisMessageListenerContainer redisContainer;
    private final RedisMessageHandler.MarketMakeMessageListener marketMakeListener;
    private final RedisMessageHandler.TradeInsertMessageListener tradeInsertListener;

    @Value("${redis.pubsub.market-make.channel-pattern:market-make:*}")
    private String marketMakeChannelPattern;

    @Value("${redis.pubsub.trade-insert.channel-pattern:trade-insert:*}")
    private String tradeInsertChannelPattern;

    @PostConstruct
    public void subscribeToChannels() {
        try {
            // Subscribe to market-make channels
            redisContainer.addMessageListener(marketMakeListener, new PatternTopic(marketMakeChannelPattern));
            log.info("Subscribed to market-make channels with pattern: {}", marketMakeChannelPattern);

            // Subscribe to trade-insert channels
            redisContainer.addMessageListener(tradeInsertListener, new PatternTopic(tradeInsertChannelPattern));
            log.info("Subscribed to trade-insert channels with pattern: {}", tradeInsertChannelPattern);

            log.info("Redis Pub/Sub service initialized successfully");

        } catch (Exception e) {
            log.error("Failed to initialize Redis Pub/Sub service", e);
            throw new RuntimeException("Redis Pub/Sub initialization failed", e);
        }
    }
}