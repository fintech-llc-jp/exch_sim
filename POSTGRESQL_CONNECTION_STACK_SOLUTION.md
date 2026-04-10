# PostgreSQL接続スタック問題の対処法

## 問題の概要

アプリケーション起動時にPostgreSQLへの接続が開始されるが、その後の処理に進まない。PostgreSQLを再起動すると正常に動作する。

## 原因

1. **起動時の接続プール初期化**: HikariCPの`minimum-idle=5`により、起動時に5つの接続を確立しようとする
2. **PostgreSQLが応答しない場合**: 接続タイムアウト（30秒）まで待機し、その間アプリケーションがスタックする
3. **起動時の初期化処理**: `DataMigrationInitializer`が`CommandLineRunner`として実行され、データベースアクセスを行う際にブロッキングする

## 実装した対処法

### 1. HikariCP接続プール設定の改善

**変更内容** (`application.properties`):

```properties
# minimum-idle: 0にしてlazy initializationを有効化（起動時の接続スタックを防止）
spring.datasource.hikari.minimum-idle=0

# connection-timeout: 10秒に短縮（30秒から）
spring.datasource.hikari.connection-timeout=10000

# validation-timeout: 接続検証のタイムアウトを3秒に設定
spring.datasource.hikari.validation-timeout=3000

# connection-test-query: 接続検証用のクエリを追加
spring.datasource.hikari.connection-test-query=SELECT 1

# initialization-fail-timeout: -1で無効化（起動をブロックしない）
spring.datasource.hikari.initialization-fail-timeout=-1
```

**効果**:
- 起動時に接続プールを初期化しないため、PostgreSQLが応答しない場合でもスタックしない
- 接続タイムアウトを短縮することで、問題の早期検出が可能
- 接続検証により、無効な接続を早期に検出

### 2. 起動時の初期化処理にタイムアウトとリトライを追加

**変更内容** (`DataMigrationInitializer.java`):

- データベース操作にタイムアウト（5秒）を設定
- 最大3回のリトライを実装
- リトライ間隔は1秒

**実装詳細**:

```java
// タイムアウト付きでデータベース操作を実行
private <T> T executeWithTimeout(Callable<T> operation, long timeoutMs, String operationName) throws Exception {
  ExecutorService executor = Executors.newSingleThreadExecutor();
  try {
    Future<T> future = executor.submit(operation);
    return future.get(timeoutMs, TimeUnit.MILLISECONDS);
  } catch (TimeoutException e) {
    log.error("[INIT_USERS] Operation {} timed out after {} ms", operationName, timeoutMs);
    throw new RuntimeException("Database operation timed out: " + operationName, e);
  } catch (ExecutionException e) {
    // エラーハンドリング
  } finally {
    executor.shutdownNow();
  }
}
```

**効果**:
- データベース操作がタイムアウトした場合、アプリケーションがスタックしない
- リトライにより、一時的な接続問題から自動回復
- エラーログにより、問題の早期発見が可能

## 期待される効果

1. **起動時のスタック防止**: `minimum-idle=0`により、起動時に接続を確立しないため、PostgreSQLが応答しない場合でもスタックしない
2. **早期エラー検出**: 接続タイムアウトを10秒に短縮することで、問題を早期に検出
3. **自動回復**: リトライロジックにより、一時的な接続問題から自動回復
4. **起動時間の短縮**: 接続プールのlazy initializationにより、起動時間が短縮される

## 追加の推奨事項

### 1. PostgreSQLの監視

PostgreSQLが応答しない原因を調査するため、以下を監視することを推奨します：

- PostgreSQLのログ（`/var/log/postgresql/`または`/usr/local/var/log/postgresql/`）
- 接続数（`SELECT count(*) FROM pg_stat_activity;`）
- ロック状況（`SELECT * FROM pg_locks;`）

### 2. 接続プールの監視

HikariCPの接続プール状態を監視するため、以下を追加することを推奨します：

```properties
# 接続プールの統計情報を有効化
spring.datasource.hikari.register-mbeans=true
```

### 3. ヘルスチェックエンドポイント

Spring Boot Actuatorを使用して、データベース接続のヘルスチェックを追加：

```properties
# Actuatorの有効化
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=when-authorized
```

### 4. 接続リトライの実装（オプション）

より堅牢な接続リトライを実装する場合、以下のライブラリの使用を検討：

- Resilience4j: サーキットブレーカーとリトライ機能
- Spring Retry: Springのリトライ機能

## トラブルシューティング

### 問題が解決しない場合

1. **PostgreSQLの状態確認**:
   ```bash
   # PostgreSQLが起動しているか確認
   ps aux | grep postgres
   
   # 接続数を確認
   psql -U postgres -d exch_sim -c "SELECT count(*) FROM pg_stat_activity;"
   ```

2. **ログの確認**:
   - アプリケーションログでタイムアウトエラーを確認
   - PostgreSQLログで接続エラーを確認

3. **接続プール設定の調整**:
   - `connection-timeout`をさらに短縮（例: 5秒）
   - `maximum-pool-size`を調整

4. **ネットワークの問題**:
   - ファイアウォールの設定を確認
   - ネットワークの遅延を確認

## 関連ファイル

- `src/main/resources/application.properties` - HikariCP設定
- `src/main/java/com/ys/exch_sim/domain/config/DataMigrationInitializer.java` - 初期化処理
- `src/main/java/com/ys/exch_sim/domain/config/DatabaseConfig.java` - データベース設定

## 変更履歴

- 2025-12-17: 初版作成
  - HikariCP接続プール設定の改善
  - 起動時の初期化処理にタイムアウトとリトライを追加

