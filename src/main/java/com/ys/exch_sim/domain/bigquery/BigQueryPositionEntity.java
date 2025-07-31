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
        this.totalBuyQty = position.getTotalBuyQty();
        this.totalBuyAmount = position.getTotalBuyAmount();
        this.totalSellQty = position.getTotalSellQty();
        this.totalSellAmount = position.getTotalSellAmount();
        this.netQty = position.getNetQty();
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
        entity.totalBuyQty = row.get("total_buy_qty") != null ? ((Number) row.get("total_buy_qty")).longValue() : null;
        entity.totalBuyAmount = row.get("total_buy_amount") != null ? ((Number) row.get("total_buy_amount")).doubleValue() : null;
        entity.totalSellQty = row.get("total_sell_qty") != null ? ((Number) row.get("total_sell_qty")).longValue() : null;
        entity.totalSellAmount = row.get("total_sell_amount") != null ? ((Number) row.get("total_sell_amount")).doubleValue() : null;
        entity.netQty = row.get("net_qty") != null ? ((Number) row.get("net_qty")).longValue() : null;
        entity.averageBuyPrice = row.get("average_buy_price") != null ? ((Number) row.get("average_buy_price")).doubleValue() : null;
        entity.averageSellPrice = row.get("average_sell_price") != null ? ((Number) row.get("average_sell_price")).doubleValue() : null;
        entity.realizedPnL = row.get("realized_pnl") != null ? ((Number) row.get("realized_pnl")).doubleValue() : null;
        entity.lastUpdated = (String) row.get("last_updated");
        return entity;
    }
    
    // Get BigQuery table ID
    public static TableId getTableId(String projectId, String datasetId) {
        return TableId.of(projectId, datasetId, TABLE_NAME);
    }
}