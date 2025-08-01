package com.ys.exch_sim.domain.market_data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * マーケットデータクライアントの設定
 */
@Configuration
@ConfigurationProperties(prefix = "market-data")
public class MarketDataClientConfig {
    
    private Bitflyer bitflyer = new Bitflyer();
    private Gmo gmo = new Gmo();
    private Board board = new Board();
    private Trade trade = new Trade();
    private Map<String, Map<String, String>> symbolMapping;
    
    // Getters and Setters
    public Bitflyer getBitflyer() {
        return bitflyer;
    }
    
    public void setBitflyer(Bitflyer bitflyer) {
        this.bitflyer = bitflyer;
    }
    
    public Gmo getGmo() {
        return gmo;
    }
    
    public void setGmo(Gmo gmo) {
        this.gmo = gmo;
    }
    
    public Board getBoard() {
        return board;
    }
    
    public void setBoard(Board board) {
        this.board = board;
    }
    
    public Trade getTrade() {
        return trade;
    }
    
    public void setTrade(Trade trade) {
        this.trade = trade;
    }
    
    public Map<String, Map<String, String>> getSymbolMapping() {
        return symbolMapping;
    }
    
    public void setSymbolMapping(Map<String, Map<String, String>> symbolMapping) {
        this.symbolMapping = symbolMapping;
    }
    
    /**
     * シンボルマッピングを取得
     */
    public String mapSymbol(String exchange, String symbol) {
        if (symbolMapping == null || symbolMapping.get(exchange) == null) {
            return null;
        }
        return symbolMapping.get(exchange).get(symbol);
    }
    
    /**
     * Bitflyer設定
     */
    public static class Bitflyer {
        private boolean enabled = true;
        private String wsUrl = "wss://ws.lightstream.bitflyer.com/json-rpc";
        private long reconnectDelay = 5000;
        private int maxReconnectAttempts = 10;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getWsUrl() {
            return wsUrl;
        }
        
        public void setWsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
        }
        
        public long getReconnectDelay() {
            return reconnectDelay;
        }
        
        public void setReconnectDelay(long reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }
        
        public int getMaxReconnectAttempts() {
            return maxReconnectAttempts;
        }
        
        public void setMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
        }
    }
    
    /**
     * GMO設定
     */
    public static class Gmo {
        private boolean enabled = true;
        private String wsUrl = "wss://api.coin.z.com/ws/public/v1";
        private long reconnectDelay = 5000;
        private int maxReconnectAttempts = 10;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getWsUrl() {
            return wsUrl;
        }
        
        public void setWsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
        }
        
        public long getReconnectDelay() {
            return reconnectDelay;
        }
        
        public void setReconnectDelay(long reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }
        
        public int getMaxReconnectAttempts() {
            return maxReconnectAttempts;
        }
        
        public void setMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
        }
    }
    
    /**
     * マーケットボード設定
     */
    public static class Board {
        private int maxLevels = 10;
        
        public int getMaxLevels() {
            return maxLevels;
        }
        
        public void setMaxLevels(int maxLevels) {
            this.maxLevels = maxLevels;
        }
    }
    
    /**
     * 取引設定
     */
    public static class Trade {
        private int batchSize = 100;
        
        public int getBatchSize() {
            return batchSize;
        }
        
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}