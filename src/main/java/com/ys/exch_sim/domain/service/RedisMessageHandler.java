package com.ys.exch_sim.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.dto.RedisMarketMakeMessage;
import com.ys.exch_sim.domain.dto.RedisTradeInsertMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMessageHandler {

    private final ObjectMapper objectMapper;
    private final MarketDataSyncService marketDataSyncService;

    public void handleMarketMakeMessage(String message) {
        long startTime = System.currentTimeMillis();
        String messageId = java.util.UUID.randomUUID().toString().substring(0, 8);
        
        try {
            log.debug("📡 Redis MarketMake Message [{}] - Raw message received: {}", messageId, message);
            
            RedisMarketMakeMessage marketMakeMessage = objectMapper.readValue(message, RedisMarketMakeMessage.class);
            
            log.info("📊 Redis MarketMake [{}] - Processing symbol: {} with {} bids, {} asks", 
                messageId,
                marketMakeMessage.symbol(), 
                marketMakeMessage.bidLevels().size(), 
                marketMakeMessage.askLevels().size());
            
            long processingStart = System.currentTimeMillis();
            marketDataSyncService.updateMarketBoard(marketMakeMessage);
            long processingTime = System.currentTimeMillis() - processingStart;
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ Redis MarketMake [{}] - Completed - symbol: {}, boardUpdateTime: {}ms, totalTime: {}ms", 
                messageId, marketMakeMessage.symbol(), processingTime, totalTime);
            
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ Redis MarketMake [{}] - Error processing message - totalTime: {}ms, error: {}, message: {}", 
                messageId, totalTime, e.getMessage(), message, e);
        }
    }

    public void handleTradeInsertMessage(String message) {
        long startTime = System.currentTimeMillis();
        String messageId = java.util.UUID.randomUUID().toString().substring(0, 8);
        
        try {
            log.debug("📡 Redis TradeInsert Message [{}] - Raw message received: {}", messageId, message);
            
            RedisTradeInsertMessage tradeMessage = objectMapper.readValue(message, RedisTradeInsertMessage.class);
            
            log.info("💰 Redis TradeInsert [{}] - Processing symbol: {} - side: {}, price: {}, quantity: {}", 
                messageId,
                tradeMessage.symbol(), 
                tradeMessage.side(), 
                tradeMessage.price(), 
                tradeMessage.quantity());
            
            long processingStart = System.currentTimeMillis();
            marketDataSyncService.insertTrade(tradeMessage);
            long processingTime = System.currentTimeMillis() - processingStart;
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ Redis TradeInsert [{}] - Completed - symbol: {}, side: {}, dbSaveTime: {}ms, totalTime: {}ms", 
                messageId, tradeMessage.symbol(), tradeMessage.side(), processingTime, totalTime);
            
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ Redis TradeInsert [{}] - Error processing message - totalTime: {}ms, error: {}, message: {}", 
                messageId, totalTime, e.getMessage(), message, e);
        }
    }

    @Service
    @RequiredArgsConstructor
    public static class MarketMakeMessageListener implements MessageListener {
        private final RedisMessageHandler handler;

        @Override
        public void onMessage(Message message, byte[] pattern) {
            String messageBody = new String(message.getBody());
            // Remove extra quotes and unescape if present
            if (messageBody.startsWith("\"") && messageBody.endsWith("\"")) {
                messageBody = messageBody.substring(1, messageBody.length() - 1);
                // Unescape JSON
                messageBody = messageBody.replace("\\\"", "\"");
            }
            handler.handleMarketMakeMessage(messageBody);
        }
    }

    @Service
    @RequiredArgsConstructor
    public static class TradeInsertMessageListener implements MessageListener {
        private final RedisMessageHandler handler;

        @Override
        public void onMessage(Message message, byte[] pattern) {
            String messageBody = new String(message.getBody());
            // Remove extra quotes and unescape if present
            if (messageBody.startsWith("\"") && messageBody.endsWith("\"")) {
                messageBody = messageBody.substring(1, messageBody.length() - 1);
                // Unescape JSON
                messageBody = messageBody.replace("\\\"", "\"");
            }
            handler.handleTradeInsertMessage(messageBody);
        }
    }
}