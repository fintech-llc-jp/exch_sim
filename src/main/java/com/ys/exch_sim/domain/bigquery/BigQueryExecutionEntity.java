package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.TableId;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.message.field.ExecStatus;
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
public class BigQueryExecutionEntity {
    
    public static final String TABLE_NAME = "executions";
    
    private String execId;
    private String orderId;
    private String username;
    private String symbol;
    private String execStatus;
    private Long lastPx;
    private Long lastQty;
    private String counterPartyUsername;
    private String createdAt; // ISO format string for BigQuery
    private Boolean isMarketMaker;
    private String side;
    private String clOrdId;
    private Integer priceMultiplier;
    private Integer qtyMultiplier;
    
    // Constructor from H2 Execution entity
    public BigQueryExecutionEntity(Execution execution) {
        this.execId = execution.getExecID().getId();
        this.orderId = execution.getOrderID();
        this.username = execution.getUsername();
        this.symbol = execution.getSymbol();
        this.execStatus = execution.getExecStatus().toString();
        this.lastPx = execution.getLastPxRaw();
        this.lastQty = execution.getLastQtyRaw();
        this.counterPartyUsername = execution.getCounterPartyUsername();
        this.createdAt = execution.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.isMarketMaker = execution.getIsMarketMaker();
        this.side = execution.getSide();
        // Order オブジェクトが利用可能な場合のみ ClOrdID を取得、そうでなければ orderID を使用
        try {
            this.clOrdId = execution.getOrder() != null ? execution.getOrder().getClOrdID().getId() : execution.getOrderID();
        } catch (UnsupportedOperationException e) {
            // Order オブジェクトが再構築できない場合（外部取引データなど）は orderID を使用
            this.clOrdId = execution.getOrderID();
        }
        // デフォルト値を設定（銘柄固有の値は別途実装が必要）
        this.priceMultiplier = 1;
        this.qtyMultiplier = 1000;
    }
    
    // Convert to BigQuery row data
    public Map<String, Object> toBigQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("exec_id", execId);
        row.put("order_id", orderId);
        row.put("username", username);
        row.put("symbol", symbol);
        row.put("exec_status", execStatus);
        row.put("last_px", lastPx);
        row.put("last_qty", lastQty);
        row.put("counter_party_username", counterPartyUsername);
        row.put("created_at", createdAt);
        row.put("is_market_maker", isMarketMaker);
        row.put("side", side);
        row.put("cl_ord_id", clOrdId);
        row.put("price_multiplier", priceMultiplier);
        row.put("qty_multiplier", qtyMultiplier);
        return row;
    }
    
    // Convert from BigQuery row data
    public static BigQueryExecutionEntity fromBigQueryRow(Map<String, Object> row) {
        BigQueryExecutionEntity entity = new BigQueryExecutionEntity();
        entity.execId = (String) row.get("exec_id");
        entity.orderId = (String) row.get("order_id");
        entity.username = (String) row.get("username");
        entity.symbol = (String) row.get("symbol");
        entity.execStatus = (String) row.get("exec_status");
        entity.lastPx = row.get("last_px") != null ? ((Number) row.get("last_px")).longValue() : null;
        entity.lastQty = row.get("last_qty") != null ? ((Number) row.get("last_qty")).longValue() : null;
        entity.counterPartyUsername = (String) row.get("counter_party_username");
        entity.createdAt = (String) row.get("created_at");
        entity.isMarketMaker = (Boolean) row.get("is_market_maker");
        entity.side = (String) row.get("side");
        return entity;
    }
    
    // Get BigQuery table ID
    public static TableId getTableId(String projectId, String datasetId) {
        return TableId.of(projectId, datasetId, TABLE_NAME);
    }
}