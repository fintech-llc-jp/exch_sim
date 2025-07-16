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
        try {
            log.debug("Received market-make message: {}", message);
            
            RedisMarketMakeMessage marketMakeMessage = objectMapper.readValue(message, RedisMarketMakeMessage.class);
            
            log.info("Processing market-make for symbol: {} with {} bids, {} asks", 
                marketMakeMessage.symbol(), 
                marketMakeMessage.bidLevels().size(), 
                marketMakeMessage.askLevels().size());
            
            marketDataSyncService.updateMarketBoard(marketMakeMessage);
            
        } catch (Exception e) {
            log.error("Error processing market-make message: {}", message, e);
        }
    }

    public void handleTradeInsertMessage(String message) {
        try {
            log.debug("Received trade-insert message: {}", message);
            
            RedisTradeInsertMessage tradeMessage = objectMapper.readValue(message, RedisTradeInsertMessage.class);
            
            log.info("Processing trade-insert for symbol: {} - side: {}, price: {}, quantity: {}", 
                tradeMessage.symbol(), tradeMessage.side(), tradeMessage.price(), tradeMessage.quantity());
            
            marketDataSyncService.insertTrade(tradeMessage);
            
        } catch (Exception e) {
            log.error("Error processing trade-insert message: {}", message, e);
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