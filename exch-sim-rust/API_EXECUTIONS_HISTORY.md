# 約定履歴 API（Rust版）

`GET /api/executions/history` の使い方とレスポンス形式です。

---

## 概要

- **エンドポイント**: `GET /api/executions/history`
- **認証**: 必須（JWT Bearer）
- **説明**: ログインユーザー自身の約定一覧を取得します。**FILLED** と **PARTIAL_FILL** の約定のみ返却し、NEW / CANCELED / REJECTED は含まれません。
- **並び順**: **約定日時が新しい順**（`created_at` DESC）

---

## リクエスト

### メソッド・URL

```
GET /api/executions/history
```

### ヘッダ

| ヘッダ名 | 必須 | 説明 |
|----------|------|------|
| `Authorization` | ○ | `Bearer <JWTトークン>` |

### クエリパラメータ

| パラメータ | 型 | デフォルト | 説明 |
|------------|-----|------------|------|
| `page` | number | `0` | ページ番号（0始まり） |
| `size` | number | `20` | 1ページあたりの件数 |
| `symbol` | string | （なし） | 銘柄で絞り込み（省略時は全銘柄） |

### 呼び出し例

```bash
# 全銘柄・1ページ目・20件
curl -s -X GET "http://localhost:8080/api/executions/history" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 銘柄指定（例: G_BTCJPY）
curl -s -X GET "http://localhost:8080/api/executions/history?symbol=G_BTCJPY" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 2ページ目・10件
curl -s -X GET "http://localhost:8080/api/executions/history?page=1&size=10" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## レスポンス

### 成功時（200 OK）

JSON のキーは **camelCase** です。

| フィールド | 型 | 説明 |
|------------|-----|------|
| `username` | string | 対象ユーザー名 |
| `page` | number | リクエストしたページ番号 |
| `size` | number | リクエストした1ページあたりの件数 |
| `totalPages` | number | 総ページ数（フィルタ後） |
| `totalElements` | number | 総件数（フィルタ後） |
| `executions` | array | 約定オブジェクトの配列 |

#### 約定オブジェクト（`executions[]` の要素）

| フィールド | 型 | 説明 |
|------------|-----|------|
| `execID` | string | 約定ID（UUID） |
| `clOrdID` | string | 注文ID（発注時にクライアントが指定したID） |
| `symbol` | string | 銘柄（例: G_BTCJPY） |
| `execStatus` | string | 約定状態（`"FILLED"` または `"PARTIAL_FILL"`） |
| `lastPx` | number | 約定価格（表示用・乗数で除算済み） |
| `lastQty` | number | 約定数量（表示用・乗数で除算済み） |
| `counterPartyUsername` | string \| null | 相手方ユーザー名 |
| `side` | string | 売買（`"BUY"` / `"SELL"`） |
| `createdAt` | string | 約定日時（ISO 8601 UTC、例: `2025-02-05T12:34:56.789Z`） |

### レスポンスサンプル（単一約定）

```json
{
  "username": "trader001",
  "page": 0,
  "size": 20,
  "totalPages": 1,
  "totalElements": 1,
  "executions": [
    {
      "execID": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "clOrdID": "order-abc-001",
      "symbol": "G_BTCJPY",
      "execStatus": "FILLED",
      "lastPx": 10250000.0,
      "lastQty": 0.001,
      "counterPartyUsername": "MARKET_MAKER",
      "side": "BUY",
      "createdAt": "2025-02-05T12:34:56.789Z"
    }
  ]
}
```

### エラー時

| ステータス | 内容 |
|------------|------|
| 401 | 未認証・トークンなし |
| 500 | サーバーエラー（body に `{ "error": "..." }`） |

---

## Partial Fill ＋ Fill のケース

1注文が複数回に分けて約定した場合、**同じ `clOrdID` に対して約定レコードが複数**できます。

- 最初の一部約定 → **PARTIAL_FILL** の約定が1件（その時点の約定価格・数量）
- 残りが約定 → **FILLED** の約定が1件（残り分の価格・数量）

API は **FILLED と PARTIAL_FILL のみ**を返し、**`created_at` の新しい順**で並べるため、同じ注文の「最後の約定（FILLED）」が先に、その前の「部分約定（PARTIAL_FILL）」が後に並びます。

### 例: 注文が 2 回に分けて約定した場合

注文 `clOrdID: "order-xyz-002"` で 0.003 BTC を指値買いし、  
まず 0.001 BTC が約定（PARTIAL_FILL）、その後 0.002 BTC が約定（FILLED）したとします。

レスポンスでは、同じ `clOrdID` の約定が **2 件**並びます（新しい約定が上）。

```json
{
  "username": "trader001",
  "page": 0,
  "size": 20,
  "totalPages": 1,
  "totalElements": 2,
  "executions": [
    {
      "execID": "exec-uuid-2222",
      "clOrdID": "order-xyz-002",
      "symbol": "G_BTCJPY",
      "execStatus": "FILLED",
      "lastPx": 10260000.0,
      "lastQty": 0.002,
      "counterPartyUsername": "trader002",
      "side": "BUY",
      "createdAt": "2025-02-05T12:35:10.123Z"
    },
    {
      "execID": "exec-uuid-1111",
      "clOrdID": "order-xyz-002",
      "symbol": "G_BTCJPY",
      "execStatus": "PARTIAL_FILL",
      "lastPx": 10250000.0,
      "lastQty": 0.001,
      "counterPartyUsername": "MARKET_MAKER",
      "side": "BUY",
      "createdAt": "2025-02-05T12:34:56.789Z"
    }
  ]
}
```

- **1件目**: 残りが約定した時点のレコード（FILLED, 0.002 @ 10260000）
- **2件目**: 最初の部分約定（PARTIAL_FILL, 0.001 @ 10250000）

この注文の**合計約定数量**は `0.001 + 0.002 = 0.003` です。  
クライアントでは **`clOrdID` でグループ化**し、`lastQty` を合計すれば注文単位の約定数量を計算できます。

---

## 補足

- 価格・数量は銘柄の乗数で除算した**表示用の値**です（DB 内部の raw 値ではない）。
- `execStatus` は API では **FILLED** / **PARTIAL_FILL** のいずれかです（NEW / CANCELED / REJECTED は返却されません）。
- 認証トークンは `/api/auth/login` で取得します。
