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
        log.info("🚀 Initializing MarketData client manager with {} clients", clients.size());
        
        for (MarketDataWebSocketClient client : clients) {
            log.info("📋 Registered client: {}", client.getClientInfo());
        }
        
        // 定期的な接続状態監視を開始
        startMonitoring();
        
        log.info("✅ MarketData client manager initialized successfully");
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
                    log.info("🔌 Starting client: {}", client.getClientInfo());
                    client.connect();
                } else {
                    log.debug("✅ Client already connected: {}", client.getClientInfo());
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
                    log.info("🔌 Stopping client: {}", client.getClientInfo());
                    client.disconnect();
                } else {
                    log.debug("⏹️ Client already disconnected: {}", client.getClientInfo());
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
                    log.info("🔄 Reconnecting disconnected client: {}", client.getClientInfo());
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
                    
                    // 切断されたクライアントの詳細ログ
                    for (MarketDataWebSocketClient client : clients) {
                        if (!client.isConnected()) {
                            log.warn("🔌 Disconnected client: {}", client.getClientInfo());
                        }
                    }
                } else {
                    log.debug("✅ All MarketData clients connected: {}/{}", connectedClients, totalClients);
                }
                
            } catch (Exception e) {
                log.error("❌ Error during MarketData client monitoring", e);
            }
        }, 30, 30, TimeUnit.SECONDS); // 30秒間隔で監視
        
        log.info("👁️ MarketData client monitoring started (30s interval)");
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