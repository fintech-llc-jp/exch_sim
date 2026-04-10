# Cloudflareでexch_sim/TradingScreenを動かす場合の費用分析

## 結論

**exch_simとTradingScreenをCloudflare上で直接動かすことはできません。**  
しかし、**Cloudflare Tunnelを使えば、既存のサーバーを無料で公開できます。**

## なぜ直接動かせないのか

### 1. Cloudflare Workersの制限

exch_simは**Java 17 + Spring Boot 3.x**で動作するサーバーアプリケーションですが、Cloudflare Workersは以下の制限があります：

#### 無料プランの制限
- **CPU時間**: 10ミリ秒/リクエスト（非常に短い）
- **メモリ**: 128MB
- **リクエスト**: 100,000リクエスト/日
- **実行環境**: JavaScript/TypeScriptのみ（Javaは不可）

#### 有料プラン（$5/月〜）
- **CPU時間**: 30秒/リクエスト
- **メモリ**: 128MB
- **リクエスト**: 1,000万リクエスト/月

**問題点:**
- Spring Bootアプリの起動には数秒〜数十秒かかる
- CPU時間10msでは起動すらできない
- Javaアプリケーションは実行できない（JavaScript/TypeScriptのみ）

### 2. exch_simの要件

- **Java 17+** ランタイム
- **PostgreSQL** データベース（常時接続が必要）
- **常時起動** が必要なサーバーアプリケーション
- **メモリ**: 最低512MB以上推奨（現在の設定では768MB）

これらはCloudflare Workersでは実現できません。

## 解決策: Cloudflare Tunnel（無料）

### Cloudflare Tunnelとは

既存のサーバー（自宅サーバー、VPS、ローカルPCなど）をCloudflare経由で無料で公開できるサービスです。

### 無料プランの内容

- **帯域幅**: **無制限** ✅
- **接続数**: 無制限
- **SSL証明書**: 自動発行・更新（無料）
- **DDoS保護**: 含まれる
- **CDN**: 含まれる

### 必要なもの

1. **サーバー**（別途必要）
   - 自宅サーバー（無料）
   - VPS（月額数百円〜数千円）
   - ローカルPC（無料、ただし常時起動が必要）

2. **Cloudflare Tunnel**（無料）
   - `cloudflared`をサーバーにインストール
   - 設定ファイルを作成
   - 起動するだけ

### 費用の内訳

| 項目 | 費用 | 備考 |
|------|------|------|
| **Cloudflare Tunnel** | **無料** | 帯域幅無制限 |
| **サーバー（VPS）** | 月額500円〜 | 例: ConoHa VPS、さくらのVPS |
| **ドメイン** | 年額1,000円〜 | 例: .com、.net |
| **PostgreSQL** | サーバー内でDocker実行 | 追加費用なし |
| **合計（最小構成）** | **月額500円〜** | VPS + ドメイン |

## 実装方法

### 1. VPSを用意（例: ConoHa VPS）

```bash
# 最小構成: 1GB RAM, 1コア, 50GB SSD
# 月額: 約500円
```

### 2. exch_simをデプロイ

```bash
# Docker Composeでexch_sim + PostgreSQLを起動
docker-compose up -d
```

### 3. Cloudflare Tunnelを設定

```bash
# cloudflaredをインストール
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x cloudflared-linux-amd64
sudo mv cloudflared-linux-amd64 /usr/local/bin/cloudflared

# ログイン
cloudflared tunnel login

# トンネルを作成
cloudflared tunnel create exch-sim

# 設定ファイルを作成
cat > ~/.cloudflared/config.yml <<EOF
tunnel: <tunnel-id>
credentials-file: /home/user/.cloudflared/<tunnel-id>.json

ingress:
  - hostname: api.yourdomain.com
    service: http://localhost:8080
  - service: http_status:404
EOF

# トンネルを起動
cloudflared tunnel run exch-sim
```

### 4. DNS設定

Cloudflare DashboardでDNSレコードを追加：
- タイプ: CNAME
- 名前: api
- ターゲット: `<tunnel-id>.cfargotunnel.com`

## 他の選択肢との比較

### Google Cloud Run（現在のデプロイ先）

| 項目 | 費用 |
|------|------|
| **無料枠** | 200万リクエスト/月、360,000 GB秒/月 |
| **超過分** | $0.40/100万リクエスト、$0.0000025/GB秒 |
| **最小費用** | **無料**（小規模利用の場合） |

**メリット:**
- サーバー管理不要
- 自動スケーリング
- 無料枠が充実

**デメリット:**
- 使用量に応じて課金
- コールドスタートがある

### AWS Lambda / Azure Functions

- 同様にサーバーレス
- ただし、Javaアプリの実行には制約がある
- 常時起動が必要なアプリには不向き

### 自宅サーバー + Cloudflare Tunnel

| 項目 | 費用 |
|------|------|
| **サーバー** | 無料（既存PC使用） |
| **Cloudflare Tunnel** | 無料 |
| **電気代** | 月額1,000円〜 |
| **合計** | **月額1,000円〜** |

**メリット:**
- 完全無料（電気代除く）
- 完全な制御

**デメリット:**
- 自宅のネットワークが安定している必要がある
- 停電対策が必要

## 推奨構成

### 小規模・個人利用の場合

```
自宅サーバー/PC + Cloudflare Tunnel
費用: 月額1,000円（電気代のみ）
```

### 本格運用の場合

```
VPS（ConoHa、さくら） + Cloudflare Tunnel
費用: 月額500円〜（VPS費用のみ）
```

### スケーラブルな運用の場合

```
Google Cloud Run（現在の構成）
費用: 無料枠内なら無料、超過分は従量課金
```

## まとめ

| 方法 | 費用 | メリット | デメリット |
|------|------|----------|------------|
| **Cloudflare Workers** | 無料 | - | ❌ Javaアプリは動かせない |
| **Cloudflare Tunnel + VPS** | 月額500円〜 | ✅ 無料枠が充実<br>✅ 帯域幅無制限 | サーバー管理が必要 |
| **Cloudflare Tunnel + 自宅サーバー** | 月額1,000円〜 | ✅ ほぼ無料 | ネットワーク安定性が必要 |
| **Google Cloud Run** | 無料〜 | ✅ サーバー管理不要<br>✅ 自動スケーリング | 使用量に応じて課金 |

**結論: Cloudflare Tunnelを使えば、既存のサーバーを無料で公開できますが、サーバー自体の費用は別途必要です。**
