# Redis削除とBitcoinMarketRecorder直接統合への移行計画

## 概要
Exchange Simulatorプロジェクトにおいて、外部Redisサーバーへの依存を除去し、BitcoinMarketRecorderプロジェクトのマーケットデータを直接取り込む方式に変更する。

## 現状分析

### 削除対象のRedisコンポーネント
1. **com.ys.exch_sim.domain.service.RedisMessageHandler** - Redis Pub/Subメッセージ処理
2. **com.ys.exch_sim.domain.service.RedisPubSubService** - Redis購読サービス
3. **com.ys.exch_sim.domain.config.RedisPubSubInitializer** - Redis初期化処理
4. **com.ys.exch_sim.domain.config.RedisConfig** - Redis設定
5. **com.ys.exch_sim.domain.dto.RedisMarketMakeMessage** - MarketMakeメッセージDTO
6. **com.ys.exch_sim.domain.dto.RedisTradeInsertMessage** - TradeInsertメッセージDTO

### BitcoinMarketRecorderの活用可能コンポーネント
- **com.example.bitcoinmarketrecorder.bitflyer.BitflyerWebSocketClient**
- **com.example.bitcoinmarketrecorder.gmo.GmoWebSocketClient**
- **com.example.bitcoinmarketrecorder.service.ExchSimService** (データ変換ロジック参考)

## 新しいアーキテクチャ設計

### 重要な設計変更: BigQuery非同期保存の導入

#### 現在の問題
- **MarketDataSyncService → PositionManager → BigQueryService** の同期処理
- WebSocketスレッドがBigQuery保存完了まで**ブロッキング**
- ネットワーク遅延とBigQueryレート制限による性能劣化

#### 新しい非同期アーキテクチャ
```
WebSocket受信
├─ H2データベース: 同期保存（順序保証・確実性重視）
└─ BigQuery: 非同期保存（パフォーマンス重視・別スレッドプール）
```

#### 利点  
1. **WebSocketスレッド解放**: 瞬時にメッセージ処理完了
2. **順序保証維持**: H2への同期保存で順序確保
3. **障害分離**: BigQuery障害でもコア機能は継続
4. **スケーラビリティ**: BigQuery専用スレッドプールで最適化

#### H2データベース容量制限と対策

##### 容量制限計算
- **Google Cloud Run制限**: 最大32GB（実用10GB）
- **1レコードサイズ**: 約200バイト（インデックス込み）
- **最大保存可能件数**: 約1,500万件（3GB使用時）
- **予想データ流入**: 最大15件/秒 = 129万件/日
- **容量到達時間**: 約12日間

##### 自動データ管理戦略
1. **7日間保持ルール**: H2には直近7日分のデータのみ保持
2. **80%到達アラート**: 容量80%で自動クリーンアップ開始
3. **90%緊急処理**: 容量90%で即座に50%まで削減
4. **BigQuery確実保存**: H2削除前にBigQuery保存を確認

### 1. パッケージ構造とクラス設計

#### 新規作成パッケージ
```
com.ys.exch_sim.domain.market_data/
├── client/
│   ├── MarketDataWebSocketClient.java        # 抽象基底クラス
│   ├── BitflyerMarketDataClient.java         # Bitflyer専用クライアント
│   └── GmoMarketDataClient.java              # GMO専用クライアント
├── service/
│   ├── DirectMarketDataService.java          # メインサービス（非同期処理）
│   └── MarketDataClientManager.java          # クライアント管理
├── dto/
│   ├── ExternalMarketBoardData.java          # 外部板データ
│   ├── ExternalTradeData.java                # 外部取引データ
│   └── MarketDataSource.java                 # データソース列挙型
├── config/
│   ├── MarketDataClientConfig.java           # WebSocket設定
│   ├── AsyncConfig.java                      # 非同期処理設定
│   └── DataRetentionConfig.java              # データ保持期間設定
├── queue/
│   ├── MarketDataQueue.java                  # データキューイング
│   ├── QueueProcessor.java                   # キュー処理
│   ├── OrderedTradeProcessor.java            # 順序保証取引処理
│   └── SymbolPartitioner.java                # シンボル別パーティショニング
├── retention/
│   ├── DataRetentionService.java             # データ保持管理
│   ├── H2DataCleanupService.java             # H2データクリーンアップ
│   └── DataArchiveService.java               # データアーカイブ
└── monitoring/
    └── DatabaseMonitoringController.java     # H2容量監視API
```

