package com.ys.exch_sim.domain.service;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.ys.exch_sim.domain.order_exec.Execution;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.data-migration.bigquery-enabled", havingValue = "true")
public class BigQueryVolumeCalculationService {

  @Autowired(required = false)
  private BigQuery bigQuery;

  @Value("${spring.cloud.gcp.project-id}")
  private String projectId;

  @Value("${spring.cloud.gcp.bigquery.dataset-name}")
  private String datasetName;

  // メモリ上での取引量管理: symbol -> volume
  private final Map<String, Long> symbolVolumeCache = new ConcurrentHashMap<>();
  private final Map<String, Long> totalVolumeCache = new ConcurrentHashMap<>();

  // 24時間の計算基準時刻（起動時に設定）
  private LocalDateTime baseDateTime;

  @PostConstruct
  public void initialize() {
    log.info("Initializing volume calculation service");
    baseDateTime = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);

    if (bigQuery == null) {
      log.warn("BigQuery is not available, volume calculation service will use memory-only mode");
      // メモリ上での取引量管理は継続
      symbolVolumeCache.clear();
      totalVolumeCache.clear();
      return;
    }

    log.info("Initializing BigQuery-based volume calculation service");

    try {
      loadInitialVolumeDataFromBigQuery();
      log.info("Successfully initialized volume calculation service with data from BigQuery");
    } catch (Exception e) {
      log.error(
          "Failed to initialize volume data from BigQuery, falling back to memory-only mode", e);
      // 失敗した場合はメモリのみモードで開始
      symbolVolumeCache.clear();
      totalVolumeCache.clear();
    }
  }

  /** BigQueryから過去24時間の取引量データを読み込み */
  private void loadInitialVolumeDataFromBigQuery() throws Exception {
    // BigQuery TIMESTAMP format (without nanoseconds)
    String fromTime = baseDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    String currentTime = LocalDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    
    log.info("🕐 Loading initial volume data from {} to {} (UTC)", fromTime, currentTime);

    // シンボル別取引量を取得
    String symbolVolumeQuery =
        String.format(
            "SELECT symbol, SUM(last_qty) as total_volume "
                + "FROM `%s.%s.executions` "
                + "WHERE is_market_maker = false "
                + "AND exec_status IN ('FILLED', 'PARTIAL_FILL') "
                + "AND created_at >= '%s' "
                + "AND created_at <= '%s' "
                + "GROUP BY symbol",
            projectId, datasetName, fromTime, currentTime);

    QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(symbolVolumeQuery).build();
    JobId jobId = JobId.of(UUID.randomUUID().toString());
    Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
    queryJob = queryJob.waitFor();

    if (queryJob == null || queryJob.getStatus().getError() != null) {
      throw new RuntimeException("BigQuery volume calculation failed");
    }

    TableResult result = queryJob.getQueryResults();
    long totalVolume = 0L;

    for (FieldValueList row : result.iterateAll()) {
      String symbol = row.get("symbol").getStringValue();
      Long volume = row.get("total_volume").getLongValue();

      symbolVolumeCache.put(symbol, volume);
      totalVolume += volume;

      log.debug("Loaded initial volume for {}: {}", symbol, volume);
    }

    totalVolumeCache.put("ALL", totalVolume);
    log.info(
        "Loaded initial volume data: {} symbols, total volume: {}",
        symbolVolumeCache.size(),
        totalVolume);
  }

  /** 新しい取引が発生した際の取引量更新 */
  public void updateVolumeOnTrade(Execution execution) {
    if (execution == null) {
      return;
    }

    // isMarketMakerがnullの場合はfalseとして扱う（安全なデフォルト値）
    Boolean isMarketMaker = execution.getIsMarketMaker();
    if (isMarketMaker != null && isMarketMaker) {
      return; // MarketMaker取引は除外
    }

    String symbol = execution.getSymbol();
    Long qty = execution.getLastQtyRaw();

    if (symbol != null && qty != null && qty > 0) {
      // シンボル別取引量を更新
      symbolVolumeCache.merge(symbol, qty, Long::sum);

      // 全体取引量を更新
      totalVolumeCache.merge("ALL", qty, Long::sum);

      log.info(
          "🔄 Updated volume for {}: +{}, new symbol total: {}, global total: {}", 
          symbol, qty, symbolVolumeCache.get(symbol), totalVolumeCache.get("ALL"));
    } else {
      log.warn("Invalid execution data for volume calculation: symbol={}, qty={}", symbol, qty);
    }
  }

  /** 指定期間の取引量を取得（現在は24時間固定） */
  public Long calculateVolumeBySymbol(String symbol) {
    if (symbol == null || symbol.trim().isEmpty()) {
      Long totalVolume = totalVolumeCache.getOrDefault("ALL", 0L);
      log.debug("🔍 calculateVolumeBySymbol: empty symbol, returning total volume: {}", totalVolume);
      return totalVolume;
    }
    
    String normalizedSymbol = symbol.toUpperCase();
    Long volume = symbolVolumeCache.getOrDefault(normalizedSymbol, 0L);
    log.debug("🔍 calculateVolumeBySymbol: symbol '{}' -> volume: {} (available symbols: {})", 
             normalizedSymbol, volume, symbolVolumeCache.keySet());
    return volume;
  }

  /** 全体の取引量を取得 */
  public Long calculateTotalVolume() {
    Long totalVolume = totalVolumeCache.getOrDefault("ALL", 0L);
    log.debug("🔍 calculateTotalVolume: {}", totalVolume);
    return totalVolume;
  }

  /** 取引量データの状態を取得（デバッグ用） */
  public Map<String, Object> getVolumeStatus() {
    return Map.of(
        "baseDateTime", baseDateTime.toString(),
        "symbolCount", symbolVolumeCache.size(),
        "totalVolume", calculateTotalVolume(),
        "symbols", symbolVolumeCache);
  }

  /** 定期的な取引量データのリフレッシュ（24時間毎） 実装は後で追加可能 */
  public void refreshVolumeData() {
    log.info("Refreshing volume data...");
    baseDateTime = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);

    try {
      symbolVolumeCache.clear();
      totalVolumeCache.clear();
      loadInitialVolumeDataFromBigQuery();
      log.info("Successfully refreshed volume data");
    } catch (Exception e) {
      log.error("Failed to refresh volume data", e);
    }
  }
}
