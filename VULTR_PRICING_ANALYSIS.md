# Vultrでexch_sim + TradingScreen + PostgreSQLを動かす場合の費用分析

## 結論

**最小構成: 月額$3.50（約500円）**  
**推奨構成: 月額$6（約900円）**

## Vultrの料金プラン（2024年12月時点）

### 最安プラン: Optimized VX1

| 項目 | 仕様 |
|------|------|
| **価格** | **$3.50/月（約500円）** |
| **CPU** | 1 vCPU (AMD EPYC) |
| **メモリ** | 1 GB RAM |
| **ストレージ** | 25 GB NVMe SSD |
| **帯域幅** | 2 TB（プール） |
| **ネットワーク** | 10 Gbps |
| **DDoS保護** | 含まれる |
| **IPv4/IPv6** | 両方対応 |

### 推奨プラン: High Performance

| 項目 | 仕様 |
|------|------|
| **価格** | **$6/月（約900円）** |
| **CPU** | 1 vCPU |
| **メモリ** | 1 GB RAM |
| **ストレージ** | 25 GB NVMe SSD |
| **帯域幅** | 2 TB |
| **ネットワーク** | IPv4/IPv6対応 |

**注意:** $2.50プランは廃止されました。

## 必要なリソース

### exch_simの要件

- **Java 17+** ランタイム
- **メモリ**: 768MB（JAVA_OPTS設定）
- **ストレージ**: アプリケーション + ログ（約500MB〜1GB）

### PostgreSQLの要件

- **メモリ**: 最低256MB（推奨512MB）
- **ストレージ**: データベースファイル（初期は小さいが、成長する可能性あり）

### TradingScreenの要件

- **フロントエンド**: 静的ファイル（通常100MB以下）
- **API接続**: exch_simへのHTTPリクエスト

### システム要件

- **OS**: Ubuntu 22.04 LTS または Debian 12（推奨）
- **Docker**: コンテナ実行環境
- **システムメモリ**: OS + Docker + アプリケーション用

## リソース使用量の見積もり

### 最小構成（$3.50/月プラン）

| 項目 | 使用量 | 備考 |
|------|--------|------|
| **システム（OS + Docker）** | 200MB | Ubuntu + Docker |
| **exch_sim** | 768MB | Javaアプリケーション |
| **PostgreSQL** | 256MB | データベース |
| **TradingScreen** | 50MB | 静的ファイル + Nginx |
| **合計** | **1,274MB** | **1GBプランでは不足** ⚠️ |

**問題点:**
- 1GB RAMでは全サービスを同時に動かすのは困難
- スワップを使用するとパフォーマンスが大幅に低下

### 推奨構成（$6/月プラン）

同じ1GB RAMですが、より高性能なNVMeストレージを使用します。

**実際の運用:**
- メモリ使用量を最適化すれば動作可能
- ただし、同時接続数が多い場合はメモリ不足になる可能性

### より安全な構成（$12/月プラン）

| 項目 | 仕様 |
|------|------|
| **価格** | **$12/月（約1,800円）** |
| **CPU** | 1 vCPU |
| **メモリ** | **2 GB RAM** |
| **ストレージ** | 55 GB NVMe SSD |
| **帯域幅** | 3 TB |

**メリット:**
- メモリに余裕がある
- 複数ユーザーが同時にアクセスしても安定
- データベースの成長に対応可能

## 費用の内訳

### 最小構成（$3.50/月）

| 項目 | 費用 | 備考 |
|------|------|------|
| **Vultr VPS** | $3.50/月 | 1GB RAM、25GB SSD |
| **ドメイン** | $10/年 | 年額（約$0.83/月） |
| **合計** | **約$4.33/月（約650円）** | |

**注意:** メモリが不足する可能性が高い

### 推奨構成（$6/月）

| 項目 | 費用 | 備考 |
|------|------|------|
| **Vultr VPS** | $6/月 | 1GB RAM、25GB NVMe SSD |
| **ドメイン** | $10/年 | 年額（約$0.83/月） |
| **合計** | **約$6.83/月（約1,000円）** | |

### 安全な構成（$12/月）

| 項目 | 費用 | 備考 |
|------|------|------|
| **Vultr VPS** | $12/月 | 2GB RAM、55GB SSD |
| **ドメイン** | $10/年 | 年額（約$0.83/月） |
| **合計** | **約$12.83/月（約1,900円）** | |

## メモリ最適化の方法

### 1. Javaヒープサイズの削減