### 2. 詳細クラス仕様

#### MarketDataWebSocketClient (抽象基底クラス)
```java
package com.ys.exch_sim.domain.market_data.client;

public abstract class MarketDataWebSocketClient {
    protected final DirectMarketDataService marketDataService;
    protected final String wsUrl;
    protected final String exchange;
    protected final ObjectMapper objectMapper;
    protected Disposable connection;
    
    // 抽象メソッド
    public abstract void connect();
    public abstract void disconnect();
    protected abstract void processMessage(String message);
    public abstract boolean isConnected();
    
    // 共通メソッド
    protected void handleConnectionError(Throwable error);
    protected void scheduleReconnection();
}
```

#### DirectMarketDataService (メインサービス) - 順序保証対応
```java
package com.ys.exch_sim.domain.market_data.service;

@Service
@RequiredArgsConstructor
public class DirectMarketDataService {
    private final MarketDataSyncService marketDataSyncService;
    private final InstrumentConfig instrumentConfig;
    private final OrderedTradeProcessor orderedTradeProcessor;
    private final TaskExecutor marketDataTaskExecutor; // MarketBoard用スレッドプール
    
    // 非同期マーケットボード処理 - 順序不要なので並列処理OK
    @Async("marketDataTaskExecutor")
    public CompletableFuture<Void> processMarketBoardAsync(ExternalMarketBoardData data);
    
    // 順序保証取引データ処理 - シンボル別にシーケンシャル処理
    public void processTradeWithOrdering(ExternalTradeData data) {
        String symbol = mapSymbol(data.exchange(), data.symbol());
        if (symbol != null) {
            orderedTradeProcessor.submitTrade(symbol, data);
        }
    }
    
    // 同期版も提供（テスト用）
    public void processMarketBoard(ExternalMarketBoardData data);
    public void processTrade(ExternalTradeData data);
    
    // シンボルマッピング
    private String mapSymbol(String exchange, String symbol);
    
    // データ変換
    private RedisMarketMakeMessage convertToMarketMakeMessage(ExternalMarketBoardData data);
    private RedisTradeInsertMessage convertToTradeMessage(ExternalTradeData data);
}
```

#### BitflyerMarketDataClient
```java
package com.ys.exch_sim.domain.market_data.client;

@Component
@ConditionalOnProperty(name = "market-data.bitflyer.enabled", havingValue = "true")
public class BitflyerMarketDataClient extends MarketDataWebSocketClient {
    
    @Value("${market-data.bitflyer.ws-url}")
    private String wsUrl;
    
    @Override
    public void connect();
    
    @Override
    protected void processMessage(String message);
    
    // Bitflyer固有の処理
    private void handleOrderbookMessage(JsonNode message);
    private void handleExecutionMessage(JsonNode message);
    private ExternalMarketBoardData convertBitflyerBoard(JsonNode boardData);
    private ExternalTradeData convertBitflyerTrade(JsonNode tradeData);
}
```

#### GmoMarketDataClient
```java
package com.ys.exch_sim.domain.market_data.client;

@Component
@ConditionalOnProperty(name = "market-data.gmo.enabled", havingValue = "true")
public class GmoMarketDataClient extends MarketDataWebSocketClient {
    
    @Value("${market-data.gmo.ws-url}")
    private String wsUrl;
    
    @Override
    public void connect();
    
    @Override
    protected void processMessage(String message);
    
    // GMO固有の処理
    private void handleOrderbookMessage(JsonNode message);
    private void handleTradeMessage(JsonNode message);
    private ExternalMarketBoardData convertGmoBoard(JsonNode boardData);
    private ExternalTradeData convertGmoTrade(JsonNode tradeData);
}
```

#### MarketDataClientManager
```java
package com.ys.exch_sim.domain.market_data.service;

@Service
@RequiredArgsConstructor
public class MarketDataClientManager {
    private final List<MarketDataWebSocketClient> clients;
    
    @PostConstruct
    public void initializeClients();
    
    @PreDestroy
    public void shutdownClients();
    
    public void startAllClients();
    public void stopAllClients();
    public boolean areAllClientsConnected();
}
```

