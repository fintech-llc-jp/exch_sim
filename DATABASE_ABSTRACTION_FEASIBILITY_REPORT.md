# データベース層抽象化の可能性調査レポート

## 調査日
2025年1月

## 調査目的
BigQueryへの強い依存を解消し、データベース層を抽象化してインターフェース経由でインジェクションできるようにする。その後、PostgreSQLサービスも作成してPostgreSQLでも動作するようにする。

## 現状分析

### 1. BigQueryへの依存箇所

#### 1.1 直接BigQuery APIを使用しているクラス
- **BigQueryService** (`domain/bigquery/BigQueryService.java`)
  - BigQueryの`com.google.cloud.bigquery.BigQuery`に直接依存
  - 主な操作：
    - `insertExecution()` - 約定データの挿入
    - `insertPosition()` / `upsertPosition()` - ポジションの挿入/更新
    - `insertTradeHistory()` - 取引履歴の挿入
    - `queryPosition()` - ポジションのクエリ
    - `queryAllPositions()` - 全ポジションのクエリ
    - `queryTradeHistory()` - 取引履歴のクエリ
    - `queryRecentExecutions()` - 最近の約定のクエリ
    - `registerUser()` - ユーザー登録
    - `userExists()` - ユーザー存在確認
    - `createTablesIfNotExist()` - テーブル作成

- **BigQueryUserDetailsService** (`security/service/BigQueryUserDetailsService.java`)
  - 直接`com.google.cloud.bigquery.BigQuery`を使用
  - ユーザー認証情報の取得

- **BigQueryVolumeCalculationService** (`domain/service/BigQueryVolumeCalculationService.java`)
  - 直接`com.google.cloud.bigquery.BigQuery`を使用
  - 取引量計算のためのクエリ実行

#### 1.2 BigQueryServiceを注入しているクラス
- **ExecutionQueueService** (`domain/service/ExecutionQueueService.java`)
  - `@Autowired(required = false) BigQueryService bigQueryService`
  - 約定履歴の初期化と保存

- **PositionManager** (`domain/position/PositionManager.java`)
  - `@Autowired(required = false) BigQueryService bigQueryService`
  - ポジションと取引履歴の保存・取得

- **TradeController** (`domain/controller/TradeController.java`)
  - `@Autowired(required = false) BigQueryService bigQueryService`
  - 取引データの挿入

- **AuthController** (`security/controller/AuthController.java`)
  - `@Autowired(required = false) BigQueryService bigQueryService`
  - ユーザー登録

- **BigQueryWriter** (`domain/bigquery/BigQueryWriter.java`)
  - `private final BigQueryService bigQueryService`
  - 非同期書き込み処理

### 2. エンティティクラス
- **BigQueryExecutionEntity** - 約定データ
- **BigQueryPositionEntity** - ポジションデータ
- **BigQueryTradeHistoryEntity** - 取引履歴データ

これらのエンティティはBigQuery固有の変換ロジック（`toBigQueryRow()`, `fromBigQueryRow()`）を含んでいる。

## 抽象化の可能性評価

### ✅ 可能です

データベース層の抽象化は**技術的に可能**です。以下の理由から：

1. **操作の共通性**: BigQueryで実行している操作（INSERT, UPDATE, SELECT, UPSERT）は、PostgreSQLでも同様に実行可能
2. **エンティティの独立性**: ドメインエンティティ（Execution, Position, TradeHistory）は既に存在し、データベース固有のエンティティから分離されている
3. **SpringのDI機能**: Springの依存性注入により、実装を切り替えることが容易

## 実装方針

### 1. インターフェース設計

以下のインターフェースを定義：

```java
// データベース操作の共通インターフェース
public interface DatabaseService {
    // Execution操作
    void insertExecution(ExecutionEntity execution);
    List<ExecutionEntity> queryRecentExecutions(LocalDateTime fromTime);
    
    // Position操作
    void upsertPosition(PositionEntity position);
    PositionEntity queryPosition(String username, String symbol);
    List<PositionEntity> queryAllPositions(String username);
    
    // TradeHistory操作
    void insertTradeHistory(TradeHistoryEntity tradeHistory);
    List<TradeHistoryEntity> queryTradeHistory(String username);
    List<TradeHistoryEntity> queryTradeHistory(String username, String symbol);
    List<TradeHistoryEntity> queryTradeHistory(String username, int limit);
    
    // User操作
    void registerUser(String username, String encodedPassword, List<String> roles);
    boolean userExists(String username);
    UserEntity loadUser(String username);
    
    // テーブル管理
    void createTablesIfNotExist();
    
    // 取引量計算（オプション）
    Map<String, Long> calculateVolumeBySymbol(LocalDateTime fromTime);
    Long calculateTotalVolume(LocalDateTime fromTime);
}
```

### 2. 実装クラス

#### 2.1 BigQuery実装
- `BigQueryDatabaseService implements DatabaseService`
- 既存の`BigQueryService`をリファクタリング

#### 2.2 PostgreSQL実装
- `PostgreSQLDatabaseService implements DatabaseService`
- Spring Data JPAまたはJDBCを使用

### 3. エンティティの抽象化

