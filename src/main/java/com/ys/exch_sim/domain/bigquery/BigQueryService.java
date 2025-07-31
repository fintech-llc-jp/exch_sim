package com.ys.exch_sim.domain.bigquery;

import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data-migration.bigquery-enabled", havingValue = "true", matchIfMissing = false)
public class BigQueryService {
    
    private final BigQuery bigQuery;
    
    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;
    
    @Value("${spring.cloud.gcp.bigquery.dataset-name}")
    private String datasetName;
    
    /**
     * Create BigQuery tables if they don't exist
     */
    public void createTablesIfNotExist() {
        try {
            createExecutionsTable();
            createPositionsTable();
            createTradeHistoryTable();
            log.info("BigQuery tables created successfully");
        } catch (Exception e) {
            log.error("Error creating BigQuery tables", e);
            throw new RuntimeException("Failed to create BigQuery tables", e);
        }
    }
    
    /**
     * Insert execution data into BigQuery (synchronous version for initialization)
     */
    public void insertExecution(BigQueryExecutionEntity execution) {
        try {
            TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = execution.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
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
    
    /**
     * Insert execution data into BigQuery asynchronously
     */
    @Async("bigQueryAsyncExecutor")
    public CompletableFuture<Void> insertExecutionAsync(BigQueryExecutionEntity execution) {
        try {
            log.debug("Starting async BigQuery execution insert: {}", execution.getExecId());
            
            TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = execution.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            
            if (response.hasErrors()) {
                log.error("Error inserting execution to BigQuery (async): {}", response.getInsertErrors());
                return CompletableFuture.failedFuture(new RuntimeException("Failed to insert execution to BigQuery"));
            }
            
            log.debug("Successfully inserted execution to BigQuery (async): {}", execution.getExecId());
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("Error inserting execution to BigQuery (async): {}", execution.getExecId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Insert position data into BigQuery (synchronous version)
     */
    public void insertPosition(BigQueryPositionEntity position) {
        try {
            TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = position.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            
            if (response.hasErrors()) {
                log.error("Error inserting position to BigQuery: {}", response.getInsertErrors());
                throw new RuntimeException("Failed to insert position to BigQuery");
            }
            
            log.debug("Successfully inserted position to BigQuery: {}_{}", position.getUsername(), position.getSymbol());
        } catch (Exception e) {
            log.error("Error inserting position to BigQuery", e);
            throw new RuntimeException("Failed to insert position to BigQuery", e);
        }
    }
    
    /**
     * Insert position data into BigQuery asynchronously
     */
    @Async("bigQueryAsyncExecutor")
    public CompletableFuture<Void> insertPositionAsync(BigQueryPositionEntity position) {
        try {
            log.debug("Starting async BigQuery position insert: {}_{}", position.getUsername(), position.getSymbol());
            
            TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = position.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            
            if (response.hasErrors()) {
                log.error("Error inserting position to BigQuery (async): {}", response.getInsertErrors());
                return CompletableFuture.failedFuture(new RuntimeException("Failed to insert position to BigQuery"));
            }
            
            log.debug("Successfully inserted position to BigQuery (async): {}_{}", position.getUsername(), position.getSymbol());
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("Error inserting position to BigQuery (async): {}_{}", position.getUsername(), position.getSymbol(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Insert trade history data into BigQuery (synchronous version)
     */
    public void insertTradeHistory(BigQueryTradeHistoryEntity tradeHistory) {
        try {
            TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = tradeHistory.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
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
    
    /**
     * Insert trade history data into BigQuery asynchronously
     */
    @Async("bigQueryAsyncExecutor")
    public CompletableFuture<Void> insertTradeHistoryAsync(BigQueryTradeHistoryEntity tradeHistory) {
        try {
            log.debug("Starting async BigQuery trade history insert: {}", tradeHistory.getExecId());
            
            TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
            Map<String, Object> row = tradeHistory.toBigQueryRow();
            
            InsertAllRequest insertRequest = InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build();
            
            InsertAllResponse response = bigQuery.insertAll(insertRequest);
            
            if (response.hasErrors()) {
                log.error("Error inserting trade history to BigQuery (async): {}", response.getInsertErrors());
                return CompletableFuture.failedFuture(new RuntimeException("Failed to insert trade history to BigQuery"));
            }
            
            log.debug("Successfully inserted trade history to BigQuery (async): {}", tradeHistory.getExecId());
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("Error inserting trade history to BigQuery (async): {}", tradeHistory.getExecId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Query executions from BigQuery (simplified version)
     */
    public void queryExecutions(String whereClause) {
        // TODO: Implement query functionality when needed
        log.info("Query executions method called with whereClause: {}", whereClause);
    }
    
    private void createExecutionsTable() {
        TableId tableId = BigQueryExecutionEntity.getTableId(projectId, datasetName);
        
        Schema schema = Schema.of(
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
                Field.of("side", StandardSQLTypeName.STRING)
        );
        
        createTableIfNotExists(tableId, schema);
    }
    
    private void createPositionsTable() {
        TableId tableId = BigQueryPositionEntity.getTableId(projectId, datasetName);
        
        Schema schema = Schema.of(
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
                Field.of("last_updated", StandardSQLTypeName.STRING)
        );
        
        createTableIfNotExists(tableId, schema);
    }
    
    private void createTradeHistoryTable() {
        TableId tableId = BigQueryTradeHistoryEntity.getTableId(projectId, datasetName);
        
        Schema schema = Schema.of(
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
                Field.of("is_market_maker", StandardSQLTypeName.BOOL)
        );
        
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