#### OrderedTradeProcessor (順序保証取引処理)
```java
package com.ys.exch_sim.domain.market_data.queue;

@Service
@RequiredArgsConstructor
public class OrderedTradeProcessor {
    private final MarketDataSyncService marketDataSyncService;
    private final Map<String, SingleThreadExecutor> symbolExecutors = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    
    // シンボル別に専用スレッドで順序処理
    public void submitTrade(String symbol, ExternalTradeData tradeData) {
        SingleThreadExecutor executor = symbolExecutors.computeIfAbsent(
            symbol, k -> new SingleThreadExecutor("Trade-" + symbol + "-"));
        
        executor.submit(() -> {
            try {
                processTradeSynchronously(tradeData);
            } catch (Exception e) {
                log.error("Trade processing failed for symbol: {}, trade: {}", symbol, tradeData, e);
            }
        });
    }
    
    private void processTradeSynchronously(ExternalTradeData tradeData) {
        // H2への同期保存 - 順序保証のため
        marketDataSyncService.insertTrade(convertToTradeMessage(tradeData));
        
        // BigQueryへの非同期保存 - パフォーマンス重視
        // 注意: PositionManagerを経由せずに直接BigQuery保存に変更予定
        CompletableFuture.runAsync(() -> {
            try {
                BigQueryTradeHistoryEntity bigQueryEntity = createBigQueryEntity(tradeData);
                bigQueryService.insertTradeHistoryAsync(bigQueryEntity);
            } catch (Exception e) {
                log.error("BigQuery async save failed for trade: {}", tradeData, e);
            }
        }, bigQueryAsyncExecutor);
    }
    
    @PreDestroy
    public void shutdown() {
        symbolExecutors.values().forEach(SingleThreadExecutor::shutdown);
    }
}
```

#### SingleThreadExecutor (シンボル専用シングルスレッド)
```java
package com.ys.exch_sim.domain.market_data.queue;

public class SingleThreadExecutor {
    private final ExecutorService executor;
    private final String threadName;
    
    public SingleThreadExecutor(String threadNamePrefix) {
        this.threadName = threadNamePrefix + System.currentTimeMillis();
        this.executor = Executors.newSingleThreadExecutor(r -> 
            new Thread(r, threadName));
    }
    
    public void submit(Runnable task) {
        executor.submit(task);
    }
    
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
```

#### DataRetentionService (H2容量管理)
```java
package com.ys.exch_sim.domain.market_data.retention;

@Service
@RequiredArgsConstructor
public class DataRetentionService {
    private final ExecutionRepository executionRepository;
    private final BigQueryService bigQueryService;
    
    @Value("${market-data.h2.retention-days:7}")
    private int retentionDays;
    
    @Value("${market-data.h2.max-records:10000000}")
    private long maxRecords;
    
    // 定期的なデータクリーンアップ（毎日午前2時実行）
    @Scheduled(cron = "0 0 2 * * *")
    public void performDataRetention() {
        long currentCount = executionRepository.count();
        
        if (currentCount > maxRecords * 0.8) { // 80%到達時
            log.warn("H2 database approaching limit: {} records", currentCount);
            performCleanup();
        }
    }
    
    private void performCleanup() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        
        // 古いレコードをBigQueryに確実に保存されていることを確認
        List<Execution> oldRecords = executionRepository.findByCreatedAtBefore(cutoffDate);
        
        // BigQueryへの同期確認とH2からの削除
        batchArchiveAndDelete(oldRecords);
    }
    
    private void batchArchiveAndDelete(List<Execution> records) {
        // 1000件ずつバッチ処理
        int batchSize = 1000;
        for (int i = 0; i < records.size(); i += batchSize) {
            List<Execution> batch = records.subList(i, Math.min(i + batchSize, records.size()));
            archiveBatch(batch);
        }
    }
}
```

