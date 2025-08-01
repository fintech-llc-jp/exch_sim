# BigQuery接続テストガイド

## 前提条件

1. **Google Cloud アカウント** と **BigQuery プロジェクト** が設定済み
2. **サービスアカウントキー** (JSON) が取得済み
3. プロジェクトでBigQuery APIが有効化済み

## テスト手順

### 1. 認証情報の設定（環境変数方式）

サービスアカウントキー（JSON形式）を環境変数で指定してください：

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/your/service-account-key.json"
```

例（このプロジェクト用）：
```bash
export GOOGLE_APPLICATION_CREDENTIALS="/Users/sakamoto.yukio/.ssh/tradingscreen-exch-sim-bigquery.json"
```

**セキュリティ上の利点**:
- ✅ 認証情報がGitリポジトリに含まれない
- ✅ 環境ごとに異なる認証情報を簡単に使用可能
- ✅ Google Cloud推奨のベストプラクティス

### 2. プロジェクト設定の確認

`application-bigquery.properties`で以下が設定されています：

```properties
spring.cloud.gcp.project-id=tradingscreen
spring.cloud.gcp.bigquery.dataset-name=test_repository
# 認証情報は環境変数 GOOGLE_APPLICATION_CREDENTIALS で提供
```

### 3. BigQueryデータセットの作成

Google Cloud Consoleで、または`gcloud`コマンドで以下のデータセットを作成：

```bash
# gcloud CLIを使用する場合
bq mk --location=US tradingscreen:test_repository
```

### 4. 環境変数でテストを有効化

BigQueryテストを実行する際は、以下の環境変数を設定：

```bash
export BIGQUERY_TEST_ENABLED=true
```

### 5. テスト実行

#### A. エンティティ変換テスト（認証情報不要）
```bash
./gradlew test --tests "BigQueryEntityTest"
```

このテストは：
- ✅ データモデルの変換ロジックをテスト
- ✅ BigQueryフォーマットへの変換をテスト
- ✅ 認証情報なしで実行可能

#### B. BigQuery統合テスト（認証情報必要）
```bash
export BIGQUERY_TEST_ENABLED=true
./gradlew test --tests "BigQueryIntegrationTest" -Dspring.profiles.active=bigquery
```

このテストは：
- 🔐 GoogleCloud認証情報が必要
- 📊 実際のBigQueryテーブル作成をテスト
- 💾 データ挿入/読み取りをテスト

### 6. 本番環境での有効化

本番環境でBigQueryを有効にする場合：

1. `application.properties`を更新：
   ```properties
   app.data-migration.bigquery-enabled=true
   ```

2. 適切な認証情報を設定：
   - サービスアカウントキー
   - または環境変数 `GOOGLE_APPLICATION_CREDENTIALS`

## テスト結果の確認

### 成功例
```
✅ BigQueryService is available and properly configured
✅ BigQuery tables created successfully
✅ Execution inserted to BigQuery successfully
✅ Position inserted to BigQuery successfully
✅ Trade history inserted to BigQuery successfully
🎉 Full BigQuery workflow completed successfully!
```

### 認証エラーの場合
```
⚠️ BigQueryService is not available (credentials not configured)
⚠️ Skipping table creation test - BigQueryService not available
```

## トラブルシューティング

### 1. 認証エラー
- サービスアカウントキーのパスを確認
- プロジェクトIDが正しいか確認
- BigQuery APIが有効化されているか確認

### 2. 権限エラー
- サービスアカウントに以下の権限があるか確認：
  - BigQuery Data Editor
  - BigQuery Job User

### 3. データセットエラー
- 指定されたデータセットが存在するか確認
- データセットのリージョンを確認

## セキュリティ注意事項

1. **サービスアカウントキーをGitにコミットしない**
2. **本番環境では環境変数での認証を推奨**
3. **最小権限の原則に従ってサービスアカウントを設定**

## データスキーマ

BigQueryに作成されるテーブル：

### executions テーブル
- exec_id (STRING)
- order_id (STRING) 
- username (STRING)
- symbol (STRING)
- exec_status (STRING)
- last_px (INT64)
- last_qty (INT64)
- counter_party_username (STRING)
- created_at (STRING)
- is_market_maker (BOOL)
- side (STRING)

### positions テーブル  
- id (STRING)
- username (STRING)
- symbol (STRING)
- total_buy_qty (INT64)
- total_buy_amount (FLOAT64)
- total_sell_qty (INT64)
- total_sell_amount (FLOAT64)
- net_qty (INT64)
- average_buy_price (FLOAT64)
- average_sell_price (FLOAT64)
- realized_pnl (FLOAT64)
- last_updated (STRING)

### trade_history テーブル
- exec_id (STRING)
- username (STRING)
- symbol (STRING)
- side (STRING)
- quantity (FLOAT64)
- price (FLOAT64)
- amount (FLOAT64)
- counter_party_username (STRING)
- timestamp (STRING)
- cl_ord_id (STRING)
- is_market_maker (BOOL)