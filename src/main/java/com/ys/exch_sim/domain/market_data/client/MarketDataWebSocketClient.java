package com.ys.exch_sim.domain.market_data.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.market_data.service.DirectMarketDataService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * マーケットデータWebSocketクライアントの抽象基底クラス
 */
@Slf4j
public abstract class MarketDataWebSocketClient {
    
    protected final DirectMarketDataService marketDataService;
    protected final String wsUrl;
    protected final String exchange;
    protected final ObjectMapper objectMapper;
    protected final long reconnectDelay;
    protected final int maxReconnectAttempts;
    
    protected Disposable connection;
    protected final AtomicBoolean isConnected = new AtomicBoolean(false);
    protected final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    protected final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    
    protected MarketDataWebSocketClient(
            DirectMarketDataService marketDataService,
            String wsUrl,
            String exchange,
            long reconnectDelay,
            int maxReconnectAttempts) {
        this.marketDataService = marketDataService;
        this.wsUrl = wsUrl;
        this.exchange = exchange;
        this.objectMapper = new ObjectMapper();
        this.reconnectDelay = reconnectDelay;
        this.maxReconnectAttempts = maxReconnectAttempts;
    }
    
    /**
     * WebSocket接続を開始
     */
    public abstract void connect();
    
    /**
     * WebSocket接続を切断
     */
    public void disconnect() {
        shouldReconnect.set(false);
        if (connection != null && !connection.isDisposed()) {
            connection.dispose();
        }
        isConnected.set(false);
        log.info("🔌 {} WebSocket client disconnected", exchange);
    }
    
    /**
     * メッセージを処理
     */
    protected abstract void processMessage(String message);
    
    /**
     * 接続状態を取得
     */
    public boolean isConnected() {
        return isConnected.get();
    }
    
    /**
     * 接続エラーを処理
     */
    protected void handleConnectionError(Throwable error) {
        log.error("❌ {} WebSocket connection error: {}", exchange, error.getMessage());
        isConnected.set(false);
        
        if (shouldReconnect.get() && reconnectAttempts.get() < maxReconnectAttempts) {
            scheduleReconnection();
        } else {
            log.error("💥 {} WebSocket connection failed after {} attempts", 
                exchange, maxReconnectAttempts);
        }
    }
    
    /**
     * 再接続をスケジュール
     */
    protected void scheduleReconnection() {
        int currentAttempt = reconnectAttempts.incrementAndGet();
        long delay = reconnectDelay * currentAttempt; // 指数バックオフではなく線形増加
        
        log.warn("⏳ {} WebSocket reconnection attempt {}/{} in {}ms", 
            exchange, currentAttempt, maxReconnectAttempts, delay);
        
        // 再接続を遅延実行
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (shouldReconnect.get()) {
                    connect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("🛑 {} WebSocket reconnection interrupted", exchange);
            }
        }).start();
    }
    
    /**
     * 接続成功時の処理
     */
    protected void onConnectionEstablished() {
        isConnected.set(true);
        reconnectAttempts.set(0);
        log.info("✅ {} WebSocket connection established", exchange);
    }
    
    /**
     * 接続切断時の処理
     */
    protected void onConnectionClosed() {
        isConnected.set(false);
        log.warn("🔌 {} WebSocket connection closed", exchange);
        
        if (shouldReconnect.get()) {
            handleConnectionError(new RuntimeException("Connection closed"));
        }
    }
    
    /**
     * メッセージ処理エラーの処理
     */
    protected void handleMessageError(String message, Throwable error) {
        log.error("❌ {} Error processing message: {} - Message: {}", 
            exchange, error.getMessage(), message, error);
    }
    
    /**
     * クライアント情報を取得
     */
    public String getClientInfo() {
        return String.format("%s WebSocket Client [URL: %s, Connected: %s, Attempts: %d/%d]",
            exchange, wsUrl, isConnected.get(), reconnectAttempts.get(), maxReconnectAttempts);
    }
}