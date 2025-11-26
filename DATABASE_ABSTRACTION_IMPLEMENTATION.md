# データベース層抽象化の実装完了報告

## 実装日
2025年1月

## 実装内容

### 1. インターフェース定義 ✅

- **DatabaseService** (`domain/database/DatabaseService.java`)
  - データベース操作の共通インターフェースを定義
  - Execution、Position、TradeHistory、User操作のメソッドを定義
  - 取引量計算のメソッドも定義

### 2. BigQuery実装 ✅

- **BigQueryDatabaseService** (`domain/database/impl/BigQueryDatabaseService.java`)
  - `DatabaseService`インターフェースを実装
  - 既存の`BigQueryService`をラップして使用
  - `@ConditionalOnProperty(name = "app.database.type", havingValue = "bigquery")`で条件付きBean化

### 3. PostgreSQL実装 ✅

- **PostgreSQLDatabaseService** (`domain/database/impl/PostgreSQLDatabaseService.java`)
  - `DatabaseService`インターフェースを実装
  - Spring Data JPAを使用してデータベース操作を実装
  - `@ConditionalOnProperty(name = "app.database.type", havingValue = "postgresql")`で条件付きBean化

- **UserEntity** (`domain/user/UserEntity.java`)
  - PostgreSQL用のユーザーエンティティ
  - JPAアノテーションを使用

- **UserRepository** (`domain/user/UserRepository.java`)
  - Spring Data JPAリポジトリ

### 4. 設定ファイル更新 ✅

- **application.properties**
  - `app.database.type=bigquery` を追加（デフォルト）
  - PostgreSQL設定のコメントを追加

- **DatabaseConfig** (`domain/config/DatabaseConfig.java`)
  - データベースサービスの設定クラス

### 5. 既存コードの移行（部分完了） ✅

- **PositionManager** (`domain/position/PositionManager.java`)
  - `DatabaseService`インターフェースを注入可能に
  - 主要メソッドで`DatabaseService`を優先的に使用
  - 後方互換性のため、既存の`BigQueryService`もサポート

## 使用方法

### BigQueryを使用する場合

`application.properties`で以下を設定：

```properties
app.database.type=bigquery
spring.cloud.gcp.project-id=your-project-id
spring.cloud.gcp.bigquery.dataset-name=your-dataset
```

### PostgreSQLを使用する場合

`application.properties`で以下を設定：

```properties
app.database.type=postgresql
spring.datasource.url=jdbc:postgresql://localhost:5432/exch_sim
spring.datasource.username=postgres
spring.datasource.password=password
spring.jpa.hibernate.ddl-auto=update
```

## 残りの作業

### 1. 他のサービスの移行

以下のサービスも`DatabaseService`インターフェースを使用するように更新が必要：

- **ExecutionQueueService** - 約定履歴の保存・取得
- **AuthController** - ユーザー登録
- **TradeController** - 取引データの挿入
- **BigQueryUserDetailsService** - ユーザー認証（DatabaseServiceを使用するように変更）
- **BigQueryVolumeCalculationService** - 取引量計算（DatabaseServiceを使用するように変更）

### 2. BigQueryWriterの抽象化

`BigQueryWriter`は非同期書き込みのために使用されているため、これも抽象化するか、`DatabaseService`に非同期書き込みメソッドを追加する必要があります。

### 3. PostgreSQLの依存関係追加

`build.gradle`にPostgreSQLドライバーを追加する必要があります：

```gradle
runtimeOnly 'org.postgresql:postgresql'
```

### 4. テスト

両方の実装に対して統合テストを実装する必要があります。

## 注意事項

1. **後方互換性**: 既存の`BigQueryService`への依存は段階的に削除する必要があります
2. **非同期処理**: `BigQueryWriter`の非同期書き込み機能は、PostgreSQL実装でも考慮する必要があります
3. **トランザクション**: PostgreSQLではトランザクション管理が重要です
4. **パフォーマンス**: BigQueryとPostgreSQLではパフォーマンス特性が異なるため、最適化が必要な場合があります

## 次のステップ

1. PostgreSQLドライバーを`build.gradle`に追加
2. 残りのサービスを`DatabaseService`インターフェースを使用するように更新
3. 統合テストを実装
4. ドキュメントを更新