#### 3.1 共通エンティティインターフェース
```java
public interface ExecutionEntity {
    String getExecId();
    String getOrderId();
    String getUsername();
    String getSymbol();
    // ... その他のフィールド
}
```

#### 3.2 実装クラス
- `BigQueryExecutionEntity implements ExecutionEntity`
- `PostgreSQLExecutionEntity implements ExecutionEntity`

または、単一のエンティティクラスを使用し、マッパーで変換する方法も可能。

### 4. 設定による切り替え

`application.properties`で実装を切り替え：

```properties
# データベースタイプの選択
app.database.type=bigquery  # または postgresql

# BigQuery設定
spring.cloud.gcp.project-id=your-project-id
spring.cloud.gcp.bigquery.dataset-name=your-dataset

# PostgreSQL設定
spring.datasource.url=jdbc:postgresql://localhost:5432/exch_sim
spring.datasource.username=postgres
spring.datasource.password=password
```

### 5. Spring設定

```java
@Configuration
public class DatabaseConfig {
    
    @Bean
    @ConditionalOnProperty(name = "app.database.type", havingValue = "bigquery")
    public DatabaseService bigQueryDatabaseService() {
        return new BigQueryDatabaseService();
    }
    
    @Bean
    @ConditionalOnProperty(name = "app.database.type", havingValue = "postgresql")
    public DatabaseService postgreSQLDatabaseService() {
        return new PostgreSQLDatabaseService();
    }
}
```

## PostgreSQL実装時の考慮事項

### 1. SQL文の違い

#### BigQuery MERGE文 → PostgreSQL UPSERT
```sql
-- BigQuery
MERGE `project.dataset.positions` AS target
USING (SELECT ...) AS source
ON target.username = source.username AND target.symbol = source.symbol
WHEN MATCHED THEN UPDATE ...
WHEN NOT MATCHED THEN INSERT ...

-- PostgreSQL
INSERT INTO positions (...) VALUES (...)
ON CONFLICT (username, symbol) 
DO UPDATE SET ...;
```

#### タイムスタンプ形式
- BigQuery: TIMESTAMP型（マイクロ秒単位）
- PostgreSQL: TIMESTAMP型（マイクロ秒単位、互換性あり）

#### 配列型
- BigQuery: `roles`は配列型
- PostgreSQL: `roles TEXT[]`または別テーブルで管理

### 2. 非同期処理

- BigQuery: クエリジョブは非同期で実行される（`job.waitFor()`）
- PostgreSQL: 通常は同期処理。大量データ処理時は非同期処理を実装可能

### 3. トランザクション

- BigQuery: トランザクションサポートが限定的
- PostgreSQL: 完全なACIDトランザクションサポート

### 4. パフォーマンス

- BigQuery: 大規模データ分析に最適化
- PostgreSQL: リアルタイムトランザクション処理に最適化

## 実装手順（推奨）

### Phase 1: インターフェース定義
1. `DatabaseService`インターフェースを作成
2. 必要なエンティティインターフェースを定義

### Phase 2: BigQuery実装のリファクタリング
1. `BigQueryService`を`BigQueryDatabaseService`にリファクタリング
2. `DatabaseService`インターフェースを実装
3. 既存のコードを段階的に移行

### Phase 3: PostgreSQL実装
1. PostgreSQLのテーブルスキーマを設計
2. `PostgreSQLDatabaseService`を実装
3. Spring Data JPAエンティティとリポジトリを作成
4. テストを実装

### Phase 4: 統合テスト
1. 両方の実装で同じテストを実行
2. パフォーマンステスト
3. エラーハンドリングの確認

## 課題とリスク

### 1. エンティティの変換ロジック
- BigQuery固有の変換ロジック（`toBigQueryRow()`, `fromBigQueryRow()`）を抽象化する必要がある
- **解決策**: マッパーパターンを使用して変換ロジックを分離

### 2. BigQueryUserDetailsServiceとBigQueryVolumeCalculationService
- これらは直接BigQueryを使用している
- **解決策**: `DatabaseService`インターフェースに必要なメソッドを追加するか、別のサービス層を作成

### 3. データ型の違い
- BigQueryとPostgreSQLでデータ型の表現が異なる場合がある
- **解決策**: エンティティ層で統一された型を使用し、マッパーで変換

### 4. マイグレーション
- 既存のBigQueryデータをPostgreSQLに移行する必要がある場合
- **解決策**: データ移行スクリプトを作成

## 結論

**データベース層の抽象化は可能です。**

以下の理由から実装を推奨します：

1. ✅ **技術的実現可能性**: すべての操作が抽象化可能
2. ✅ **保守性の向上**: データベース実装を切り替え可能
3. ✅ **テスト容易性**: モック実装でテストが容易
4. ✅ **将来の拡張性**: 他のデータベース（MySQL、MongoDB等）への対応も容易

PostgreSQL実装も可能ですが、以下の点に注意が必要です：

- SQL文の違い（特にMERGE文）
- 配列型の扱い
- 非同期処理の実装方法
- パフォーマンス特性の違い

段階的な実装を推奨します。まずインターフェースを定義し、既存のBigQuery実装をリファクタリングしてから、PostgreSQL実装を追加するアプローチが安全です。

