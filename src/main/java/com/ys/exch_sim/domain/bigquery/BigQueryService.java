package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
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

  /** Insert execution data into BigQuery asynchronously */
  @Async("bigQueryAsyncExecutor")
  public CompletableFuture<Void> insertExecutionAsync(BigQueryExecutionEntity execution) {
    try {
      log.debug("Starting async BigQuery execution insert: {}", execution.getExecId());

      TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = execution.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting execution to BigQuery (async): {}", response.getInsertErrors());
        return CompletableFuture.failedFuture(
            new RuntimeException("Failed to insert execution to BigQuery"));
      }

      log.debug("Successfully inserted execution to BigQuery (async): {}", execution.getExecId());
      return CompletableFuture.completedFuture(null);

    } catch (Exception e) {
      log.error("Error inserting execution to BigQuery (async): {}", execution.getExecId(), e);
      return CompletableFuture.failedFuture(e);
    }
  }

  /** Insert position data into BigQuery (synchronous version) */
  public void insertPosition(BigQueryPositionEntity position) {
    try {
      TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = position.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting position to BigQuery: {}", response.getInsertErrors());
        throw new RuntimeException("Failed to insert position to BigQuery");
      }

      log.debug(
          "Successfully inserted position to BigQuery: {}_{}",
          position.getUsername(),
          position.getSymbol());
    } catch (Exception e) {
      log.error("Error inserting position to BigQuery", e);
      throw new RuntimeException("Failed to insert position to BigQuery", e);
    }
  }

  /** Insert position data into BigQuery asynchronously */
  @Async("bigQueryAsyncExecutor")
  public CompletableFuture<Void> insertPositionAsync(BigQueryPositionEntity position) {
    try {
      log.debug(
          "Starting async BigQuery position insert: {}_{}",
          position.getUsername(),
          position.getSymbol());

      TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = position.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error("Error inserting position to BigQuery (async): {}", response.getInsertErrors());
        return CompletableFuture.failedFuture(
            new RuntimeException("Failed to insert position to BigQuery"));
      }

      log.debug(
          "Successfully inserted position to BigQuery (async): {}_{}",
          position.getUsername(),
          position.getSymbol());
      return CompletableFuture.completedFuture(null);

    } catch (Exception e) {
      log.error(
          "Error inserting position to BigQuery (async): {}_{}",
          position.getUsername(),
          position.getSymbol(),
          e);
      return CompletableFuture.failedFuture(e);
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

  /** Insert trade history data into BigQuery asynchronously */
  @Async("bigQueryAsyncExecutor")
  public CompletableFuture<Void> insertTradeHistoryAsync(BigQueryTradeHistoryEntity tradeHistory) {
    try {
      log.debug("Starting async BigQuery trade history insert: {}", tradeHistory.getExecId());

      TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
      Map<String, Object> row = tradeHistory.toBigQueryRow();

      InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId).addRow(row).build();

      InsertAllResponse response = bigQuery.insertAll(insertRequest);

      if (response.hasErrors()) {
        log.error(
            "Error inserting trade history to BigQuery (async): {}", response.getInsertErrors());
        return CompletableFuture.failedFuture(
            new RuntimeException("Failed to insert trade history to BigQuery"));
      }

      log.debug(
          "Successfully inserted trade history to BigQuery (async): {}", tradeHistory.getExecId());
      return CompletableFuture.completedFuture(null);

    } catch (Exception e) {
      log.error(
          "Error inserting trade history to BigQuery (async): {}", tradeHistory.getExecId(), e);
      return CompletableFuture.failedFuture(e);
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
            Field.of("created_at", StandardSQLTypeName.STRING),
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
            Field.of("last_updated", StandardSQLTypeName.STRING));

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
            Field.of("timestamp", StandardSQLTypeName.STRING),
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
}