#### H2DataCleanupService (緊急時クリーンアップ)
```java
package com.ys.exch_sim.domain.market_data.retention;

@Service
@RequiredArgsConstructor
public class H2DataCleanupService {
    private final ExecutionRepository executionRepository;
    
    // 緊急時: 最大容量の90%到達時に即座にクリーンアップ
    public void emergencyCleanup() {
        long currentCount = executionRepository.count();
        long targetCount = (long) (currentCount * 0.5); // 50%まで削減
        
        // 最古のレコードから削除（BigQuery保存済み前提）
        executionRepository.deleteOldestRecords(currentCount - targetCount);
        
        log.warn("Emergency cleanup completed: {} -> {} records", currentCount, targetCount);
    }
    
    // 容量監視とアラート
    @Scheduled(fixedRate = 300000) // 5分毎
    public void monitorDiskUsage() {
        long count = executionRepository.count();
        double usagePercent = (double) count / MAX_RECORDS * 100;
        
        if (usagePercent > 90) {
            log.error("CRITICAL: H2 database usage {}% - Emergency cleanup required", usagePercent);
            emergencyCleanup();
        } else if (usagePercent > 80) {
            log.warn("WARNING: H2 database usage {}%", usagePercent);
        }
    }
}
```

#### DatabaseMonitoringController (H2容量監視API)
```java
package com.ys.exch_sim.domain.market_data.monitoring;

@RestController
@RequestMapping("/api/monitoring/database")
@RequiredArgsConstructor
public class DatabaseMonitoringController {
    private final ExecutionRepository executionRepository;
    private final DataSource dataSource;
    
    @Value("${market-data.h2.max-records:10000000}")
    private long maxRecords;
    
    // 基本容量情報API（USER権限でアクセス可能）
    @GetMapping("/capacity")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getCapacityInfo();
    
    // 詳細ステータス情報API（ADMIN限定）
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getDatabaseStatus();
    
    // テーブル統計情報API（ADMIN限定）
    @GetMapping("/tables")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getTableStatistics();
    
    // クリーンアップ予測API（ADMIN限定）
    @GetMapping("/cleanup-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getCleanupPreview();
    
    // 内部メソッド
    private Map<String, Object> getH2FileSize();
    private Map<String, Object> getDatabaseStatistics();
    private String getStatusLevel(double usagePercent);
    private Map<String, String> generateAlerts(double usagePercent, long recordCount);
    private Map<String, Object> calculateSpaceSaved(long recordCount);
}
```

### API仕様詳細

#### 1. GET /api/monitoring/database/capacity
**用途**: 基本的な容量情報の取得（軽量API）
**権限**: USER, ADMIN
**レスポンス例**:
```json
{
  "recordCount": 5000000,
  "maxRecords": 10000000,
  "usagePercent": 50.0,
  "status": "NORMAL",
  "remainingCapacity": 5000000
}
```

#### 2. GET /api/monitoring/database/status
**用途**: 詳細なデータベース状態の取得
**権限**: ADMIN
**レスポンス例**:
```json
{
  "timestamp": "2025-08-01T10:30:00",
  "recordCount": 8500000,
  "maxRecords": 10000000,
  "usagePercent": 85.0,
  "status": "WARNING",
  "fileInfo": {
    "fileSizeBytes": 1700000000,
    "fileSizeMB": 1700.0,
    "fileSizeGB": 1.7,
    "filePath": "/data/executions.mv.db"
  },
  "databaseStats": {
    "pageCount": "425000",
    "databaseProductName": "H2",
    "databaseProductVersion": "2.1.214"
  },
  "alerts": {
    "level": "WARNING",
    "message": "Database capacity approaching limit",
    "action": "Schedule cleanup of old records"
  }
}
```

#### 3. GET /api/monitoring/database/tables
**用途**: テーブル別統計情報の取得
**権限**: ADMIN
**レスポンス例**:
```json
{
  "timestamp": "2025-08-01T10:30:00",
  "tables": {
    "EXECUTIONS": 8500000,
    "POSITIONS": 1500,
    "TRADE_HISTORY": 5000000
  }
}
```

#### 4. GET /api/monitoring/database/cleanup-preview
**用途**: データクリーンアップの予測情報
**権限**: ADMIN
**レスポンス例**:
```json
{
  "currentRecords": 8500000,
  "recordsOlderThan7Days": 3000000,
  "recordsOlderThan3Days": 1500000,
  "expectedAfterCleanup7Days": 5500000,
  "expectedAfterCleanup3Days": 7000000,
  "spaceSaved7Days": {
    "records": 3000000,
    "estimatedBytes": 600000000,
    "estimatedMB": 600.0,
    "estimatedGB": 0.6
  },
  "spaceSaved3Days": {
    "records": 1500000,
    "estimatedBytes": 300000000,
    "estimatedMB": 300.0,
    "estimatedGB": 0.3
  }
}
```

