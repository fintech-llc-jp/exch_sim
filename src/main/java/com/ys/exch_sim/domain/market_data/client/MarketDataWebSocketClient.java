package com.ys.exch_sim.domain.market_data.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.market_data.service.DirectMarketDataService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.Disposable;

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
    
    // 接続品質監視用
    protected volatile Instant connectionEstablishedAt;
    protected final AtomicLong totalMessageCount = new AtomicLong(0);
    protected final AtomicLong totalErrorCount = new AtomicLong(0);
    protected volatile Instant lastActivityTime = Instant.now();
    
    // 接続監視用
    protected Disposable connectionMonitor;
    protected static final long CONNECTION_TIMEOUT_SECONDS = 900; // 15分 (Bitflyer適応)
    protected static final long MONITORING_INTERVAL_SECONDS = 30; // 30秒間隔で監視
    
    // 接続品質統計
    protected final AtomicLong handshakeErrorCount = new AtomicLong(0);
    protected final AtomicLong networkErrorCount = new AtomicLong(0);
    protected volatile Instant lastHandshakeErrorTime;
    protected volatile Instant lastNetworkErrorTime;
    
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
        
        // 接続監視を停止
        stopConnectionMonitoring();
        
        boolean wasConnected = isConnected.get();
        if (connection != null && !connection.isDisposed()) {
            connection.dispose();
        }
        
        // 接続状態を更新
        isConnected.set(false);
        
        // 接続していた場合のみログ出力
        if (wasConnected) {
            log.info("🔌 {} WebSocket client disconnected - URL: {}", exchange, wsUrl);
        }
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
        log.error("❌ {} WebSocket connection error - URL: {}, Error: {}", exchange, wsUrl, error.getMessage());
        
        // エラーカウンターとアクティビティ時刻を更新
        totalErrorCount.incrementAndGet();
        lastActivityTime = Instant.now();
        
        // 接続状態を更新
        boolean wasConnected = isConnected.getAndSet(false);
        
        // 特定のエラータイプに対する特別な処理
        boolean isHandshakeError = error.getMessage() != null && 
            (error.getMessage().contains("handshake") || 
             error.getMessage().contains("prematurely closed") ||
             error.getMessage().contains("Connection reset"));
        
        // エラー統計の更新
        if (isHandshakeError) {
            handshakeErrorCount.incrementAndGet();
            lastHandshakeErrorTime = Instant.now();
            log.warn("🔧 {} Handshake error detected (total: {})", exchange, handshakeErrorCount.get());
        } else {
            networkErrorCount.incrementAndGet();
            lastNetworkErrorTime = Instant.now();
        }
        
        // Bitflyerの場合は、Reactorのretry機能に任せるため、
        // 基底クラスからの再接続は実行しない（重複を防ぐ）
        if ("Bitflyer".equals(exchange)) {
            log.info("🔄 {} Letting Reactor retry mechanism handle reconnection", exchange);
            return;
        }
        
        // 接続レベルのエラーのみで再接続を実行 (Bitflyer以外)
        if (shouldReconnect.get() && reconnectAttempts.get() < maxReconnectAttempts) {
            // handshakeエラーの場合はより積極的に再接続
            if (isHandshakeError) {
                log.warn("🔄 {} Detected handshake/network error, attempting immediate reconnection", exchange);
                scheduleReconnection(true); // 即座再接続
            } else if (wasConnected) {
                scheduleReconnection(false); // 通常の再接続
            }
        } else if (reconnectAttempts.get() >= maxReconnectAttempts) {
            log.error("💥 {} WebSocket connection failed after {} attempts - URL: {}", 
                exchange, maxReconnectAttempts, wsUrl);
            shouldReconnect.set(false);
        }
    }
    
    protected void handleProcessingError(Throwable error) {
        log.error("⚠️ {} WebSocket message processing error - URL: {}, Error: {}", exchange, wsUrl, error.getMessage());
        
        // 処理エラーカウンターとアクティビティ時刻を更新
        totalErrorCount.incrementAndGet();
        lastActivityTime = Instant.now();
        
        // 処理エラーでは再接続しない（接続は維持）
    }
    
    /**
     * 再接続をスケジュール
     */
    protected void scheduleReconnection() {
        scheduleReconnection(false);
    }
    
    protected void scheduleReconnection(boolean immediate) {
        int currentAttempt = reconnectAttempts.incrementAndGet();
        
        // 最大試行回数を超えた場合は再接続しない
        if (currentAttempt > maxReconnectAttempts) {
            log.error("💥 {} WebSocket max reconnection attempts ({}) exceeded - URL: {}", 
                exchange, maxReconnectAttempts, wsUrl);
            shouldReconnect.set(false);
            return;
        }
        
        // 指数バックオフ (最大60秒まで), 即座再接続の場合は遅延なし
        long delay = immediate ? 0 : Math.min(reconnectDelay * (long) Math.pow(2, currentAttempt - 1), 60000);
        
        log.warn("⏳ {} WebSocket reconnection attempt {}/{} in {}ms - URL: {}", 
            exchange, currentAttempt, maxReconnectAttempts, delay, wsUrl);
        
        // Reactor適切な遅延処理を使用
        reactor.core.publisher.Mono.delay(Duration.ofMillis(delay))
            .subscribe(
                tick -> {
                    if (shouldReconnect.get()) {
                        try {
                            connect();
                        } catch (Exception e) {
                            log.error("❌ {} WebSocket reconnection failed - URL: {}, Error: {}", exchange, wsUrl, e.getMessage(), e);
                            // 再接続に失敗した場合、エラーハンドラーを呼び出し
                            handleConnectionError(e);
                        }
                    }
                },
                error -> {
                    log.error("❌ {} WebSocket reconnection scheduler error - URL: {}, Error: {}", exchange, wsUrl, error.getMessage());
                    handleConnectionError(error);
                }
            );
    }
    
    /**
     * 接続成功時の処理
     */
    protected void onConnectionEstablished() {
        boolean wasConnected = isConnected.getAndSet(true);
        reconnectAttempts.set(0);
        
        Instant now = Instant.now();
        connectionEstablishedAt = now;
        lastActivityTime = now;
        
        // 新たに接続された場合のみログ出力
        if (!wasConnected) {
            log.info("✅ {} WebSocket connection established - URL: {}", exchange, wsUrl);
        }
        
        // 接続監視を開始
        startConnectionMonitoring();
    }
    
    /**
     * 接続切断時の処理
     */
    protected void onConnectionClosed() {
        boolean wasConnected = isConnected.getAndSet(false);
        
        // 接続監視を停止
        stopConnectionMonitoring();
        
        // 接続していた場合のみログ出力
        if (wasConnected) {
            log.warn("🔌 {} WebSocket connection closed - URL: {}", exchange, wsUrl);
        }
        
        // アクティビティ時刻を更新
        lastActivityTime = Instant.now();
        
        // 再接続を試みる必要がある場合
        if (shouldReconnect.get() && wasConnected) {
            handleConnectionError(new RuntimeException("Connection closed"));
        }
    }
    
    /**
     * メッセージ処理エラーの処理
     */
    protected void handleMessageError(String message, Throwable error) {
        totalErrorCount.incrementAndGet();
        lastActivityTime = Instant.now();
        log.error("❌ {} Error processing message - URL: {}, Error: {}, Message: {}", 
            exchange, wsUrl, error.getMessage(), message, error);
    }
    
    /**
     * クライアント情報を取得
     */
    public String getClientInfo() {
        Duration uptime = connectionEstablishedAt != null ? 
            Duration.between(connectionEstablishedAt, Instant.now()) : Duration.ZERO;
        Duration timeSinceLastActivity = Duration.between(lastActivityTime, Instant.now());
        
        return String.format("%s WebSocket Client [URL: %s, Connected: %s, Uptime: %ds, " +
            "Messages: %d, Errors: %d (Handshake: %d, Network: %d), Last Activity: %ds ago, Attempts: %d/%d]",
            exchange, wsUrl, isConnected.get(), uptime.toSeconds(),
            totalMessageCount.get(), totalErrorCount.get(), handshakeErrorCount.get(), networkErrorCount.get(),
            timeSinceLastActivity.toSeconds(), reconnectAttempts.get(), maxReconnectAttempts);
    }
    
    /**
     * メッセージカウントを更新（子クラスから呼び出し用）
     */
    protected void incrementMessageCount() {
        totalMessageCount.incrementAndGet();
        lastActivityTime = Instant.now();
    }
    
    protected void updateActivityTime() {
        lastActivityTime = Instant.now();
    }
    
    private void startConnectionMonitoring() {
        stopConnectionMonitoring();
        
        connectionMonitor = reactor.core.publisher.Flux.interval(Duration.ofSeconds(MONITORING_INTERVAL_SECONDS))
            .subscribe(
                tick -> {
                    if (isConnected.get()) {
                        Instant now = Instant.now();
                        Duration timeSinceLastActivity = Duration.between(lastActivityTime, now);
                        long inactiveSeconds = timeSinceLastActivity.getSeconds();
                        
                        // Bitflyer用最適化: 15分以上非活性の場合のみタイムアウト判定
                        if (inactiveSeconds > CONNECTION_TIMEOUT_SECONDS) {
                            long currentMessageCount = totalMessageCount.get();
                            
                            log.warn("⚠️ {} WebSocket connection inactive for {} seconds ({}min). Messages: {}, Errors: {}", 
                                exchange, inactiveSeconds, inactiveSeconds/60, currentMessageCount, totalErrorCount.get());
                            
                            // Bitflyerは独自のkeepaliveとstream monitoringで処理するため、
                            // ここではフォールバック的な監視のみ行う
                            if (!"Bitflyer".equals(exchange)) {
                                handleConnectionError(new RuntimeException("Connection timeout - no activity for " + inactiveSeconds + "s"));
                            } else {
                                log.info("🔄 {} Base class monitoring detected extended inactivity - relying on application-level keepalive", exchange);
                            }
                        } else if (inactiveSeconds > 300) { // 5分以上非活性の場合は警告ログのみ
                            if ("Bitflyer".equals(exchange)) {
                                log.debug("🔍 {} WebSocket connection inactive for {} seconds - application-level monitoring active. Messages: {}", 
                                    exchange, inactiveSeconds, totalMessageCount.get());
                            } else {
                                log.debug("🔍 {} WebSocket connection inactive for {} seconds. Messages: {}", 
                                    exchange, inactiveSeconds, totalMessageCount.get());
                            }
                        }
                    }
                },
                error -> log.error("❌ {} Connection monitoring error", exchange, error)
            );
    }
    
    private void stopConnectionMonitoring() {
        if (connectionMonitor != null && !connectionMonitor.isDisposed()) {
            connectionMonitor.dispose();
            connectionMonitor = null;
        }
    }
}