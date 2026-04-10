# 古いデータ削除処理の変更

## 変更内容

古いデータの削除処理を、1時間ごとの定期実行から**起動時のみ実行**に変更しました。

## 変更理由

1時間ごとの削除処理が原因で、DBとの接続が切れる問題が発生していました。この問題を解決するため、削除処理を起動時にのみ実行するように変更しました。

## 実装の変更

### 1. MarketBoardSnapshotService.java

**変更前**:
```java
/** 古いデータを削除（market_board_snapshots と executions） 毎時0分に実行 */
@Scheduled(cron = "0 0 * * * ?") // 毎時0分に実行
@Transactional
public void cleanupOldData() {
```

**変更後**:
```java
/** 古いデータを削除（market_board_snapshots と executions） 起動時に実行 */
@Transactional
public void cleanupOldData() {
```

- `@Scheduled`アノテーションを削除
- メソッドはそのまま残し、起動時に呼び出されるように変更

### 2. DataMigrationInitializer.java

起動時の初期化処理に、古いデータの削除処理を追加：

```java
// Clean up old data on startup (market_board_snapshots and executions)
if (marketBoardSnapshotService != null) {
  long cleanupStart = System.currentTimeMillis();
  log.info("[CLEANUP] Starting old data cleanup on startup...");
  try {
    marketBoardSnapshotService.cleanupOldData();
    long cleanupEnd = System.currentTimeMillis();
    log.info("[CLEANUP] Completed in {} ms", (cleanupEnd - cleanupStart));
  } catch (Exception e) {
    long cleanupEnd = System.currentTimeMillis();
    log.warn("[CLEANUP] Failed to cleanup old data in {} ms - {}", (cleanupEnd - cleanupStart), e.getMessage(), e);
  }
} else {
  log.info("[CLEANUP] MarketBoardSnapshotService not available (PostgreSQL mode may not be enabled)");
}
```

### 3. application.properties

コメントを更新：

```properties
# 古いデータの削除: 起動時に実行、24時間以上古いデータを削除（market_board_snapshots と executions）
app.market-board.snapshot.retention-hours=24
```

## 動作

- **起動時**: アプリケーション起動時に、24時間以上古いデータ（`market_board_snapshots`と`executions`）を削除
- **実行中**: 定期実行は行わないため、DB接続が切れる問題が発生しない

## 注意事項

1. **データ保持期間**: 現在は24時間以上古いデータを削除します（`app.market-board.snapshot.retention-hours=24`）
2. **起動時の処理時間**: 大量のデータがある場合、起動時に削除処理が完了するまで時間がかかる可能性があります
3. **プロセス再起動**: ユーザーが後でプロセス自体の再起動を検討するとのことなので、その際に定期的な削除処理を再検討できます

## ログ出力

起動時の削除処理のログ例：

```
[CLEANUP] Starting old data cleanup on startup...
Cleaned up 12345 market board snapshots older than 2025-12-16T00:00:00 (retention: 24 hours)
Cleaned up 67890 executions older than 2025-12-16T00:00:00 (retention: 24 hours)
[CLEANUP] Completed in 1234 ms
```

## 関連ファイル

- `src/main/java/com/ys/exch_sim/domain/market_board/MarketBoardSnapshotService.java` - 削除処理の実装
- `src/main/java/com/ys/exch_sim/domain/config/DataMigrationInitializer.java` - 起動時の初期化処理
- `src/main/resources/application.properties` - 設定ファイル

## 変更履歴

- 2025-12-17: 1時間ごとの定期実行から起動時実行に変更





