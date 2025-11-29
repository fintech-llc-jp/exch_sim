package com.ys.exch_sim.domain.market_data.service;

import com.ys.exch_sim.domain.market_data.client.MarketDataWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * マーケットデータクライアント管理サービス
 */
@Slf4j
@Service
public class MarketDataClientManager {
    
    private final List<MarketDataWebSocketClient> clients;
    private final ScheduledExecutorService monitoringExecutor;
    
    @Autowired
    public MarketDataClientManager(List<MarketDataWebSocketClient> clients) {
        this.clients = clients;
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MarketData-Monitor");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void initializeClients() {
        long startTime = System.currentTimeMillis();
        log.info("========== MARKET_DATA_CLIENT_MANAGER START ==========");
        log.info("🚀 Initializing MarketData client manager with {} clients", clients.size());

        if (clients.isEmpty()) {
            log.error("❌ NO MARKET DATA CLIENTS FOUND! Check @ConditionalOnProperty settings:");
            log.error("   - market-data.bitflyer.enabled should be 'true'");
            log.error("   - market-data.gmo.enabled should be 'true'");
            return;
        }

        for (MarketDataWebSocketClient client : clients) {
            log.info("📋 Registered client: {}", client.getClientInfo());
            log.info("📋 Client class: {}", client.getClass().getSimpleName());
        }

        // 定期的な接続状態監視を開始
        long monitoringStart = System.currentTimeMillis();
        log.info("[MARKET_DATA_CLIENT] Starting monitoring...");
        startMonitoring();
        long monitoringEnd = System.currentTimeMillis();
        log.info("[MARKET_DATA_CLIENT] Monitoring startup took {} ms", (monitoringEnd - monitoringStart));

        long endTime = System.currentTimeMillis();
        log.info("========== MARKET_DATA_CLIENT_MANAGER COMPLETE ==========");
        log.info("✅ MarketData client manager initialized successfully in {} ms", (endTime - startTime));
    }

    @PreDestroy
    public void shutdownClients() {
        log.info("🛑 Shutting down MarketData client manager...");
        
        // 監視スレッドの停止
        stopMonitoring();
        
        // 全クライアントの切断
        stopAllClients();
        
        log.info("✅ MarketData client manager shutdown completed");
    }

    /**
     * 全クライアントを開始
     */
    public void startAllClients() {
        log.info("🚀 Starting all MarketData clients...");
        
        for (MarketDataWebSocketClient client : clients) {
            try {
                if (!client.isConnected()) {
                    log.info("🔌 Starting MarketData client: {}", client.getClientInfo());
                    client.connect();
                } else {
                    log.debug("✅ MarketData client already connected: {}", client.getClientInfo());
                }
            } catch (Exception e) {
                log.error("❌ Failed to start client: {} - {}", client.getClientInfo(), e.getMessage(), e);
            }
        }
        
        log.info("✅ All MarketData clients start completed");
    }

    /**
     * 全クライアントを停止
     */
    public void stopAllClients() {
        log.info("🛑 Stopping all MarketData clients...");
        
        for (MarketDataWebSocketClient client : clients) {
            try {
                if (client.isConnected()) {
                    log.info("🔌 Stopping MarketData client: {}", client.getClientInfo());
                    client.disconnect();
                } else {
                    log.debug("⏹️ MarketData client already disconnected: {}", client.getClientInfo());
                }
            } catch (Exception e) {
                log.error("❌ Failed to stop client: {} - {}", client.getClientInfo(), e.getMessage(), e);
            }
        }
        
        log.info("✅ All MarketData clients stop completed");
    }

    /**
     * 全クライアントが接続されているかチェック
     */
    public boolean areAllClientsConnected() {
        for (MarketDataWebSocketClient client : clients) {
            if (!client.isConnected()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 接続されているクライアント数を取得
     */
    public int getConnectedClientCount() {
        int connectedCount = 0;
        for (MarketDataWebSocketClient client : clients) {
            if (client.isConnected()) {
                connectedCount++;
            }
        }
        return connectedCount;
    }

    /**
     * 全クライアントの状態を取得
     */
    public String getAllClientsStatus() {
        StringBuilder status = new StringBuilder();
        status.append("MarketData Clients Status:\n");
        
        for (MarketDataWebSocketClient client : clients) {
            status.append("  - ").append(client.getClientInfo()).append("\n");
        }
        
        status.append("Connected: ").append(getConnectedClientCount())
              .append("/").append(clients.size());
        
        return status.toString();
    }

    /**
     * 切断されたクライアントを再接続
     */
    public void reconnectDisconnectedClients() {
        for (MarketDataWebSocketClient client : clients) {
            if (!client.isConnected()) {
                try {
                    log.info("🔄 Reconnecting disconnected MarketData client: {}", client.getClientInfo());
                    client.connect();
                } catch (Exception e) {
                    log.error("❌ Failed to reconnect client: {} - {}", client.getClientInfo(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 定期的な接続状態監視を開始
     */
    private void startMonitoring() {
        monitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                int connectedClients = getConnectedClientCount();
                int totalClients = clients.size();
                
                if (connectedClients < totalClients) {
                    log.warn("⚠️ MarketData client status: {}/{} connected", connectedClients, totalClients);
                    
                    // 切断されたクライアントの詳細ログと再接続
                    for (MarketDataWebSocketClient client : clients) {
                        if (!client.isConnected()) {
                            log.warn("🔌 Disconnected MarketData client: {}", client.getClientInfo());
                            try {
                                log.info("🔄 Reconnecting disconnected client: {}", client.getClientInfo());
                                client.connect(); // 再接続処理を呼び出す
                            } catch (Exception e) {
                                log.error("❌ Failed to reconnect client: {} - {}", client.getClientInfo(), e.getMessage(), e);
                            }
                        }
                    }
                } else {
                    log.info("✅ All MarketData clients connected: {}/{}", connectedClients, totalClients);
                    
                    // 接続中クライアントの詳細情報を定期的にログ出力（データ品質監視含む）
                    for (MarketDataWebSocketClient client : clients) {
                        if (client.isConnected()) {
                            log.info("📊 Connected MarketData client details: {}", client.getClientInfo());
                        }
                    }
                }
                
            } catch (Exception e) {
                log.error("❌ Error during MarketData client monitoring", e);
            }
        }, 30, 60, TimeUnit.SECONDS); // 30秒後に開始、1分間隔で監視
        
        log.info("👁️ MarketData client monitoring started (60s interval with detailed logging)");
    }

    /**
     * 監視スレッドを停止
     */
    private void stopMonitoring() {
        if (monitoringExecutor != null && !monitoringExecutor.isShutdown()) {
            monitoringExecutor.shutdown();
            try {
                if (!monitoringExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                    log.warn("⚠️ MarketData client monitoring forced shutdown");
                } else {
                    log.info("✅ MarketData client monitoring stopped gracefully");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                monitoringExecutor.shutdownNow();
                log.warn("⚠️ MarketData client monitoring interrupted during shutdown");
            }
        }
    }

    /**
     * 管理統計情報を取得
     */
    public String getManagerStats() {
        return String.format("MarketDataClientManager [Clients: %d, Connected: %d, Monitoring: %s]",
            clients.size(), 
            getConnectedClientCount(),
            !monitoringExecutor.isShutdown() ? "Active" : "Stopped");
    }
}