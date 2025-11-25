package com.ys.exch_sim.domain.database.impl;

import com.google.cloud.bigquery.*;
import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryPositionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryTradeHistoryEntity;
import com.ys.exch_sim.domain.database.DatabaseService;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** BigQuery実装のDatabaseService 既存のBigQueryServiceの機能をDatabaseServiceインターフェース経由で提供 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.database.type", havingValue = "bigquery", matchIfMissing = false)
public class BigQueryDatabaseService implements DatabaseService {

  private final BigQuery bigQuery;

  public BigQueryDatabaseService(BigQuery bigQuery) {
    this.bigQuery = bigQuery;
  }

  @Value("${spring.cloud.gcp.project-id}")
  private String projectId;

  @Value("${spring.cloud.gcp.bigquery.dataset-name}")
  private String datasetName;

  // 既存のBigQueryServiceのメソッドを呼び出すためのヘルパー
  // 注意: BigQueryServiceは@ConditionalOnPropertyで条件付きBeanのため、required = falseで注入
  @Autowired(required = false)
  private com.ys.exch_sim.domain.bigquery.BigQueryService bigQueryService;

  @Override
  public void insertExecution(Execution execution) {
    if (bigQueryService == null) {
      log.error("BigQueryService is not available");
      throw new IllegalStateException("BigQueryService is not available");
    }
    BigQueryExecutionEntity entity = new BigQueryExecutionEntity(execution);
    bigQueryService.insertExecution(entity);
  }

  @Override
  public List<Execution> queryRecentExecutions(LocalDateTime fromTime) {
    List<BigQueryExecutionEntity> entities = bigQueryService.queryRecentExecutions();
    return entities.stream()
        .filter(
            entity -> {
              try {
                LocalDateTime createdAt = LocalDateTime.parse(entity.getCreatedAt());
                return createdAt.isAfter(fromTime) || createdAt.isEqual(fromTime);
              } catch (Exception e) {
                log.warn("Failed to parse createdAt: {}", entity.getCreatedAt(), e);
                return false;
              }
            })
        .map(this::convertToExecution)
        .toList();
  }

  @Override
  public void upsertPosition(Position position) {
    BigQueryPositionEntity entity = new BigQueryPositionEntity(position);
    bigQueryService.upsertPosition(entity);
  }

  @Override
  public Position queryPosition(String username, String symbol) {
    BigQueryPositionEntity entity = bigQueryService.queryPosition(username, symbol);
    return entity != null ? entity.toPosition() : null;
  }

  @Override
  public List<Position> queryAllPositions(String username) {
    List<BigQueryPositionEntity> entities = bigQueryService.queryAllPositions(username);
    return entities.stream().map(BigQueryPositionEntity::toPosition).toList();
  }

  @Override
  public void insertTradeHistory(TradeHistory tradeHistory) {
    BigQueryTradeHistoryEntity entity = new BigQueryTradeHistoryEntity(tradeHistory);
    bigQueryService.insertTradeHistory(entity);
  }

  @Override
  public List<TradeHistory> queryTradeHistory(String username) {
    List<BigQueryTradeHistoryEntity> entities = bigQueryService.queryTradeHistory(username);
    return entities.stream().map(BigQueryTradeHistoryEntity::toTradeHistory).toList();
  }

  @Override
  public List<TradeHistory> queryTradeHistory(String username, String symbol) {
    List<BigQueryTradeHistoryEntity> entities = bigQueryService.queryTradeHistory(username, symbol);
    return entities.stream().map(BigQueryTradeHistoryEntity::toTradeHistory).toList();
  }

  @Override
  public List<TradeHistory> queryTradeHistory(String username, int limit) {
    List<BigQueryTradeHistoryEntity> entities = bigQueryService.queryTradeHistory(username, limit);
    return entities.stream().map(BigQueryTradeHistoryEntity::toTradeHistory).toList();
  }

  @Override
  public void registerUser(String username, String encodedPassword, List<String> roles) {
    bigQueryService.registerUser(username, encodedPassword, roles);
  }

  @Override
  public boolean userExists(String username) {
    return bigQueryService.userExists(username);
  }

  @Override
  public UserEntity loadUser(String username) {
    try {
      String query =
          String.format(
              "SELECT username, password, roles FROM `%s.%s.users` WHERE username = @username LIMIT 1",
              projectId, datasetName);

      QueryJobConfiguration queryConfig =
          QueryJobConfiguration.newBuilder(query)
              .addNamedParameter("username", QueryParameterValue.string(username))
              .build();

      JobId jobId = JobId.of(UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
      queryJob = queryJob.waitFor();

      if (queryJob == null || queryJob.getStatus().getError() != null) {
        log.error("Error loading user from BigQuery: {}", username);
        return null;
      }

      TableResult result = queryJob.getQueryResults();
      if (result.getTotalRows() == 0) {
        return null;
      }

      FieldValueList row = result.iterateAll().iterator().next();
      String dbUsername = row.get("username").getStringValue();
      String password = row.get("password").getStringValue();

      List<String> roles = new ArrayList<>();
      if (!row.get("roles").isNull()) {
        for (FieldValue roleValue : row.get("roles").getRepeatedValue()) {
          roles.add(roleValue.getStringValue());
        }
      }

      return new UserEntity(dbUsername, password, roles);
    } catch (Exception e) {
      log.error("Error loading user from BigQuery: {}", username, e);
      return null;
    }
  }

  @Override
  public void createTablesIfNotExist() {
    bigQueryService.createTablesIfNotExist();
  }

  @Override
  public Map<String, Long> calculateVolumeBySymbol(LocalDateTime fromTime) {
    // BigQueryServiceにはこのメソッドがないため、直接実装する必要がある
    // 簡易実装として空のMapを返す（後で実装）
    log.warn("calculateVolumeBySymbol is not yet fully implemented for BigQuery");
    return new HashMap<>();
  }

  @Override
  public Long calculateTotalVolume(LocalDateTime fromTime) {
    // BigQueryServiceにはこのメソッドがないため、直接実装する必要がある
    // 簡易実装として0を返す（後で実装）
    log.warn("calculateTotalVolume is not yet fully implemented for BigQuery");
    return 0L;
  }

  /** BigQueryExecutionEntityをExecutionドメインオブジェクトに変換 */
  private Execution convertToExecution(BigQueryExecutionEntity entity) {
    return new Execution(
        entity.getExecId(),
        entity.getOrderId(),
        entity.getUsername(),
        entity.getSymbol(),
        com.ys.exch_sim.domain.message.field.ExecStatus.valueOf(entity.getExecStatus()),
        entity.getLastPx(),
        entity.getLastQty(),
        entity.getCounterPartyUsername(),
        LocalDateTime.parse(entity.getCreatedAt()),
        entity.getIsMarketMaker(),
        entity.getSide());
  }
}
