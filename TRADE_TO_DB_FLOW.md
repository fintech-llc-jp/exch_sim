# トレードがDBに記録されるまでのパス

## 外部トレードデータ（ExternalTradeData）→ DB保存までのフロー

外部取引所（GMO/Bitflyer）から受信したトレードデータが`executions`テーブルに保存されるまでのパス：

### 1. WebSocket受信
**`GmoMarketDataClient.handleTradeMessage()`** (GmoMarketDataClient.java:265-285)
- WebSocketから`trades`チャンネルのメッセージを受信
- `convertGmoTrade()`で`ExternalTradeData`に変換

### 2. 非同期処理開始
**`MarketDataService.processTradeAsync()`** (MarketDataService.java:254-256)
- WebSocketスレッドをブロックしないよう非同期処理
- `processTrade()`を呼び出し

### 3. シンボルマッピング
**`MarketDataService.processTrade()`** (MarketDataService.java:264-295)
- `mapSymbol()`で外部シンボル（例: `BTC_JPY`）を内部シンボル（例: `BTC/JPY`）に変換

### 4. 順序処理（シンボル別専用スレッド）
**`OrderedTradeProcessor.submitTrade()`** (OrderedTradeProcessor.java:39-59)
- シンボル別に専用スレッドを作成して順序処理を保証
- `processTradeSynchronously()`を専用スレッドで実行

### 5. Executionへの変換
**`OrderedTradeProcessor.processTradeSynchronously()`** (OrderedTradeProcessor.java:62-116)
- `ExternalTradeData` → `Execution` に変換
  - `username` = `"EXTERNAL_FEED"`
  - `counterPartyUsername` = `"EXTERNAL_FEED"`
  - `price/qty`をマルチプライヤーで内部値に変換（例: `price * 100`, `qty * 1000`）
  - `execID` = UUID生成
  - `orderID` = UUID生成（ダミー）

### 6. ExecutionQueueServiceに追加
**`ExecutionQueueService.addExecution()`** (ExecutionQueueService.java:103-143)
- メモリキャッシュに追加（`userExecutionQueues`, `executionHistoryBySymbol`）
- **`isMarketMaker=false` の場合のみ**DB保存を実行
- `databaseService.insertExecution(execution)`を呼び出し

### 7. DB保存（executionsテーブル）
**`PostgreSQLDatabaseService.insertExecution()`** (PostgreSQLDatabaseService.java:45-54)
- `executionRepository.save(execution)`を実行
- **`executions`テーブル**に保存

---

## ユーザーのExecution（注文約定）→ DB保存までのフロー

ユーザーの注文が約定した際の`Execution`が保存されるまでのパス：

### パスA: executionsテーブルへの保存

1. **注文約定**
   - 注文が約定して`Execution`オブジェクトが作成される

2. **ExecutionQueueServiceに追加**
   **`ExecutionQueueService.addExecution()`** (ExecutionQueueService.java:103-143)
   - メモリキャッシュに追加
   - **`isMarketMaker=false` の場合のみ**DB保存を実行
   - `databaseService.insertExecution(execution)`を呼び出し

3. **DB保存（executionsテーブル）**
   **`PostgreSQLDatabaseService.insertExecution()`** (PostgreSQLDatabaseService.java:45-54)
   - **`executions`テーブル**に保存

### パスB: trade_historyテーブルへの保存（ポジション更新時）

注文約定後、ポジション更新時に`trade_history`テーブルにも保存されます。

1. **PositionManagerで処理**
   **`PositionManager.processExecution()`** (PositionManager.java:80-206)
   - ポジションを更新（買い/売りによる現金残高の増減）
   - FIFO（先入先出）でOPEN/CLOSEを判定
   - 損益計算（CLOSE時）

2. **TradeHistoryの作成**
   (PositionManager.java:166-180)
   - `Execution`から`TradeHistory`オブジェクトを作成
   - OPEN/CLOSE、損益、マッチした約定IDなどを設定

3. **DB保存（trade_historyテーブル）**
   **`PostgreSQLDatabaseService.insertTradeHistory()`** (PostgreSQLDatabaseService.java:115-124)
   - **`trade_history`テーブル**に保存

---

## 重要なポイント

### テーブルの使い分け

1. **`executions`テーブル**
   - すべての約定（Execution）を保存
   - 外部トレードデータも含む（`username="EXTERNAL_FEED"`）
   - `isMarketMaker=false`の場合のみ保存

2. **`trade_history`テーブル**
   - ユーザーのポジション更新時にのみ作成・保存
   - OPEN/CLOSE判定と損益計算を含む
   - **外部トレードデータは保存されない**（ユーザーのポジション更新がないため）

### 外部トレードデータの扱い

- 外部トレードデータ（GMO/Bitflyer）は`executions`テーブルにのみ保存
- `username="EXTERNAL_FEED"`として記録される
- `trade_history`テーブルには保存されない（参照用のデータ）

### 処理の順序保証

- `OrderedTradeProcessor`により、同一シンボルのトレードは専用スレッドで順序処理される
- これにより、シンボル単位での処理順序が保証される
