package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.TableId;
import com.ys.exch_sim.domain.order_exec.Execution;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
      this.clOrdId =
          execution.getOrder() != null
              ? execution.getOrder().getClOrdID().getId()
              : execution.getOrderID();
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

    // Convert ISO string to BigQuery TIMESTAMP format (seconds.microseconds since epoch)
    if (createdAt != null) {
      try {
        LocalDateTime dateTime =
            LocalDateTime.parse(createdAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // Convert to seconds since epoch (BigQuery TIMESTAMP format)
        double ts =
            dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().getEpochSecond()
                + dateTime.getNano() / 1_000_000_000.0;
        row.put("created_at", ts);
      } catch (Exception e) {
        // If parsing fails, use current timestamp
        row.put("created_at", System.currentTimeMillis() / 1000.0);
      }
    } else {
      row.put("created_at", System.currentTimeMillis() / 1000.0);
    }

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

    // Safe conversion for lastPx (can be Number or String)
    Object lastPxObj = row.get("last_px");
    if (lastPxObj != null) {
      if (lastPxObj instanceof Number) {
        entity.lastPx = ((Number) lastPxObj).longValue();
      } else if (lastPxObj instanceof String) {
        try {
          entity.lastPx = Long.parseLong((String) lastPxObj);
        } catch (NumberFormatException e) {
          entity.lastPx = null;
        }
      }
    } else {
      entity.lastPx = null;
    }

    // Safe conversion for lastQty (can be Number or String)
    Object lastQtyObj = row.get("last_qty");
    if (lastQtyObj != null) {
      if (lastQtyObj instanceof Number) {
        entity.lastQty = ((Number) lastQtyObj).longValue();
      } else if (lastQtyObj instanceof String) {
        try {
          entity.lastQty = Long.parseLong((String) lastQtyObj);
        } catch (NumberFormatException e) {
          entity.lastQty = null;
        }
      }
    } else {
      entity.lastQty = null;
    }

    entity.counterPartyUsername = (String) row.get("counter_party_username");

    // Safe conversion for createdAt (can be String, Number (timestamp), or null)
    Object createdAtObj = row.get("created_at");
    if (createdAtObj != null) {
      if (createdAtObj instanceof String) {
        entity.createdAt = (String) createdAtObj;
      } else if (createdAtObj instanceof Number) {
        // Convert timestamp to ISO string
        double timestamp = ((Number) createdAtObj).doubleValue();
        LocalDateTime dateTime =
            LocalDateTime.ofEpochSecond(
                (long) timestamp,
                (int) ((timestamp % 1) * 1_000_000_000),
                java.time.ZoneOffset.UTC);
        entity.createdAt = dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
    } else {
      entity.createdAt = null;
    }

    // Safe conversion for isMarketMaker (can be Boolean or String)
    Object isMarketMakerObj = row.get("is_market_maker");
    if (isMarketMakerObj != null) {
      if (isMarketMakerObj instanceof Boolean) {
        entity.isMarketMaker = (Boolean) isMarketMakerObj;
      } else if (isMarketMakerObj instanceof String) {
        entity.isMarketMaker = Boolean.parseBoolean((String) isMarketMakerObj);
      } else {
        entity.isMarketMaker = false; // Default value
      }
    } else {
      entity.isMarketMaker = false; // Default value
    }

    entity.side = (String) row.get("side");
    return entity;
  }

  // Get BigQuery table ID
  public static TableId getTableId(String projectId, String datasetId) {
    return TableId.of(projectId, datasetId, TABLE_NAME);
  }
}