### ステータスレベル定義
- **NORMAL**: 使用率 < 60%
- **CAUTION**: 使用率 60% - 80%
- **WARNING**: 使用率 80% - 90%
- **CRITICAL**: 使用率 >= 90%

### ExecutionRepositoryへの追加メソッド
```java
// 監視用メソッド
@Query("SELECT COUNT(e) FROM Execution e WHERE e.createdAt < :cutoffDate")
Long countByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

@Query("SELECT e FROM Execution e WHERE e.createdAt < :cutoffDate ORDER BY e.createdAt ASC")
List<Execution> findByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

@Query(value = "DELETE FROM executions WHERE created_at < :cutoffDate", nativeQuery = true)
void deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

@Query(value = "DELETE FROM executions WHERE exec_id IN (SELECT exec_id FROM executions ORDER BY created_at ASC LIMIT :limit)", nativeQuery = true)
void deleteOldestRecords(@Param("limit") long limit);

// 統計用メソッド
@Query("SELECT MIN(e.createdAt) FROM Execution e")
LocalDateTime findOldestRecordDate();

@Query("SELECT MAX(e.createdAt) FROM Execution e")  
LocalDateTime findNewestRecordDate();

@Query("SELECT COUNT(e) FROM Execution e WHERE e.createdAt > :date")
Long countRecordsAfterDate(@Param("date") LocalDateTime date);
```

### テスト仕様
```java
@SpringBootTest
@AutoConfigureTestMvc
@ActiveProfiles("test")
class DatabaseMonitoringControllerTest {
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testGetCapacityInfo_Success();
    
    @Test
    @WithMockUser(roles = "ADMIN") 
    void testGetCapacityInfo_HighUsage();
    
    @Test
    @WithMockUser(roles = "USER")
    void testGetCapacityInfo_UserAccess();
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testGetDatabaseStatus_Success();
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testGetCleanupPreview_Success();
    
    @Test
    void testGetCapacityInfo_Unauthorized();
    
    @Test
    @WithMockUser(roles = "USER")
    void testGetDatabaseStatus_Forbidden();
}
```

### 実装時の注意点
1. **セキュリティ**: USER権限でアクセス可能なAPIは最小限の情報のみ
2. **パフォーマンス**: 軽量なcapacityエンドポイントを優先使用
3. **エラーハンドリング**: データベース接続エラー時の適切な処理
4. **ログ出力**: 監視API呼び出しのログ記録
5. **レート制限**: 頻繁なアクセスを制限する仕組み

### 3. DTOクラス仕様

#### ExternalMarketBoardData
```java
package com.ys.exch_sim.domain.market_data.dto;

public record ExternalMarketBoardData(
    String exchange,
    String symbol,
    List<PriceLevel> bids,
    List<PriceLevel> asks,
    Instant timestamp
) {
    public record PriceLevel(Double price, Double quantity) {}
}
```

#### ExternalTradeData
```java
package com.ys.exch_sim.domain.market_data.dto;

public record ExternalTradeData(
    String exchange,
    String symbol,
    Double price,
    Double quantity,
    String side,
    Instant timestamp
) {}
```

#### MarketDataSource
```java
package com.ys.exch_sim.domain.market_data.dto;

public enum MarketDataSource {
    BITFLYER("BITFLYER"),
    GMO("GMO");
    
    private final String name;
    
    MarketDataSource(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
}
```

## 既存クラスの改修計画

### MarketDataSyncService の改修
```java
// 削除対象メソッド
- public void updateMarketBoard(RedisMarketMakeMessage message)
- public void insertTrade(RedisTradeInsertMessage message)

// 新規メソッド (後方互換性のため段階的に追加)
+ public void updateMarketBoard(ExternalMarketBoardData data)
+ public void insertTrade(ExternalTradeData data)

// 内部メソッドは既存のまま保持
- private MarketBoard getOrCreateMarketBoard(String symbolName)
- private Order createMarketMakerOrder(Symbol symbol, long price, long quantity, Side side)
```

## 設定ファイル更新

### application.properties 変更内容

#### 削除対象設定
```properties
# Redis関連設定を全て削除
redis.pubsub.enabled=*
redis.pubsub.market-make.channel-pattern=*
redis.pubsub.trade-insert.channel-pattern=*
spring.redis.*
```