```bash
# application.properties または環境変数
JAVA_OPTS="-Xmx512m -Xms128m"
```

### 2. PostgreSQLのメモリ設定

```bash
# postgresql.conf
shared_buffers = 128MB
effective_cache_size = 256MB
maintenance_work_mem = 64MB
work_mem = 4MB
```

### 3. スワップの設定

```bash
# 1GBのスワップファイルを作成
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

### 4. Dockerコンテナのメモリ制限

```yaml
# docker-compose.yml
services:
  exch_sim:
    mem_limit: 512m
  postgres:
    mem_limit: 256m
```

## ストレージ使用量の見積もり

### 初期セットアップ

| 項目 | サイズ |
|------|--------|
| **OS (Ubuntu 22.04)** | 約3GB |
| **Docker** | 約500MB |
| **exch_sim (JAR)** | 約50MB |
| **PostgreSQL** | 約200MB |
| **TradingScreen** | 約100MB |
| **合計** | **約4GB** |

### データベースの成長

- **初期**: 10MB〜100MB
- **1ヶ月後**: 100MB〜500MB（使用状況による）
- **1年後**: 1GB〜5GB（取引量による）

**25GB SSDプランなら十分な容量があります。**

## 実装手順（最小構成）

### 1. VultrでVPSを作成

1. Vultrアカウントを作成
2. 「Deploy Server」をクリック
3. プランを選択: **Optimized VX1 ($3.50/月)**
4. OS: **Ubuntu 22.04 LTS**
5. リージョン: **Tokyo (日本)**
6. デプロイ

### 2. サーバーにSSH接続

```bash
ssh root@<your-server-ip>
```

### 3. DockerとDocker Composeをインストール

```bash
# Dockerのインストール
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh

# Docker Composeのインストール
apt-get update
apt-get install -y docker-compose-plugin
```

### 4. スワップファイルを作成（メモリ不足対策）

```bash
# 1GBのスワップファイル
fallocate -l 1G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile

# 永続化
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

### 5. docker-compose.ymlを作成

```yaml
version: '3.8'

services:
  postgres:
    image: timescale/timescaledb:latest-pg14
    environment:
      POSTGRES_DB: exch_sim
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres123
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    mem_limit: 256m
    restart: unless-stopped

  exch_sim:
    build: .
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/exch_sim
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: postgres123
      JAVA_OPTS: "-Xmx512m -Xms128m"
    ports:
      - "8080:8080"
    depends_on:
      - postgres
    mem_limit: 512m
    restart: unless-stopped

  trading_screen:
    image: nginx:alpine
    volumes:
      - ./trading_screen:/usr/share/nginx/html
    ports:
      - "80:80"
    restart: unless-stopped

volumes:
  postgres_data:
```

### 6. アプリケーションをデプロイ

```bash
# リポジトリをクローン
git clone <your-repo-url>
cd exch_sim

# ビルドと起動
docker compose up -d
```

## 月額費用の比較

| 構成 | 月額費用 | メモリ | ストレージ | 推奨度 |
|------|---------|--------|-----------|--------|
| **最小構成** | **$3.50（約500円）** | 1GB | 25GB | ⚠️ メモリ不足の可能性 |
| **推奨構成** | **$6（約900円）** | 1GB | 25GB NVMe | ✅ 最適化すれば動作可能 |
| **安全な構成** | **$12（約1,800円）** | 2GB | 55GB | ✅✅ 余裕がある |
| **Google Cloud Run** | **無料〜** | - | - | ✅ サーバー管理不要 |

## 追加費用

### オプション

- **バックアップ**: Vultrのスナップショット機能（$0.05/GB/月）
- **モニタリング**: 無料（Vultrのダッシュボード）
- **SSL証明書**: Let's Encrypt（無料）

### 帯域幅

- **2TB/月まで無料**（通常は十分）
- 超過分: $0.01/GB

## まとめ

### 最小構成（$3.50/月）

- **費用**: 約500円/月
- **問題点**: メモリが1GBのみで、最適化が必要
- **推奨**: 個人利用・テスト環境

### 推奨構成（$6/月）

- **費用**: 約900円/月
- **メリット**: NVMeストレージで高速、メモリ最適化で動作可能
- **推奨**: 小規模運用

### 安全な構成（$12/月）

- **費用**: 約1,800円/月
- **メリット**: メモリに余裕、複数ユーザー対応可能
- **推奨**: 本格運用

**結論: 最小構成なら月額約500円、推奨構成なら月額約900円で運用可能です。**









