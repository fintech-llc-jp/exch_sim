# Cloud Run へのデプロイメント ガイド

このドキュメントでは、Exchange Simulator を Google Cloud Run にデプロイする手順を説明します。

## 目次

1. [前提条件](#前提条件)
2. [準備](#準備)
3. [デプロイ手順](#デプロイ手順)
4. [環境設定](#環境設定)
5. [トラブルシューティング](#トラブルシューティング)
6. [本番運用](#本番運用)

## 前提条件

### 必須ツール

- **Google Cloud SDK** (gcloud CLI)
  - インストール: https://cloud.google.com/sdk/docs/install
  - バージョン確認: `gcloud --version`

- **Docker**
  - インストール: https://www.docker.com/products/docker-desktop
  - バージョン確認: `docker --version`

- **Java 17**
  - バージョン確認: `java -version`

### Google Cloud 環境

- **GCP プロジェクト**が設定されていること
- **BigQuery API** が有効化されていること
- **Cloud Run API** が有効化されていること
- **Container Registry** が有効化されていること

### 認証情報

- BigQuery へのアクセス権を持つサービスアカウント
- サービスアカウントキー JSON ファイル

## 準備

### 1. Google Cloud SDK のセットアップ

```bash
# gcloud CLI のインストール
# macOS の場合
brew install google-cloud-sdk

# ログイン
gcloud auth login

# プロジェクトの確認・設定
gcloud config list
gcloud config set project YOUR_PROJECT_ID
```

### 2. Docker 認証の設定

```bash
# Google Container Registry への認証を設定
gcloud auth configure-docker gcr.io
```

### 3. プロジェクト構成の確認

デプロイ前に、以下のファイルが存在することを確認してください：

- `Dockerfile` - Docker イメージの定義
- `.dockerignore` - Docker ビルド時に除外するファイル
- `build.gradle` - Gradle ビルド設定
- `src/main/resources/application-prod.properties` - 本番環境設定

## デプロイ手順

### 自動デプロイスクリプトを使用（推奨）

```bash
# プロジェクトディレクトリに移動
cd /path/to/exch_sim

# デプロイスクリプトを実行
./deploy-to-cloud-run.sh
```

スクリプトが以下を自動的に行います：
1. Google Cloud 認証確認
2. 必要な API の有効化
3. サービスアカウントの作成
4. Docker イメージの構築
5. Google Container Registry へのプッシュ
6. Cloud Run へのデプロイ
7. ヘルスチェック実行

### オプション付きデプロイ

```bash
# 特定のプロジェクトとリージョンを指定
./deploy-to-cloud-run.sh \
  -p my-project-id \
  -r asia-northeast1 \
  -m 2Gi \
  -c 4

# ビルドをスキップ（既存イメージを再利用）
./deploy-to-cloud-run.sh --skip-build

# 完全なヘルプを表示
./deploy-to-cloud-run.sh --help
```

### 手動デプロイ

自動スクリプトが使用できない場合は、以下の手順で手動デプロイできます。

#### ステップ 1: Google Cloud の初期化

```bash
gcloud config set project YOUR_PROJECT_ID
gcloud services enable cloudbuild.googleapis.com run.googleapis.com containerregistry.googleapis.com bigquery.googleapis.com
```

#### ステップ 2: サービスアカウントの作成

```bash
gcloud iam service-accounts create exch-sim
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:exch-sim@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/bigquery.dataEditor"
```

#### ステップ 3: Docker イメージの構築とプッシュ

```bash
docker build -t gcr.io/YOUR_PROJECT_ID/exch-sim:latest .
docker push gcr.io/YOUR_PROJECT_ID/exch-sim:latest
```

#### ステップ 4: Cloud Run へのデプロイ

```bash
gcloud run deploy exch-sim \
  --image gcr.io/YOUR_PROJECT_ID/exch-sim:latest \
  --platform managed \
  --region asia-northeast1 \
  --memory 1Gi \
  --cpu 2 \
  --timeout 3600 \
  --max-instances 10 \
  --allow-unauthenticated \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DATA_MIGRATION_ENABLED=true" \
  --service-account=exch-sim@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

## デプロイ後の確認

### 1. ヘルスチェック

```bash
SERVICE_URL=$(gcloud run services describe exch-sim \
  --region asia-northeast1 \
  --format='value(status.url)')

curl ${SERVICE_URL}/actuator/health
```

### 2. ログの確認

```bash
gcloud run logs read exch-sim --region asia-northeast1 --limit 50 --follow
```

### 3. API のテスト

```bash
curl -X POST ${SERVICE_URL}/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

## 環境設定

### 環境変数の設定

```bash
gcloud run deploy exch-sim \
  --set-env-vars="
    SPRING_PROFILES_ACTIVE=prod,
    DATA_MIGRATION_ENABLED=true,
    AUTH_BIGQUERY_ENABLED=true,
    JWT_SECRET=your-secure-secret-key
  " \
  --region asia-northeast1
```

### 主要な環境変数

| 環境変数 | 説明 | デフォルト値 |
|---------|------|-----------|
| `SPRING_PROFILES_ACTIVE` | Spring Boot プロファイル | prod |
| `DATA_MIGRATION_ENABLED` | データ移行の有効化 | true |
| `AUTH_BIGQUERY_ENABLED` | BigQuery 認証の有効化 | true |
| `GCP_PROJECT_ID` | GCP プロジェクト ID | tradingscreen |
| `JWT_SECRET` | JWT トークンの署名キー | 必須 |

## トラブルシューティング

### デプロイに失敗する

```bash
# Docker ビルドの確認（ローカルで実行）
./gradlew clean build -x test

# Docker キャッシュをクリア
docker system prune -a
```

### Cloud Run でアプリが起動しない

```bash
# ログを確認
gcloud run logs read exch-sim --region asia-northeast1 --limit 100
```

### BigQuery への接続がエラーになる

```bash
# サービスアカウントのロール確認
gcloud projects get-iam-policy YOUR_PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:serviceAccount:exch-sim@*"

# BigQuery へのアクセス権を付与
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:exch-sim@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/bigquery.dataOwner"
```

## よくある質問（FAQ）

### Q: メモリ・CPU 割り当ての推奨値は?

A: 以下のガイドラインを参考にしてください：

| ユースケース | メモリ | CPU |
|-----------|--------|-----|
| 開発環境 | 1Gi | 2 |
| 本番環境（低負荷） | 2Gi | 2 |
| 本番環境（中負荷） | 4Gi | 4 |

### Q: コスト削減のためには?

A: 以下の対策を検討してください：
- `--min-instances=0` で最小インスタンスをゼロに設定
- 不要な時間帯に削除

---

**最終更新**: 2025-11-09