#### 新規追加設定
```properties
# マーケットデータクライアント設定
market-data.bitflyer.enabled=true
market-data.bitflyer.ws-url=wss://ws.lightstream.bitflyer.com/json-rpc
market-data.bitflyer.reconnect-delay=5000
market-data.bitflyer.max-reconnect-attempts=10

market-data.gmo.enabled=true
market-data.gmo.ws-url=wss://api.coin.z.com/ws/public/v1
market-data.gmo.reconnect-delay=5000
market-data.gmo.max-reconnect-attempts=10

# シンボルマッピング設定
market-data.symbol-mapping.BITFLYER.BTC_JPY=G_BTCJPY
market-data.symbol-mapping.BITFLYER.FX_BTC_JPY=G_FX_BTCJPY
market-data.symbol-mapping.GMO.BTC_JPY=B_BTCJPY
market-data.symbol-mapping.GMO.BTC=B_FX_BTCJPY

# データ処理設定
market-data.board.max-levels=10
market-data.trade.batch-size=100

# 非同期処理設定（MarketBoard用）
market-data.async.core-pool-size=4
market-data.async.max-pool-size=8
market-data.async.queue-capacity=1000
market-data.async.thread-name-prefix=MarketData-

# 順序保証設定（Trade用）
market-data.trade.ordering.enabled=true
market-data.trade.ordering.shutdown-timeout=5000

# BigQuery非同期保存設定
market-data.bigquery.async.enabled=true
market-data.bigquery.async.core-pool-size=2
market-data.bigquery.async.max-pool-size=4
market-data.bigquery.async.queue-capacity=500

# H2データ保持設定
market-data.h2.retention-days=7
market-data.h2.max-records=10000000
market-data.h2.cleanup.enabled=true
market-data.h2.cleanup.batch-size=1000
market-data.h2.emergency-cleanup-threshold=0.9
```

## 段階的移行手順

### Phase 1: 新規コンポーネント実装 (3-4日)
1. **DirectMarketDataService** 作成
   - 基本的なデータ処理ロジック実装
   - 既存MarketDataSyncServiceとの連携
2. **MarketDataWebSocketClient** 基底クラス作成
   - 共通のWebSocket接続ロジック
   - エラーハンドリングと再接続機能
3. **DTOクラス** 作成
   - ExternalMarketBoardData, ExternalTradeData
   - MarketDataSource列挙型
4. **MarketDataClientConfig** 作成
   - 設定プロパティの定義

### Phase 2: WebSocketクライアント実装 (5-7日)
1. **BitflyerMarketDataClient** 実装
   - WebSocket接続とメッセージ処理
   - Bitflyerフォーマットからの変換ロジック
2. **GmoMarketDataClient** 実装
   - WebSocket接続とメッセージ処理  
   - GMOフォーマットからの変換ロジック
3. **MarketDataClientManager** 実装
   - クライアント管理とライフサイクル制御
4. **単体テスト** 実装
   - WebSocket接続テスト
   - データ変換テスト

### Phase 3: 既存サービス統合・BigQuery非同期化 (3-4日)
1. **MarketDataSyncService** 改修
   - 新しいメソッドの追加
   - 既存メソッドとの並行動作
2. **BigQuery非同期保存** 実装
   - PositionManagerからBigQuery保存処理を分離
   - 専用BigQueryスレッドプールの作成
   - エラーハンドリングとリトライ機能
3. **統合テスト** 実装
   - 新旧システムの並行テスト
   - データ整合性確認（H2とBigQuery）
   - BigQuery非同期保存のテスト
4. **設定ファイル** 更新
   - 新しい設定項目の追加
   - BigQuery非同期設定の追加
   - 既存Redis設定は一時的に残す

### Phase 4: Redis依存削除 (1-2日)
1. **Redisクラス削除**
   - RedisMessageHandler削除
   - RedisPubSubService削除
   - RedisPubSubInitializer削除
   - RedisConfig削除
   - Redis DTO削除
2. **設定ファイルクリーンアップ**
   - Redis関連設定の完全削除
3. **依存関係更新**
   - build.gradleからRedis依存削除
   - 不要なimport削除

