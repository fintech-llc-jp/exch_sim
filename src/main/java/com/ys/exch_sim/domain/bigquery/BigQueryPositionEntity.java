package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.TableId;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionEntity;
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
public class BigQueryPositionEntity {
    
    public static final String TABLE_NAME = "positions";
    
    private String username;
    private String symbol;
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
        row.put("id", username + "_" + symbol); // Composite key for BigQuery
        row.put("username", username);
        row.put("symbol", symbol);
        row.put("total_buy_qty", totalBuyQty);
        row.put("total_buy_amount", totalBuyAmount);
        row.put("total_sell_qty", totalSellQty);
        row.put("total_sell_amount", totalSellAmount);
        row.put("net_qty", netQty);
        row.put("average_buy_price", averageBuyPrice);
        row.put("average_sell_price", averageSellPrice);
        row.put("realized_pnl", realizedPnL);
        row.put("last_updated", lastUpdated);
        return row;
    }
    
    // Convert from BigQuery row data
    public static BigQueryPositionEntity fromBigQueryRow(Map<String, Object> row) {
        BigQueryPositionEntity entity = new BigQueryPositionEntity();
        entity.username = (String) row.get("username");
        entity.symbol = (String) row.get("symbol");
        
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
        position.setTotalBuyQty(totalBuyQty != null ? totalBuyQty / 1000.0 : 0.0);
        position.setTotalBuyAmount(totalBuyAmount != null ? totalBuyAmount : 0.0);
        position.setTotalSellQty(totalSellQty != null ? totalSellQty / 1000.0 : 0.0);
        position.setTotalSellAmount(totalSellAmount != null ? totalSellAmount : 0.0);
        position.setNetQty(netQty != null ? netQty / 1000.0 : 0.0);
        position.setAverageBuyPrice(averageBuyPrice != null ? averageBuyPrice : 0.0);
        position.setAverageSellPrice(averageSellPrice != null ? averageSellPrice : 0.0);
        position.setRealizedPnL(realizedPnL != null ? realizedPnL : 0.0);
        if (lastUpdated != null) {
            position.setLastUpdated(LocalDateTime.parse(lastUpdated, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else {
            position.setLastUpdated(LocalDateTime.now());
        }
        return position;
    }
    
    // Get BigQuery table ID
    public static TableId getTableId(String projectId, String datasetId) {
        return TableId.of(projectId, datasetId, TABLE_NAME);
    }
}