package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.TableId;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BigQueryPositionEntity {
    
    public static final String TABLE_NAME = "positions";
    
    private String username;
    private String symbol;
    private String unit;
    private Long totalBuyQty;
    private Double totalBuyAmount;
    private Long totalSellQty;
    private Double totalSellAmount;
    private Long netQty;
    private Double averageBuyPrice;
    private Double averageSellPrice;
    private Double realizedPnL;
    private String lastUpdated; // ISO format string for BigQuery
    
    // Constructor from H2 PositionEntity
    public BigQueryPositionEntity(PositionEntity positionEntity) {
        this.username = positionEntity.getUsername();
        this.symbol = positionEntity.getSymbol();
        this.unit = positionEntity.getUnit();
        this.totalBuyQty = positionEntity.getTotalBuyQty();
        this.totalBuyAmount = positionEntity.getTotalBuyAmount();
        this.totalSellQty = positionEntity.getTotalSellQty();
        this.totalSellAmount = positionEntity.getTotalSellAmount();
        this.netQty = positionEntity.getNetQty();
        this.averageBuyPrice = positionEntity.getAverageBuyPrice();
        this.averageSellPrice = positionEntity.getAverageSellPrice();
        this.realizedPnL = positionEntity.getRealizedPnL();
        this.lastUpdated = positionEntity.getLastUpdated().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    // Constructor from domain Position
    public BigQueryPositionEntity(Position position) {
        this.username = position.getUsername();
        this.symbol = position.getSymbol();
        this.unit = position.getUnit();
        // Convert double to long (multiply by 1000 for storage)
        this.totalBuyQty = (long) (position.getTotalBuyQty() * 1000);
        this.totalBuyAmount = position.getTotalBuyAmount();
        this.totalSellQty = (long) (position.getTotalSellQty() * 1000);
        this.totalSellAmount = position.getTotalSellAmount();
        this.netQty = (long) (position.getNetQty() * 1000);
        this.averageBuyPrice = position.getAverageBuyPrice();
        this.averageSellPrice = position.getAverageSellPrice();
        this.realizedPnL = position.getRealizedPnL();
        this.lastUpdated = position.getLastUpdated().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    // Convert to BigQuery row data
    public Map<String, Object> toBigQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("username", username);
        row.put("symbol", symbol);
        row.put("unit", unit);
        row.put("total_buy_qty", totalBuyQty);
        row.put("total_buy_amount", totalBuyAmount);
        row.put("total_sell_qty", totalSellQty);
        row.put("total_sell_amount", totalSellAmount);
        row.put("net_qty", netQty);
        row.put("average_buy_price", averageBuyPrice);
        row.put("average_sell_price", averageSellPrice);
        row.put("realized_pnl", realizedPnL);

        // Convert ISO string to BigQuery TIMESTAMP format (seconds.microseconds since epoch)
        if (lastUpdated != null) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(lastUpdated, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                // Convert to seconds since epoch (BigQuery TIMESTAMP format)
                double timestamp = dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().getEpochSecond()
                    + dateTime.getNano() / 1_000_000_000.0;
                row.put("last_updated", timestamp);
            } catch (DateTimeParseException e) {
                // If parsing fails, use current timestamp
                row.put("last_updated", System.currentTimeMillis() / 1000.0);
            }
        } else {
            row.put("last_updated", System.currentTimeMillis() / 1000.0);
        }

        return row;
    }
    
    // Convert from BigQuery row data
    public static BigQueryPositionEntity fromBigQueryRow(Map<String, Object> row) {
        BigQueryPositionEntity entity = new BigQueryPositionEntity();
        entity.username = (String) row.get("username");
        entity.symbol = (String) row.get("symbol");
        entity.unit = (String) row.get("unit");
        
        // Safe conversion for totalBuyQty
        Object totalBuyQtyObj = row.get("total_buy_qty");
        if (totalBuyQtyObj != null) {
            if (totalBuyQtyObj instanceof Number) {
                entity.totalBuyQty = ((Number) totalBuyQtyObj).longValue();
            } else if (totalBuyQtyObj instanceof String) {
                entity.totalBuyQty = Long.parseLong((String) totalBuyQtyObj);
            }
        }
        
        // Safe conversion for totalBuyAmount
        Object totalBuyAmountObj = row.get("total_buy_amount");
        if (totalBuyAmountObj != null) {
            if (totalBuyAmountObj instanceof Number) {
                entity.totalBuyAmount = ((Number) totalBuyAmountObj).doubleValue();
            } else if (totalBuyAmountObj instanceof String) {
                entity.totalBuyAmount = Double.parseDouble((String) totalBuyAmountObj);
            }
        }
        
        // Safe conversion for totalSellQty
        Object totalSellQtyObj = row.get("total_sell_qty");
        if (totalSellQtyObj != null) {
            if (totalSellQtyObj instanceof Number) {
                entity.totalSellQty = ((Number) totalSellQtyObj).longValue();
            } else if (totalSellQtyObj instanceof String) {
                entity.totalSellQty = Long.parseLong((String) totalSellQtyObj);
            }
        }
        
        // Safe conversion for totalSellAmount
        Object totalSellAmountObj = row.get("total_sell_amount");
        if (totalSellAmountObj != null) {
            if (totalSellAmountObj instanceof Number) {
                entity.totalSellAmount = ((Number) totalSellAmountObj).doubleValue();
            } else if (totalSellAmountObj instanceof String) {
                entity.totalSellAmount = Double.parseDouble((String) totalSellAmountObj);
            }
        }
        
        // Safe conversion for netQty
        Object netQtyObj = row.get("net_qty");
        if (netQtyObj != null) {
            if (netQtyObj instanceof Number) {
                entity.netQty = ((Number) netQtyObj).longValue();
            } else if (netQtyObj instanceof String) {
                entity.netQty = Long.parseLong((String) netQtyObj);
            }
        }
        
        // Safe conversion for averageBuyPrice
        Object averageBuyPriceObj = row.get("average_buy_price");
        if (averageBuyPriceObj != null) {
            if (averageBuyPriceObj instanceof Number) {
                entity.averageBuyPrice = ((Number) averageBuyPriceObj).doubleValue();
            } else if (averageBuyPriceObj instanceof String) {
                entity.averageBuyPrice = Double.parseDouble((String) averageBuyPriceObj);
            }
        }
        
        // Safe conversion for averageSellPrice
        Object averageSellPriceObj = row.get("average_sell_price");
        if (averageSellPriceObj != null) {
            if (averageSellPriceObj instanceof Number) {
                entity.averageSellPrice = ((Number) averageSellPriceObj).doubleValue();
            } else if (averageSellPriceObj instanceof String) {
                entity.averageSellPrice = Double.parseDouble((String) averageSellPriceObj);
            }
        }
        
        // Safe conversion for realizedPnL
        Object realizedPnLObj = row.get("realized_pnl");
        if (realizedPnLObj != null) {
            if (realizedPnLObj instanceof Number) {
                entity.realizedPnL = ((Number) realizedPnLObj).doubleValue();
            } else if (realizedPnLObj instanceof String) {
                entity.realizedPnL = Double.parseDouble((String) realizedPnLObj);
            }
        }
        
        entity.lastUpdated = (String) row.get("last_updated");
        return entity;
    }
    
    // Convert to domain Position
    public Position toPosition() {
        Position position = new Position(username, symbol);
        // Convert long to double (divide by 1000 from storage)
        double actualTotalBuyQty = totalBuyQty != null ? totalBuyQty / 1000.0 : 0.0;
        double actualTotalSellQty = totalSellQty != null ? totalSellQty / 1000.0 : 0.0;
        double actualNetQty = netQty != null ? netQty / 1000.0 : 0.0;
        
        position.setTotalBuyQty(actualTotalBuyQty);
        position.setTotalBuyAmount(totalBuyAmount != null ? totalBuyAmount : 0.0);
        position.setTotalSellQty(actualTotalSellQty);
        position.setTotalSellAmount(totalSellAmount != null ? totalSellAmount : 0.0);
        position.setNetQty(actualNetQty);
        
        // Set unit (use stored unit if available, otherwise determine from symbol)
        if (unit != null && !unit.isEmpty()) {
            position.setUnit(unit);
        } else {
            position.setUnit(determineUnit(symbol));
        }
        
        // Recalculate average prices to ensure consistency
        if (actualTotalBuyQty > 0 && totalBuyAmount != null && totalBuyAmount > 0) {
            position.setAverageBuyPrice(totalBuyAmount / actualTotalBuyQty);
        } else {
            position.setAverageBuyPrice(0.0);
        }
        
        if (actualTotalSellQty > 0 && totalSellAmount != null && totalSellAmount > 0) {
            position.setAverageSellPrice(totalSellAmount / actualTotalSellQty);
        } else {
            position.setAverageSellPrice(0.0);
        }
        
        position.setRealizedPnL(realizedPnL != null ? realizedPnL : 0.0);
        if (lastUpdated != null) {
            try {
                // Try parsing as ISO format first
                position.setLastUpdated(LocalDateTime.parse(lastUpdated, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            } catch (DateTimeParseException e) {
                try {
                    // If that fails, try parsing as Unix timestamp (seconds)
                    double timestamp = Double.parseDouble(lastUpdated);
                    position.setLastUpdated(LocalDateTime.ofEpochSecond((long) timestamp, 
                        (int) ((timestamp % 1) * 1_000_000_000), 
                        java.time.ZoneOffset.UTC).atZone(java.time.ZoneOffset.UTC).toLocalDateTime());
                } catch (Exception ex) {
                    // If all parsing fails, use current time
                    position.setLastUpdated(LocalDateTime.now());
                }
            }
        } else {
            position.setLastUpdated(LocalDateTime.now());
        }
        return position;
    }
    
    // Helper method to determine unit from symbol
    private String determineUnit(String symbol) {
        if ("JPY".equals(symbol)) {
            return "JPY";
        } else if (symbol.contains("BTC")) {
            return "BTC";
        } else if (symbol.contains("ETH")) {
            return "ETH";
        } else {
            return "UNIT"; // デフォルト
        }
    }
    
    // Get BigQuery table ID
    public static TableId getTableId(String projectId, String datasetId) {
        return TableId.of(projectId, datasetId, TABLE_NAME);
    }
}