### Phase 5: 監視機能・最終テスト (3-4日)
1. **H2監視API実装**
   - DatabaseMonitoringControllerの実装
   - ExecutionRepositoryに監視用メソッド追加
   - セキュリティ設定とテスト実装
2. **全機能回帰テスト**
   - 既存機能の動作確認
   - マーケットボード更新テスト
   - 取引実行テスト
   - 監視API動作テスト
3. **パフォーマンステスト**
   - データ処理速度測定
   - メモリ使用量監視
   - 接続安定性確認
   - H2容量監視の動作確認
4. **本番環境デプロイ準備**
   - 設定ファイルの最終調整
   - ログ出力の最適化
   - 監視ダッシュボード連携準備

## テスト戦略

### 単体テスト
- **WebSocketクライアント**
  - 接続/切断のテスト
  - メッセージ解析のテスト
  - エラーハンドリングのテスト
- **データ変換ロジック**
  - Bitflyer/GMOフォーマット変換テスト
  - シンボルマッピングテスト
  - 価格・数量変換テスト

### 統合テスト
- **リアルタイムデータ処理**
  - 実際のWebSocket接続テスト
  - マーケットボード更新の確認
  - 取引データ挿入の確認
- **エラー復旧テスト**
  - 接続断時の再接続テスト
  - 不正データ受信時の処理テスト

### パフォーマンステスト
- **処理性能**
  - 1秒間のメッセージ処理件数測定
  - メモリ使用量の継続監視
  - CPU使用率の測定
- **BigQuery非同期処理テスト**
  - H2とBigQueryの保存時間差測定
  - BigQuery障害時のシステム継続性確認
  - BigQueryスレッドプールの最適サイズ決定
- **安定性テスト**
  - 24時間連続動作テスト
  - 大量データ処理時の安定性確認
  - BigQuery接続障害からの自動復旧テスト

## リスク管理

### 想定リスク
1. **WebSocket接続の不安定性**
   - 対策: 自動再接続機能の実装
   - 対策: フェイルオーバー機能の検討

2. **データフォーマット変更**
   - 対策: 柔軟なパーサー実装
   - 対策: バージョン管理機能

3. **パフォーマンス劣化**
   - 対策: 非同期処理の最適化
   - 対策: バッファリング機能

4. **順序保証の失敗**
   - 対策: シンボル別SingleThreadExecutorによる厳密な順序制御
   - 対策: データベースのタイムスタンプとシーケンス番号による検証
   - 対策: エラー発生時の処理停止と手動復旧機能

5. **BigQuery非同期保存の問題**
   - 対策: H2データベースによる確実なデータ保持
   - 対策: BigQuery専用エラーハンドリングとリトライ機能
   - 対策: BigQuery障害時の自動復旧とアラート機能
   - 対策: H2からBigQueryへのバッチ同期機能（障害復旧用）

### 緊急時対応
- **ロールバック計画**: 既存Redis機能を一時的に復元
- **監視とアラート**: 接続状態とデータ処理状況の監視
- **手動フェイルオーバー**: 問題発生時のマニュアル切り替え

## 完了条件
1. ✅ 全ての新しいWebSocketクライアントが正常に動作
2. ✅ マーケットボードの更新が正常に機能
3. ✅ 取引データの挿入が正常に機能（H2同期・BigQuery非同期）
4. ✅ BigQuery非同期保存が正常に動作し、H2との整合性が確保
5. ✅ 順序保証機能（シンボル別）が正常に動作
6. ✅ 全ての既存テストがパス
7. ✅ パフォーマンスが既存システムと同等以上（特にWebSocketスループット）
8. ✅ BigQuery障害時でもシステムが継続動作
9. ✅ Redis関連コンポーネントが完全に削除
10. ✅ 24時間連続稼働テストが成功（BigQuery非同期処理含む）
11. ✅ H2監視APIが正常に動作し、容量管理が自動化
12. ✅ 監視ダッシュボードとの連携が完了

## 進捗管理
- [ ] Phase 1: 新規コンポーネント実装
- [ ] Phase 2: WebSocketクライアント実装  
- [ ] Phase 3: 既存サービス統合・BigQuery非同期化
- [ ] Phase 4: Redis依存削除
- [ ] Phase 5: 監視機能・最終テスト

---
作成日: 2025-08-01  
最終更新: 2025-08-01  
作成者: Claude Code Assistant