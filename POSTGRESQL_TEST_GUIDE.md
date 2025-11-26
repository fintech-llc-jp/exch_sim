# PostgreSQLテストガイド

## 前提条件

PostgreSQLデータベースが以下の設定で準備済みであること：
- Host: localhost
- Port: 5432
- Database: exch_sim
- User: postgres
- Password: postgres123

## 設定方法

### 方法1: application.propertiesを直接編集

`src/main/resources/application.properties`で以下を設定：

```properties
# Database Configuration
app.database.type=postgresql

# PostgreSQL Configuration（既に設定済み）
spring.datasource.url=jdbc:postgresql://localhost:5432/exch_sim
spring.datasource.username=postgres
spring.datasource.password=postgres123
```

### 方法2: プロファイルを使用

Spring Boot起動時にプロファイルを指定：

```bash
java -jar app.jar --spring.profiles.active=postgresql
```

または、IDEで実行する場合：
- Run Configuration の VM options に追加: `-Dspring.profiles.active=postgresql`

## テーブル作成

アプリケーション起動時に、`spring.jpa.hibernate.ddl-auto=update`により自動的にテーブルが作成されます。

作成されるテーブル：
- `executions` - 約定データ
- `positions` - ポジションデータ
- `trade_history` - 取引履歴
- `users` - ユーザー情報
- `user_roles` - ユーザーロール（usersテーブルの関連テーブル）

## テスト手順

### 1. アプリケーション起動

```bash
./gradlew bootRun
```

または、プロファイルを指定：

```bash
./gradlew bootRun --args='--spring.profiles.active=postgresql'
```

### 2. データベース接続確認

起動ログで以下のようなメッセージが表示されれば接続成功：

```
HikariPool-1 - Starting...
HikariPool-1 - Start completed.
```

### 3. APIテスト

#### ユーザー登録
```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123",
    "roles": ["ROLE_USER"]
  }'
```

#### ログイン
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123"
  }'
```

#### ポジション取得
```bash
curl -X GET http://localhost:8080/api/positions \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

## トラブルシューティング

### 接続エラーが発生する場合

1. PostgreSQLが起動しているか確認：
```bash
# macOS
brew services list | grep postgresql

# Linux
sudo systemctl status postgresql
```

2. データベースが存在するか確認：
```bash
psql -U postgres -h localhost -c "\l" | grep exch_sim
```

3. 接続情報を確認：
```bash
psql -U postgres -h localhost -d exch_sim -c "SELECT version();"
```

### テーブルが作成されない場合

1. `spring.jpa.hibernate.ddl-auto=update`が設定されているか確認
2. アプリケーションログでエラーを確認
3. PostgreSQLのログを確認

### データが保存されない場合

1. トランザクションがコミットされているか確認
2. `@Transactional`アノテーションが正しく設定されているか確認
3. PostgreSQLのログを確認

## BigQueryとの切り替え

BigQueryに戻す場合は、`application.properties`で以下を設定：

```properties
app.database.type=bigquery
```

または、プロファイルを削除：

```bash
java -jar app.jar
```

## 注意事項

1. **データの永続化**: PostgreSQLではデータがローカルに保存されます
2. **パフォーマンス**: BigQueryとPostgreSQLではパフォーマンス特性が異なります
3. **トランザクション**: PostgreSQLでは完全なACIDトランザクションがサポートされます
4. **スケーラビリティ**: BigQueryは大規模データ分析に最適化されていますが、PostgreSQLはリアルタイムトランザクション処理に最適化されています

