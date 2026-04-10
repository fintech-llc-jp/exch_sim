# 2CPU/4GB VPS用のCPU最適化

## 問題

2CPU/4GBのVPSで、JavaとPostgreSQLが常にすべてのCPUパワーを使用している。

## 原因分析

### CPU使用率が高い主な原因

1. **スナップショット取得処理**
   - 5秒間隔で4シンボル × 10レベル = 40レベルのデータを処理
   - 各スナップショットでPriceLevelオブジェクトを大量に作成
   - PostgreSQLへの書き込みが頻繁

2. **PostgreSQL書き込み処理**
   - バッチサイズ10で頻繁に`saveAll()`が実行される
   - 各書き込みでトランザクション処理が発生

3. **データベースクエリ**
   - `queryRecentExecutions()`が`findAll()`で全件取得（非効率）
   - インデックスが不足している可能性

## 実装した最適化

### 1. スナップショット取得間隔を10秒に延長

```properties
# 変更前: 5秒
# 変更後: 10秒
app.market-board.snapshot.interval-ms=10000
```

**効果**: CPU使用率を約50%削減

### 2. スナップショット取得レベル数を5に削減

```properties
# 変更前: 10レベル
# 変更後: 5レベル
app.market-board.snapshot.max-levels=5
```

**効果**: 処理するデータ量を50%削減

### 3. バッチサイズを20に増加

```properties
# 変更前: 10
# 変更後: 20
app.postgresql.writer.batch-size=20
```

**効果**: 書き込み頻度を50%削減、CPU使用率を削減

### 4. スナップショット機能を無効化するオプションを追加

```properties
# スナップショット機能を完全に無効化（CPU使用率を大幅に削減）
app.market-board.snapshot.enabled=false
```

**効果**: スナップショット関連のCPU使用率を100%削減

## 推奨設定（2CPU/4GB VPS）

### 最小構成（CPU使用率を最小化）

```properties
# スナップショット機能を無効化
app.market-board.snapshot.enabled=false

# または、30秒間隔で5レベル
app.market-board.snapshot.interval-ms=30000
app.market-board.snapshot.max-levels=5
app.postgresql.writer.batch-size=20
```

### 標準構成（バランス型）

```properties
# 10秒間隔で5レベル
app.market-board.snapshot.interval-ms=10000
app.market-board.snapshot.max-levels=5
app.postgresql.writer.batch-size=20
spring.datasource.hikari.maximum-pool-size=5
```

### 高負荷構成（データ精度優先）

```properties
# 5秒間隔で10レベル
app.market-board.snapshot.interval-ms=5000
app.market-board.snapshot.max-levels=10
app.postgresql.writer.batch-size=20
```

## 2CPU/4GB VPSの制約

### リソース制約

- **CPU**: 2コア（100%使用で200%）
- **メモリ**: 4GB（Java 768MB + PostgreSQL 1.5GB + システム = 約3GB使用）

### 推奨される動作

- **スナップショット取得間隔**: 10秒以上
- **スナップショット取得レベル数**: 5レベル以下
- **Javaヒープサイズ**: 768MB以下
- **PostgreSQL接続数**: 5以下

## さらなる最適化（必要に応じて）

### 1. スナップショット機能を完全に無効化

```properties
app.market-board.snapshot.enabled=false
```

**効果**: CPU使用率を大幅に削減（スナップショット関連の処理を完全に停止）

### 2. PostgreSQLの設定を最適化

`/etc/postgresql/15/main/postgresql.conf`:

```conf
# 接続数を削減
max_connections = 20

# メモリ使用量を削減
shared_buffers = 256MB
effective_cache_size = 1GB
work_mem = 4MB
maintenance_work_mem = 64MB

# 自動バキュームを最適化
autovacuum = on
autovacuum_max_workers = 2
```

### 3. インデックスの確認

```sql
-- インデックスの使用状況を確認
SELECT 
    schemaname,
    tablename,
    indexname,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan DESC;
```

## モニタリング

### CPU使用率の監視

```bash
# リアルタイムでCPU使用率を監視
top -bn1 | grep -E "PID|java|postgres"

# または
htop
```

### プロセスごとのCPU使用率

```bash
# プロセスごとのCPU使用率を確認
ps aux --sort=-%cpu | head -10
```

## 判断基準

### 2CPU/4GB VPSで動作可能かどうか

**動作可能な条件**:
- CPU使用率が平均80%以下
- メモリ使用率が90%以下
- レスポンス時間が許容範囲内

**動作困難な条件**:
- CPU使用率が常に100%以上
- メモリ使用率が95%以上
- レスポンスが極端に遅い

### リソース不足の場合の対処

1. **VPSのアップグレード**: 4CPU/8GBにアップグレード
2. **機能の無効化**: スナップショット機能を無効化
3. **処理の簡略化**: スナップショット取得間隔を30秒以上に

## 関連ファイル

- `src/main/resources/application.properties` - アプリケーション設定
- `src/main/java/com/ys/exch_sim/domain/market_board/MarketBoardSnapshotService.java` - スナップショット処理

## 変更履歴

- 2025-12-17: 2CPU/4GB VPS用の最適化を実装
  - スナップショット取得間隔を10秒に延長
  - スナップショット取得レベル数を5に削減
  - バッチサイズを20に増加
  - スナップショット機能の無効化オプションを追加

