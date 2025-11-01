package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.data-migration.bigquery-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class BigQueryService {

  private final BigQuery bigQuery;

  @Value("${spring.cloud.gcp.project-id}")
  private String projectId;

  @Value("${spring.cloud.gcp.bigquery.dataset-name}")
  private String datasetName;

  /** Create BigQuery tables if they don't exist */
  public void createTablesIfNotExist() {
    try {
      createExecutionsTable();
      createPositionsTable();
      createTradeHistoryTable();
      createUsersTable();
      log.info("BigQuery tables created successfully");
    } catch (Exception e) {
      log.error("Error creating BigQuery tables", e);
      throw new RuntimeException("Failed to create BigQuery tables", e);
    }
  }

  /** Insert execution data into BigQuery (synchronous version for initialization) */
  public void insertExecution(BigQueryExecutionEntity execution) {
    try {
      TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = execution.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting execution to BigQuery: {}", response.getInsertErrors());
        throw new RuntimeException("Failed to insert execution to BigQuery");
      }

      log.debug("Successfully inserted execution to BigQuery: {}", execution.getExecId());
    } catch (Exception e) {
      log.error("Error inserting execution to BigQuery", e);
      throw new RuntimeException("Failed to insert execution to BigQuery", e);
    }
  }


  /** Upsert position data into BigQuery (synchronous version) */
  public void insertPosition(BigQueryPositionEntity position) {
    try {
      // First try a simple insert for new positions
      TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = position.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();
      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (!response.hasErrors()) {
        log.debug("Successfully inserted new position to BigQuery: {}_{}", 
                  position.getUsername(), position.getSymbol());
        return;
      }

      // If insert fails (likely due to duplicate key), use MERGE to update
      log.debug("Insert failed, attempting upsert for position: {}_{}", 
                position.getUsername(), position.getSymbol());
      
      String mergeQuery = String.format(
        """
        MERGE `%s.%s.positions` AS target
        USING (
          SELECT 
            @username AS username,
            @symbol AS symbol,
            @total_buy_qty AS total_buy_qty,
            @total_buy_amount AS total_buy_amount,
            @total_sell_qty AS total_sell_qty,
            @total_sell_amount AS total_sell_amount,
            @net_qty AS net_qty,
            @average_buy_price AS average_buy_price,
            @average_sell_price AS average_sell_price,
            @realized_pnl AS realized_pnl,
            @last_updated AS last_updated
        ) AS source
        ON target.username = source.username AND target.symbol = source.symbol
        WHEN MATCHED THEN
          UPDATE SET 
            total_buy_qty = source.total_buy_qty,
            total_buy_amount = source.total_buy_amount,
            total_sell_qty = source.total_sell_qty,
            total_sell_amount = source.total_sell_amount,
            net_qty = source.net_qty,
            average_buy_price = source.average_buy_price,
            average_sell_price = source.average_sell_price,
            realized_pnl = source.realized_pnl,
            last_updated = source.last_updated
        WHEN NOT MATCHED THEN
          INSERT (username, symbol, total_buy_qty, total_buy_amount, total_sell_qty, 
                  total_sell_amount, net_qty, average_buy_price, average_sell_price, 
                  realized_pnl, last_updated)
          VALUES (source.username, source.symbol, source.total_buy_qty, 
                  source.total_buy_amount, source.total_sell_qty, source.total_sell_amount, 
                  source.net_qty, source.average_buy_price, source.average_sell_price, 
                  source.realized_pnl, source.last_updated)
        """,
        projectId, datasetName);
      
      // Convert timestamp from seconds (double) to microseconds (long) for BigQuery
      Double lastUpdatedSeconds = (Double) row.get("last_updated");
      Long lastUpdatedMicros = (long) (lastUpdatedSeconds * 1_000_000);

      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(mergeQuery)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string((String) row.get("username")))
        .addNamedParameter("symbol", com.google.cloud.bigquery.QueryParameterValue.string((String) row.get("symbol")))
        .addNamedParameter("total_buy_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("total_buy_qty")))
        .addNamedParameter("total_buy_amount", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("total_buy_amount")))
        .addNamedParameter("total_sell_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("total_sell_qty")))
        .addNamedParameter("total_sell_amount", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("total_sell_amount")))
        .addNamedParameter("net_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("net_qty")))
        .addNamedParameter("average_buy_price", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("average_buy_price")))
        .addNamedParameter("average_sell_price", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("average_sell_price")))
        .addNamedParameter("realized_pnl", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("realized_pnl")))
        .addNamedParameter("last_updated", com.google.cloud.bigquery.QueryParameterValue.timestamp(lastUpdatedMicros))
        .build();

      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null || queryJob.getStatus().getError() != null) {
        String error = queryJob != null ? queryJob.getStatus().getError().toString() : "Unknown error";
        log.error("Error upserting position to BigQuery: {}", error);
        throw new RuntimeException("Failed to upsert position to BigQuery: " + error);
      }

      log.debug("Successfully upserted position to BigQuery: {}_{}", 
                position.getUsername(), position.getSymbol());
                
    } catch (Exception e) {
      log.error("Error saving position to BigQuery", e);
      throw new RuntimeException("Failed to save position to BigQuery", e);
    }
  }


  /** Insert trade history data into BigQuery (synchronous version) */
  public void insertTradeHistory(BigQueryTradeHistoryEntity tradeHistory) {
    try {
      TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = tradeHistory.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting trade history to BigQuery: {}", response.getInsertErrors());
        throw new RuntimeException("Failed to insert trade history to BigQuery");
      }

      log.debug("Successfully inserted trade history to BigQuery: {}", tradeHistory.getExecId());
    } catch (Exception e) {
      log.error("Error inserting trade history to BigQuery", e);
      throw new RuntimeException("Failed to insert trade history to BigQuery", e);
    }
  }


  /** Query position by username and symbol from BigQuery */
  public BigQueryPositionEntity queryPosition(String username, String symbol) {
    try {
      String query = String.format(
        "SELECT * FROM `%s.%s.positions` WHERE username = @username AND symbol = @symbol ORDER BY last_updated DESC LIMIT 1",
        projectId, datasetName);
      
      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string(username))
        .addNamedParameter("symbol", com.google.cloud.bigquery.QueryParameterValue.string(symbol))
        .build();
      
      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null) {
        log.error("❌ BigQuery job was null when querying position for user: {} symbol: {}", username, symbol);
        return null;
      }
      
      if (queryJob.getStatus().getError() != null) {
        log.error("❌ BigQuery job failed when querying position for user: {} symbol: {} - Error: {}", 
                  username, symbol, queryJob.getStatus().getError().getMessage());
        return null;
      }
      
      TableResult result = queryJob.getQueryResults();
      if (result.getTotalRows() == 0) {
        log.info("📊 No position found in BigQuery for user: {} symbol: {}", username, symbol);
        return null;
      }
      
      com.google.cloud.bigquery.FieldValueList row = result.iterateAll().iterator().next();
      Map<String, Object> rowMap = new HashMap<>();
      for (com.google.cloud.bigquery.Field field : result.getSchema().getFields()) {
        String fieldName = field.getName();
        com.google.cloud.bigquery.FieldValue fieldValue = row.get(fieldName);
        if (!fieldValue.isNull()) {
          rowMap.put(fieldName, fieldValue.getValue());
        }
      }
      
      BigQueryPositionEntity position = BigQueryPositionEntity.fromBigQueryRow(rowMap);
      log.info("📊 Found position in BigQuery for user: {} symbol: {} - NetQty: {}", username, symbol, position.getNetQty());
      return position;
      
    } catch (Exception e) {
      log.error("❌ Failed to query position from BigQuery for user: {} symbol: {} - Error: {}", username, symbol, e.getMessage(), e);
      return null;
    }
  }

  /** Query all positions by username from BigQuery */
  public List<BigQueryPositionEntity> queryAllPositions(String username) {
    try {
      String query = String.format(
        "SELECT * FROM `%s.%s.positions` WHERE username = @username ORDER BY last_updated DESC",
        projectId, datasetName);
      
      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string(username))
        .build();
      
      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null) {
        log.error("BigQuery job was null when querying positions for user: {}", username);
        return List.of();
      }
      
      if (queryJob.getStatus().getError() != null) {
        log.error("BigQuery job failed when querying positions for user: {} - Error: {}", 
                  username, queryJob.getStatus().getError().getMessage());
        return List.of();
      }
      
      TableResult result = queryJob.getQueryResults();
      List<BigQueryPositionEntity> positions = new ArrayList<>();
      
      for (com.google.cloud.bigquery.FieldValueList row : result.iterateAll()) {
        Map<String, Object> rowMap = new HashMap<>();
        for (com.google.cloud.bigquery.Field field : result.getSchema().getFields()) {
          String fieldName = field.getName();
          com.google.cloud.bigquery.FieldValue fieldValue = row.get(fieldName);
          if (!fieldValue.isNull()) {
            rowMap.put(fieldName, fieldValue.getValue());
          }
        }
        positions.add(BigQueryPositionEntity.fromBigQueryRow(rowMap));
      }
      
      log.info("📊 BigQuery positions query completed for user: {} - Found {} positions", username, positions.size());
      return positions;
      
    } catch (Exception e) {
      log.error("❌ Failed to query positions from BigQuery for user: {} - Error: {}", username, e.getMessage(), e);
      return List.of();
    }
  }

  /** Query trade history by username from BigQuery */
  public List<BigQueryTradeHistoryEntity> queryTradeHistory(String username) {
    try {
      String query = String.format(
        "SELECT * FROM `%s.%s.trade_history` WHERE username = @username ORDER BY timestamp DESC",
        projectId, datasetName);
      
      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string(username))
        .build();
      
      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null || queryJob.getStatus().getError() != null) {
        log.error("Error querying trade history from BigQuery for user: {}", username);
        return List.of();
      }
      
      TableResult result = queryJob.getQueryResults();
      List<BigQueryTradeHistoryEntity> tradeHistories = new ArrayList<>();
      
      for (com.google.cloud.bigquery.FieldValueList row : result.iterateAll()) {
        Map<String, Object> rowMap = new HashMap<>();
        for (com.google.cloud.bigquery.Field field : result.getSchema().getFields()) {
          String fieldName = field.getName();
          com.google.cloud.bigquery.FieldValue fieldValue = row.get(fieldName);
          if (!fieldValue.isNull()) {
            rowMap.put(fieldName, fieldValue.getValue());
          }
        }
        tradeHistories.add(BigQueryTradeHistoryEntity.fromBigQueryRow(rowMap));
      }
      
      return tradeHistories;
      
    } catch (Exception e) {
      log.error("Error querying trade history from BigQuery for user: {}", username, e);
      return List.of();
    }
  }

  /** Query trade history by username and symbol from BigQuery */
  public List<BigQueryTradeHistoryEntity> queryTradeHistory(String username, String symbol) {
    try {
      String query = String.format(
        "SELECT * FROM `%s.%s.trade_history` WHERE username = @username AND symbol = @symbol ORDER BY timestamp DESC",
        projectId, datasetName);
      
      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string(username))
        .addNamedParameter("symbol", com.google.cloud.bigquery.QueryParameterValue.string(symbol))
        .build();
      
      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null || queryJob.getStatus().getError() != null) {
        log.error("Error querying trade history from BigQuery for user: {} symbol: {}", username, symbol);
        return List.of();
      }
      
      TableResult result = queryJob.getQueryResults();
      List<BigQueryTradeHistoryEntity> tradeHistories = new ArrayList<>();
      
      for (com.google.cloud.bigquery.FieldValueList row : result.iterateAll()) {
        Map<String, Object> rowMap = new HashMap<>();
        for (com.google.cloud.bigquery.Field field : result.getSchema().getFields()) {
          String fieldName = field.getName();
          com.google.cloud.bigquery.FieldValue fieldValue = row.get(fieldName);
          if (!fieldValue.isNull()) {
            rowMap.put(fieldName, fieldValue.getValue());
          }
        }
        tradeHistories.add(BigQueryTradeHistoryEntity.fromBigQueryRow(rowMap));
      }
      
      return tradeHistories;
      
    } catch (Exception e) {
      log.error("Error querying trade history from BigQuery for user: {} symbol: {}", username, symbol, e);
      return List.of();
    }
  }

  /** Query trade history by username with limit from BigQuery */
  public List<BigQueryTradeHistoryEntity> queryTradeHistory(String username, int limit) {
    try {
      String query = String.format(
        "SELECT * FROM `%s.%s.trade_history` WHERE username = @username ORDER BY timestamp DESC LIMIT @limit",
        projectId, datasetName);
      
      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string(username))
        .addNamedParameter("limit", com.google.cloud.bigquery.QueryParameterValue.int64(limit))
        .build();
      
      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      
      queryJob = queryJob.waitFor();
      
      if (queryJob == null || queryJob.getStatus().getError() != null) {
        log.error("Error querying trade history from BigQuery for user: {} with limit: {}", username, limit);
        return List.of();
      }
      
      TableResult result = queryJob.getQueryResults();
      List<BigQueryTradeHistoryEntity> tradeHistories = new ArrayList<>();
      
      for (com.google.cloud.bigquery.FieldValueList row : result.iterateAll()) {
        Map<String, Object> rowMap = new HashMap<>();
        for (com.google.cloud.bigquery.Field field : result.getSchema().getFields()) {
          String fieldName = field.getName();
          com.google.cloud.bigquery.FieldValue fieldValue = row.get(fieldName);
          if (!fieldValue.isNull()) {
            rowMap.put(fieldName, fieldValue.getValue());
          }
        }
        tradeHistories.add(BigQueryTradeHistoryEntity.fromBigQueryRow(rowMap));
      }
      
      return tradeHistories;
      
    } catch (Exception e) {
      log.error("Error querying trade history from BigQuery for user: {} with limit: {}", username, limit, e);
      return List.of();
    }
  }

  /** Query executions from BigQuery (simplified version) */
  public void queryExecutions(String whereClause) {
    // TODO: Implement query functionality when needed
    log.info("Query executions method called with whereClause: {}", whereClause);
  }

  /** Register a new user in BigQuery */
  public void registerUser(String username, String encodedPassword, List<String> roles) {
    try {
      TableId tableId = TableId.of(projectId, datasetName, "users");

      String currentTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      Map<String, Object> row =
          Map.of(
              "username", username,
              "password", encodedPassword,
              "roles", roles,
              "created_at", currentTime,
              "updated_at", currentTime);

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting user to BigQuery: {}", response.getInsertErrors());
        throw new RuntimeException("Failed to insert user to BigQuery");
      }

      log.info("Successfully registered user in BigQuery: {}", username);
    } catch (Exception e) {
      log.error("Error registering user in BigQuery: {}", username, e);
      throw new RuntimeException("Failed to register user in BigQuery", e);
    }
  }

  /** Check if user exists in BigQuery */
  public boolean userExists(String username) {
    try {
      String query =
          String.format(
              "SELECT COUNT(*) as count FROM `%s.%s.users` WHERE username = @username",
              projectId, datasetName);

      QueryJobConfiguration queryConfig =
          QueryJobConfiguration.newBuilder(query)
              .addNamedParameter(
                  "username", com.google.cloud.bigquery.QueryParameterValue.string(username))
              .build();

      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());

      queryJob = queryJob.waitFor();

      if (queryJob == null || queryJob.getStatus().getError() != null) {
        log.error("Error checking user existence in BigQuery: {}", username);
        return false;
      }

      TableResult result = queryJob.getQueryResults();
      if (result.getTotalRows() == 0) {
        return false;
      }

      com.google.cloud.bigquery.FieldValueList row = result.iterateAll().iterator().next();
      long count = row.get("count").getLongValue();

      return count > 0;
    } catch (Exception e) {
      log.error("Error checking user existence in BigQuery: {}", username, e);
      return false;
    }
  }

  private void createExecutionsTable() {
    TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);

    Schema schema =
        Schema.of(
            Field.of("exec_id", StandardSQLTypeName.STRING),
            Field.of("order_id", StandardSQLTypeName.STRING),
            Field.of("username", StandardSQLTypeName.STRING),
            Field.of("symbol", StandardSQLTypeName.STRING),
            Field.of("exec_status", StandardSQLTypeName.STRING),
            Field.of("last_px", StandardSQLTypeName.INT64),
            Field.of("last_qty", StandardSQLTypeName.INT64),
            Field.of("counter_party_username", StandardSQLTypeName.STRING),
            Field.of("created_at", StandardSQLTypeName.TIMESTAMP),
            Field.of("is_market_maker", StandardSQLTypeName.BOOL),
            Field.of("side", StandardSQLTypeName.STRING));

    createTableIfNotExists(tableId, schema);
  }

  private void createPositionsTable() {
    TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);

    Schema schema =
        Schema.of(
            Field.of("id", StandardSQLTypeName.STRING),
            Field.of("username", StandardSQLTypeName.STRING),
            Field.of("symbol", StandardSQLTypeName.STRING),
            Field.of("total_buy_qty", StandardSQLTypeName.INT64),
            Field.of("total_buy_amount", StandardSQLTypeName.FLOAT64),
            Field.of("total_sell_qty", StandardSQLTypeName.INT64),
            Field.of("total_sell_amount", StandardSQLTypeName.FLOAT64),
            Field.of("net_qty", StandardSQLTypeName.INT64),
            Field.of("average_buy_price", StandardSQLTypeName.FLOAT64),
            Field.of("average_sell_price", StandardSQLTypeName.FLOAT64),
            Field.of("realized_pnl", StandardSQLTypeName.FLOAT64),
            Field.of("last_updated", StandardSQLTypeName.TIMESTAMP));

    createTableIfNotExists(tableId, schema);
  }

  private void createTradeHistoryTable() {
    TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);

    Schema schema =
        Schema.of(
            Field.of("exec_id", StandardSQLTypeName.STRING),
            Field.of("username", StandardSQLTypeName.STRING),
            Field.of("symbol", StandardSQLTypeName.STRING),
            Field.of("side", StandardSQLTypeName.STRING),
            Field.of("quantity", StandardSQLTypeName.FLOAT64),
            Field.of("price", StandardSQLTypeName.FLOAT64),
            Field.of("amount", StandardSQLTypeName.FLOAT64),
            Field.of("counter_party_username", StandardSQLTypeName.STRING),
            Field.of("timestamp", StandardSQLTypeName.TIMESTAMP),
            Field.of("cl_ord_id", StandardSQLTypeName.STRING),
            Field.of("is_market_maker", StandardSQLTypeName.BOOL));

    createTableIfNotExists(tableId, schema);
  }

  private void createUsersTable() {
    TableId tableId = TableId.of(projectId, datasetName, "users");

    Schema schema =
        Schema.of(
            Field.of("username", StandardSQLTypeName.STRING),
            Field.of("password", StandardSQLTypeName.STRING),
            Field.of("roles", StandardSQLTypeName.STRING),
            Field.of("created_at", StandardSQLTypeName.STRING),
            Field.of("updated_at", StandardSQLTypeName.STRING));

    createTableIfNotExists(tableId, schema);
  }

  private void createTableIfNotExists(TableId tableId, Schema schema) {
    try {
      Table table = bigQuery.getTable(tableId);
      if (table == null) {
        TableDefinition tableDefinition = StandardTableDefinition.of(schema);
        TableInfo tableInfo = TableInfo.newBuilder(tableId, tableDefinition).build();
        bigQuery.create(tableInfo);
        log.info("Created BigQuery table: {}", tableId);
      } else {
        log.info("BigQuery table already exists: {}", tableId);
      }
    } catch (Exception e) {
      log.error("Error creating BigQuery table: " + tableId, e);
      throw new RuntimeException("Failed to create BigQuery table: " + tableId, e);
    }
  }

  /**
   * ポジションをBigQueryにUPSERT（INSERT or UPDATE）する同期メソッド
   * BigQueryWriterスレッドから呼ばれる
   */
  public void upsertPosition(BigQueryPositionEntity position) {
    try {
      Map<String, Object> row = position.toBigQueryRow();

      String mergeQuery = String.format(
        """
        MERGE `%s.%s.positions` AS target
        USING (
          SELECT
            @username AS username,
            @symbol AS symbol,
            @total_buy_qty AS total_buy_qty,
            @total_buy_amount AS total_buy_amount,
            @total_sell_qty AS total_sell_qty,
            @total_sell_amount AS total_sell_amount,
            @net_qty AS net_qty,
            @average_buy_price AS average_buy_price,
            @average_sell_price AS average_sell_price,
            @realized_pnl AS realized_pnl,
            @last_updated AS last_updated
        ) AS source
        ON target.username = source.username AND target.symbol = source.symbol
        WHEN MATCHED THEN
          UPDATE SET
            total_buy_qty = source.total_buy_qty,
            total_buy_amount = source.total_buy_amount,
            total_sell_qty = source.total_sell_qty,
            total_sell_amount = source.total_sell_amount,
            net_qty = source.net_qty,
            average_buy_price = source.average_buy_price,
            average_sell_price = source.average_sell_price,
            realized_pnl = source.realized_pnl,
            last_updated = source.last_updated
        WHEN NOT MATCHED THEN
          INSERT (username, symbol, total_buy_qty, total_buy_amount, total_sell_qty,
                  total_sell_amount, net_qty, average_buy_price, average_sell_price,
                  realized_pnl, last_updated)
          VALUES (source.username, source.symbol, source.total_buy_qty,
                  source.total_buy_amount, source.total_sell_qty, source.total_sell_amount,
                  source.net_qty, source.average_buy_price, source.average_sell_price,
                  source.realized_pnl, source.last_updated)
        """,
        projectId, datasetName);

      // Convert timestamp from seconds (double) to microseconds (long) for BigQuery
      Double lastUpdatedSeconds = (Double) row.get("last_updated");
      Long lastUpdatedMicros = (long) (lastUpdatedSeconds * 1_000_000);

      QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(mergeQuery)
        .addNamedParameter("username", com.google.cloud.bigquery.QueryParameterValue.string((String) row.get("username")))
        .addNamedParameter("symbol", com.google.cloud.bigquery.QueryParameterValue.string((String) row.get("symbol")))
        .addNamedParameter("total_buy_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("total_buy_qty")))
        .addNamedParameter("total_buy_amount", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("total_buy_amount")))
        .addNamedParameter("total_sell_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("total_sell_qty")))
        .addNamedParameter("total_sell_amount", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("total_sell_amount")))
        .addNamedParameter("net_qty", com.google.cloud.bigquery.QueryParameterValue.int64((Long) row.get("net_qty")))
        .addNamedParameter("average_buy_price", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("average_buy_price")))
        .addNamedParameter("average_sell_price", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("average_sell_price")))
        .addNamedParameter("realized_pnl", com.google.cloud.bigquery.QueryParameterValue.float64((Double) row.get("realized_pnl")))
        .addNamedParameter("last_updated", com.google.cloud.bigquery.QueryParameterValue.timestamp(lastUpdatedMicros))
        .build();

      JobId jobId = JobId.of(java.util.UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());

      queryJob = queryJob.waitFor();

      if (queryJob == null || queryJob.getStatus().getError() != null) {
        String error = queryJob != null ? queryJob.getStatus().getError().toString() : "Unknown error";
        log.error("Error upserting position to BigQuery: {}", error);
        throw new RuntimeException("Failed to upsert position to BigQuery: " + error);
      }

      log.debug("Successfully upserted position to BigQuery: {}_{}",
                position.getUsername(), position.getSymbol());

    } catch (Exception e) {
      log.error("Error upserting position to BigQuery: {}_{}",
                position.getUsername(), position.getSymbol(), e);
      throw new RuntimeException("Failed to upsert position to BigQuery", e);
    }
  }
}
