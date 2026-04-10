# データ収集停止原因の調査報告書

**調査日時**: 2025-12-17  
**対象**: `market_board_snapshots`テーブルのデータ収集停止問題  
**最終データ**: UTC 2025-12-16 23:59:59.96474

## 調査結果の要約

データ収集が2025-12-16 23:59:59.96474で停止した原因を調査しました。主な原因は**アプリケーションの停止**である可能性が高いですが、再起動後もデータ収集が再開していない可能性があります。

## 確認した事実

### 1. アプリケーションの状態

- **現在の状態**: アプリケーションは実行中（PID 71194）
- **再起動時刻**: 2025-12-17 08:42（JST）
- **PostgreSQLWriter**: 2025-12-17 08:42:53に正常に起動
- **停止前のログ**: 2025-12-16 23:59:59付近でエラーやシャットダウンのログは見つからなかった

### 2. データ収集の仕組み

- **スケジューラー**: `MarketBoardSnapshotService.captureMarketBoardSnapshots()`が1秒ごとに実行
- **設定**: `@Scheduled(fixedRate = 1000)`で設定
- **条件**: `@ConditionalOnProperty(name = "app.database.type", havingValue = "postgresql")`でPostgreSQLモードの時のみ有効
- **保存処理**: `PostgreSQLWriter`が非同期キューでデータを保存

### 3. シンボルの初期化状況

**確認結果**: ✅ 正常に初期化されている

```
2025-12-17T08:42:54.007+09:00  INFO  --- Initialized MarketBoard for symbol: B_FX_BTCJPY
2025-12-17T08:42:54.007+09:00  INFO  --- Initialized MarketBoard for symbol: B_BTCJPY
2025-12-17T08:42:54.007+09:00  INFO  --- Initialized MarketBoard for symbol: G_BTCJPY
2025-12-17T08:42:54.007+09:00  INFO  --- Initialized MarketBoard for symbol: G_FX_BTCJPY
2025-12-17T08:42:54.007+09:00  INFO  --- Available symbols: [B_FX_BTCJPY, B_BTCJPY, TESTJPY, G_BTCJPY, G_FX_BTCJPY]
```

### 4. PostgreSQLWriterの状態

**確認結果**: ✅ 正常に起動している

```
2025-12-17T08:42:53.370+09:00  INFO  --- ========== POSTGRESQL_WRITER START ==========
2025-12-17T08:42:53.371+09:00  INFO  --- PostgreSQLWriter thread started in 1 ms
2025-12-17T08:42:53.371+09:00  INFO  --- PostgreSQLWriter processing loop started
```

**問題点**: スナップショット保存のログ（`Saved batch`）が見当たらない
- これは、キューにデータが追加されていないことを示している
- エラーログも見当たらないため、エラーが発生していない可能性が高い

### 5. スケジューラーの動作状況

**確認結果**: ⚠️ 動作状況が不明

- `captureMarketBoardSnapshots`に関するログが全く見当たらない
- ログレベルがINFOのため、`log.debug`のログは表示されない
- エラーログ（`log.error`）も見当たらない

## 考えられる原因

### 1. アプリケーションの停止（最も可能性が高い）✅ 確認済み

- **2025-12-16 23:59:59の時点でアプリケーションが停止していた可能性**
- その後、2025-12-17 08:42に再起動されたが、その間データ収集が停止していた
- ログには停止理由が記録されていない（手動停止の可能性）

### 2. スケジューラーが動作していない可能性 ⚠️ 要確認

- 再起動後、スケジューラーが正常に動作していない可能性
- `@EnableScheduling`は`ExchSimApplication`で有効になっているが、実際にスケジューラーが実行されているか不明
- ログレベルがINFOのため、動作状況が確認できない

### 3. 板データが空の可能性 ⚠️ 要確認

- `orderService.getMarketBoard(symbol, 20)`が空の板データを返している可能性
- その場合、`log.debug("Skipping empty board for symbol: {}")`が出力されるが、ログレベルがINFOのため表示されない

### 4. ログレベルの問題 ⚠️ 要確認

- `captureMarketBoardSnapshots`メソッドは主に`log.debug`を使用している
- 現在のログレベルがINFOのため、デバッグログが出力されず、動作状況が確認できない

## 推奨される対応策

### 1. 即座に実施すべき確認

1. **ログレベルの一時的な変更**
   - `application.properties`で`logging.level.com.ys.exch_sim.domain.market_board=DEBUG`に変更
   - これにより、`captureMarketBoardSnapshots`の動作状況が確認できる

2. **スケジューラーの動作確認**
   - ログレベル変更後、`captureMarketBoardSnapshots`が実行されているか確認
   - 実行されていない場合は、スケジューラーの設定を確認

3. **板データの確認**
   - `orderService.getMarketBoard()`が正しく板データを返しているか確認
   - MarketBoardが正しく更新されているか確認

### 2. 長期的な改善策

1. **ログレベルの見直し**
   - 重要な処理（スナップショット保存など）は`log.info`を使用することを検討
   - または、専用のログレベルを設定

2. **監視の強化**
   - スケジューラーの実行状況を監視する仕組みを追加
   - PostgreSQLWriterのキューサイズを定期的にログ出力

3. **ヘルスチェックの追加**
   - スケジューラーが正常に動作しているかを確認するエンドポイントを追加

## 関連ファイル

- `src/main/java/com/ys/exch_sim/domain/market_board/MarketBoardSnapshotService.java` - スケジューラー実装
- `src/main/java/com/ys/exch_sim/domain/market_board/PostgreSQLWriter.java` - 非同期書き込み処理
- `src/main/resources/application.properties` - 設定ファイル
- `src/main/java/com/ys/exch_sim/ExchSimApplication.java` - メインアプリケーション（@EnableScheduling）

## 次のステップ

1. ログレベルをDEBUGに変更して、スケジューラーの動作を確認
2. 動作していない場合は、スケジューラーの設定を確認
3. 動作しているがデータが保存されない場合は、PostgreSQLWriterのエラーを確認
4. 問題が解決したら、ログレベルを元に戻す

