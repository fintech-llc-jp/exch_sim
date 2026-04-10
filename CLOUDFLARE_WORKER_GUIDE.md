# Cloudflare Workersからexch_simを呼び出す方法

## 概要

Cloudflare Workersからexch_simのREST APIを呼び出すことができます。exch_simは既にCORS設定が有効になっているため、外部からのアクセスが可能です。

## 前提条件

1. **exch_simが公開されていること**
   - 本番環境でexch_simがHTTPSで公開されている必要があります
   - 例: `https://api.exch-sim.com` または Cloud RunなどのURL

2. **Cloudflare Workersアカウント**
   - Cloudflareアカウントが必要です（無料プランでも利用可能）

## セットアップ方法

### 1. Wrangler CLIのインストール

```bash
npm install -g wrangler
# または
npm install wrangler --save-dev
```

### 2. Cloudflareにログイン

```bash
wrangler login
```

### 3. 設定ファイルの編集

`cloudflare-worker-wrangler.toml`の`EXCH_SIM_API_URL`を本番環境のURLに変更：

```toml
[env.production.vars]
EXCH_SIM_API_URL = "https://your-exch-sim-domain.com"
```

### 4. Workerのデプロイ

```bash
# JavaScript版
wrangler publish --config cloudflare-worker-wrangler.toml

# または、TypeScript版を使用する場合
wrangler publish --config cloudflare-worker-wrangler.toml --name exch-sim-proxy
```

## 使用方法

### 1. ログインしてJWTトークンを取得

```javascript
// フロントエンドから
const response = await fetch('https://your-worker.workers.dev/api/login', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    username: 'trader001',
    password: 'trader123',
  }),
});

const { token } = await response.json();
```

### 2. 板情報を取得（認証不要）

```javascript
const response = await fetch('https://your-worker.workers.dev/api/board?symbol=G_FX_BTCJPY');
const boardData = await response.json();
```

### 3. 注文一覧を取得（認証必要）

```javascript
const response = await fetch('https://your-worker.workers.dev/api/orders?symbol=B_FX_BTCJPY&status=NEW', {
  headers: {
    'Authorization': `Bearer ${token}`,
  },
});

const orders = await response.json();
```

### 4. 新規注文を発注（認証必要）

```javascript
const response = await fetch('https://your-worker.workers.dev/api/orders', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  },
  body: JSON.stringify({
    symbol: 'G_FX_BTCJPY',
    price: 5000000,
    quantity: 1,
    side: 'BUY',
    ordType: 'LIMIT',
    tif: 'GTC',
  }),
});

const result = await response.json();
```

### 5. ポジション情報を取得（認証必要）

```javascript
// サマリー
const response = await fetch('https://your-worker.workers.dev/api/positions?type=summary', {
  headers: {
    'Authorization': `Bearer ${token}`,
  },
});

const summary = await response.json();
```

## 利用可能なエンドポイント

### Worker経由のエンドポイント

| エンドポイント | メソッド | 認証 | 説明 |
|-------------|---------|------|------|
| `/api/login` | POST | 不要 | ログインしてJWTトークンを取得 |
| `/api/board` | GET | 不要 | 板情報を取得 |
| `/api/orders` | GET | 必要 | 注文一覧を取得 |
| `/api/orders` | POST | 必要 | 新規注文を発注 |
| `/api/positions` | GET | 必要 | ポジション情報を取得 |

### 直接exch_sim APIを呼び出す場合

Workerを使わずに、直接exch_sim APIを呼び出すことも可能です：

```javascript
// 直接呼び出し（CORS設定が有効なため）
const response = await fetch('https://your-exch-sim-domain.com/api/market/board/G_FX_BTCJPY');
const boardData = await response.json();
```

## セキュリティ考慮事項

1. **JWTトークンの管理**
   - トークンは安全に保存してください（localStorage、sessionStorage、またはHttpOnly Cookie）
   - トークンの有効期限（デフォルト24時間）を確認してください

2. **API URLの保護**
   - Worker内でAPI URLを環境変数として管理
   - 本番環境のURLを直接フロントエンドに公開しない

3. **CORS設定**
   - exch_simのCORS設定は既に`*`で許可されています
   - 本番環境では特定のドメインのみ許可することを推奨

## トラブルシューティング

### CORSエラーが発生する場合

exch_simの`SecurityConfig.java`でCORS設定を確認：

```java
configuration.setAllowedOriginPatterns(Arrays.asList("*"));
```

### 認証エラーが発生する場合

- JWTトークンが正しく送信されているか確認
- トークンの有効期限を確認
- `Authorization: Bearer <token>`の形式を確認

### タイムアウトエラーが発生する場合

Cloudflare Workersのデフォルトタイムアウトは30秒です。長時間かかる処理の場合は、exch_sim側で最適化を検討してください。

## 参考資料

- [Cloudflare Workers Documentation](https://developers.cloudflare.com/workers/)
- [exch_sim API仕様](./README.md#api仕様)









