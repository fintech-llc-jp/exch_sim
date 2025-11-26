# BigQueryからPostgreSQLへのユーザーデータ移行ガイド

## 概要

BigQueryに保存されているユーザーデータをPostgreSQLにコピーするための移行サービスを提供しています。

## 使用方法

### 方法1: 起動時に自動移行

`application.properties`で以下を設定：

```properties
# PostgreSQLを使用
app.database.type=postgresql

# BigQueryからPostgreSQLへユーザーデータを自動移行（起動時）
app.database.migrate-users-from-bigquery=true
```

アプリケーション起動時に自動的にユーザーデータが移行されます。

### 方法2: REST API経由で手動移行

1. アプリケーションを起動（PostgreSQLモードで）

2. 管理者権限でログインしてJWTトークンを取得

3. 移行APIを呼び出し：

```bash
curl -X POST http://localhost:8080/api/admin/migration/users \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

## 移行の動作

1. **BigQueryから全ユーザーを取得**
   - `users`テーブルから全レコードを取得
   - username, password, roles, created_at, updated_atを取得

2. **PostgreSQLに保存**
   - 既に存在するユーザーはスキップ（重複チェック）
   - 新規ユーザーのみ保存

3. **ログ出力**
   - 移行されたユーザー数
   - スキップされたユーザー数
   - エラーが発生したユーザー

## 注意事項

1. **BigQuery認証情報が必要**
   - 環境変数`GOOGLE_APPLICATION_CREDENTIALS`が設定されている必要があります
   - または、`application.properties`でBigQuery設定が正しく設定されている必要があります

2. **PostgreSQL接続が必要**
   - PostgreSQLデータベースが起動している必要があります
   - 接続情報が正しく設定されている必要があります

3. **重複チェック**
   - 既に存在するユーザーはスキップされます
   - 同じusernameのユーザーは上書きされません

4. **パスワード**
   - パスワードは暗号化された状態でコピーされます
   - BigQueryに保存されている形式のままPostgreSQLに保存されます

## トラブルシューティング

### BigQuery接続エラー

```
❌ BigQuery job failed: ...
```

- BigQuery認証情報を確認
- プロジェクトIDとデータセット名を確認

### PostgreSQL接続エラー

```
❌ Failed to migrate user: ...
```

- PostgreSQLが起動しているか確認
- 接続情報を確認
- テーブルが作成されているか確認（`spring.jpa.hibernate.ddl-auto=update`）

### ユーザーが移行されない

- BigQueryにユーザーデータが存在するか確認
- ログでエラーメッセージを確認
- 既に存在するユーザーはスキップされます

## 確認方法

移行後、PostgreSQLでユーザーを確認：

```sql
-- PostgreSQLに接続
psql -U postgres -d exch_sim

-- ユーザー一覧を確認
SELECT username, roles, created_at FROM users;

-- ユーザー数を確認
SELECT COUNT(*) FROM users;
```

