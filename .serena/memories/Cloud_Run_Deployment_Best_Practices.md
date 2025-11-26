# Cloud Run デプロイメント - ベストプラクティス

## 重要な学習点

### 1. マルチプラットフォーム対応（M1 Mac対応）
M1 Mac（ARM64）でビルドしたイメージはCloud Run（x86_64）で動作しない。
**解決方法**: Cloud Buildを使ってx86_64向けにビルド

```bash
gcloud builds submit --tag=gcr.io/PROJECT/IMAGE:latest
```

ローカルビルドは避け、常にCloud Buildで実行すること。

### 2. Spring Boot初期化のブロッキング問題
BigQueryからのデータ読み込みなど重い初期化処理がSpring Boot起動をブロックし、
Cloud Runのヘルスチェックがタイムアウトする。

**解決方法**: @Async で非同期初期化

1. `@PostConstruct` メソッドを2つに分割：
   - 同期部分: 基本初期化のみ
   - 非同期部分: BigQuery読み込み等

```java
@PostConstruct
public void initialize() {
    // 基本初期化
    initializeAsync(); // 非同期タスク起動
}

@Async
private void initializeAsync() {
    // BigQuery読み込み等の重い処理
}
```

2. メインアプリケーションクラスに `@EnableAsync` を追加

3. Dockerfile のHEALTHCHECK開始期間を120秒に設定

### 3. 推奨Dockerfile設定

```dockerfile
FROM openjdk:17.0.2-jdk-slim

# 省略...

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod
```

### 4. デプロイコマンド

```bash
# Cloud Buildでビルド＆GCRプッシュ
gcloud builds submit --tag=gcr.io/PROJECT/exch-sim:latest

# Cloud Runへデプロイ
gcloud run deploy exch-sim \
  --image gcr.io/PROJECT/exch-sim:latest \
  --platform managed \
  --region asia-northeast1 \
  --memory 1Gi \
  --cpu 2 \
  --timeout 3600 \
  --max-instances 10 \
  --allow-unauthenticated \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DATA_MIGRATION_ENABLED=true,AUTH_BIGQUERY_ENABLED=true" \
  --service-account=SERVICE_ACCOUNT@PROJECT.iam.gserviceaccount.com
```

### 5. トラブルシューティング

**エラー**: "exec format error"
- 原因: ARM64イメージをx86_64で実行
- 解決: Cloud Buildでx86_64向けビルド

**エラー**: "Container failed to start within allocated timeout"
- 原因: 起動時間が長すぎる（BigQuery読み込み等）
- 解決: 初期化を非同期化、HEALTHCHECK開始期間延長

**エラー**: "401 Unauthorized" (BigQuery)
- 原因: サービスアカウント認証の問題
- 解決: Workload Identityが自動で機能するため、アプリケーション側で適切なエラーハンドリング実装

### 6. デプロイ後の確認

```bash
# サービス状態確認
gcloud run services list --region asia-northeast1

# ログ確認
gcloud run services logs read exch-sim --region asia-northeast1 --limit 50

# ヘルスチェック
curl https://exch-sim-xxxxx.asia-northeast1.run.app/api/market/board/G_BTCJPY
```

## 2025-11-09 の実績

- **問題**: M1 Macビルドのイメージ＋BigQuery初期化のブロッキング
- **最終成功**: Cloud Build x86_64ビルド＋非同期初期化で解決
- **デプロイ**: exch-sim revision 00006-c4x
- **URL**: https://exch-sim-953974838707.asia-northeast1.run.app
