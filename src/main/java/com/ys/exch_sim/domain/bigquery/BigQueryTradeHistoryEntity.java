package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.TableId;
import com.ys.exch_sim.domain.position.TradeHistory;
import com.ys.exch_sim.domain.position.TradeHistoryEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BigQueryTradeHistoryEntity {
    
    public static final String TABLE_NAME = "trade_history";
    
    private String execId;
    private String username;
    private String symbol;
    private String side; // BUY or SELL
    private Double quantity;
    private Double price;
    private Double amount; // quantity * price
    private String counterPartyUsername;
    private String timestamp; // ISO format string for BigQuery
    private String clOrdId;
    private Boolean isMarketMaker;
    
    // Constructor from H2 TradeHistoryEntity
    public BigQueryTradeHistoryEntity(TradeHistoryEntity tradeHistoryEntity) {
        this.execId = tradeHistoryEntity.getExecId();
        this.username = tradeHistoryEntity.getUsername();
        this.symbol = tradeHistoryEntity.getSymbol();
        this.side = tradeHistoryEntity.getSide();
        this.quantity = tradeHistoryEntity.getQuantity();
        this.price = tradeHistoryEntity.getPrice();
        this.amount = tradeHistoryEntity.getAmount();
        this.counterPartyUsername = tradeHistoryEntity.getCounterPartyUsername();
        this.timestamp = tradeHistoryEntity.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.clOrdId = tradeHistoryEntity.getClOrdId();
        this.isMarketMaker = tradeHistoryEntity.getIsMarketMaker();
    }
    
    // Constructor from domain TradeHistory
    public BigQueryTradeHistoryEntity(TradeHistory tradeHistory) {
        this.execId = tradeHistory.getExecID();
        this.username = tradeHistory.getUsername();
        this.symbol = tradeHistory.getSymbol();
        this.side = tradeHistory.getSide();
        // Convert actual quantity to storage format (multiply by 1000)
        this.quantity = tradeHistory.getQuantity() * 1000.0;
        this.price = tradeHistory.getPrice();
        this.amount = tradeHistory.getAmount();
        this.counterPartyUsername = tradeHistory.getCounterPartyUsername();
        this.timestamp = tradeHistory.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.clOrdId = tradeHistory.getClOrdID();
        this.isMarketMaker = false; // Default value
    }
    
    // Convert to BigQuery row data
    public Map<String, Object> toBigQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("exec_id", execId);
        row.put("username", username);
        row.put("symbol", symbol);
        row.put("side", side);
        row.put("quantity", quantity);
        row.put("price", price);
        row.put("amount", amount);
        row.put("counter_party_username", counterPartyUsername);

        // Convert ISO string to BigQuery TIMESTAMP format (seconds.microseconds since epoch)
        if (timestamp != null) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                // Convert to seconds since epoch (BigQuery TIMESTAMP format)
                double ts = dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().getEpochSecond()
                    + dateTime.getNano() / 1_000_000_000.0;
                row.put("timestamp", ts);
            } catch (Exception e) {
                // If parsing fails, use current timestamp
                row.put("timestamp", System.currentTimeMillis() / 1000.0);
            }
        } else {
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        }

        row.put("cl_ord_id", clOrdId);
        row.put("is_market_maker", isMarketMaker);
        return row;
    }
    
    // Convert from BigQuery row data
    public static BigQueryTradeHistoryEntity fromBigQueryRow(Map<String, Object> row) {
        BigQueryTradeHistoryEntity entity = new BigQueryTradeHistoryEntity();
        entity.execId = (String) row.get("exec_id");
        entity.username = (String) row.get("username");
        entity.symbol = (String) row.get("symbol");
        entity.side = (String) row.get("side");
        // Safe conversion for quantity
        Object quantityObj = row.get("quantity");
        if (quantityObj != null) {
            if (quantityObj instanceof Number) {
                entity.quantity = ((Number) quantityObj).doubleValue();
            } else if (quantityObj instanceof String) {
                entity.quantity = Double.parseDouble((String) quantityObj);
            }
        }
        
        // Safe conversion for price
        Object priceObj = row.get("price");
        if (priceObj != null) {
            if (priceObj instanceof Number) {
                entity.price = ((Number) priceObj).doubleValue();
            } else if (priceObj instanceof String) {
                entity.price = Double.parseDouble((String) priceObj);
            }
        }
        
        // Safe conversion for amount
        Object amountObj = row.get("amount");
        if (amountObj != null) {
            if (amountObj instanceof Number) {
                entity.amount = ((Number) amountObj).doubleValue();
            } else if (amountObj instanceof String) {
                entity.amount = Double.parseDouble((String) amountObj);
            }
        }
        entity.counterPartyUsername = (String) row.get("counter_party_username");
        entity.timestamp = (String) row.get("timestamp");
        entity.clOrdId = (String) row.get("cl_ord_id");
        
        // Safe conversion for isMarketMaker
        Object isMarketMakerObj = row.get("is_market_maker");
        if (isMarketMakerObj != null) {
            if (isMarketMakerObj instanceof Boolean) {
                entity.isMarketMaker = (Boolean) isMarketMakerObj;
            } else if (isMarketMakerObj instanceof String) {
                entity.isMarketMaker = Boolean.parseBoolean((String) isMarketMakerObj);
            }
        } else {
            entity.isMarketMaker = false; // Default value
        }
        
        return entity;
    }
    
    // Convert to domain TradeHistory
    public TradeHistory toTradeHistory() {
        // Convert stored quantity (may be 1000x) to actual quantity
        double actualQuantity = quantity != null ? quantity / 1000.0 : 0.0;
        
        TradeHistory tradeHistory = new TradeHistory(
            execId,
            username,
            symbol,
            side,
            actualQuantity,
            price != null ? price : 0.0,
            counterPartyUsername,
            clOrdId
        );
        if (timestamp != null) {
            try {
                // Try parsing as ISO format first
                tradeHistory.setTimestamp(LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            } catch (Exception e) {
                try {
                    // If that fails, try parsing as Unix timestamp (seconds)
                    double unixTimestamp = Double.parseDouble(timestamp);
                    tradeHistory.setTimestamp(LocalDateTime.ofEpochSecond((long) unixTimestamp, 
                        (int) ((unixTimestamp % 1) * 1_000_000_000), 
                        java.time.ZoneOffset.UTC).atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
                } catch (Exception ex) {
                    // If all parsing fails, use current time
                    tradeHistory.setTimestamp(LocalDateTime.now());
                }
            }
        } else {
            tradeHistory.setTimestamp(LocalDateTime.now());
        }
        return tradeHistory;
    }
    
    // Get BigQuery table ID
    public static TableId getTableId(String projectId, String datasetId) {
        return TableId.of(projectId, datasetId, TABLE_NAME);
    }
}