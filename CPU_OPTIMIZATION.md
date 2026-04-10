# CPU使用率最適化

## 問題

VPSでExchSimを実行中、2CPUでCPU使用率が200%になっている。

## 原因分析

主なCPU使用率の原因：

1. **MarketBoardSnapshotService**: 1秒ごとに全シンボルのスナップショットを取得
2. **PostgreSQLWriter**: キューが空の時でも100msごとにpoll()を実行（busy waiting）
3. **スナップショット処理**: 各シンボルで20レベルまで処理

## 実装した最適化

### 1. PostgreSQLWriterの最適化

**変更前**:
- `poll()`を使用して100msごとにキューをチェック（busy waiting）
- キューが空でもCPUを消費

**変更後**:
- `BlockingQueue.take()`を使用して、データが来るまでブロック
- データがない時はCPUを使用しない
- スリープ時間を100ms → 500msに変更（シャットダウン時の動作）

**効果**: キューが空の時のCPU使用率を大幅に削減

### 2. スナップショット取得間隔の調整

**変更前**:
- 固定で1秒ごとに実行

**変更後**:
- 設定可能な間隔（デフォルト2秒）
- `app.market-board.snapshot.interval-ms=2000`

**効果**: スナップショット取得頻度を50%削減

### 3. スナップショット取得レベル数の削減

**変更前**:
- 固定で20レベルまで取得

**変更後**:
- 設定可能なレベル数（デフォルト10レベル）
- `app.market-board.snapshot.max-levels=10`

**効果**: 処理するデータ量を50%削減

## 設定項目

`application.properties`に以下の設定を追加：

```properties
# スナップショット取得間隔（ミリ秒）
# 1秒=1000ms, 2秒=2000ms, 5秒=5000ms
app.market-board.snapshot.interval-ms=2000

# スナップショット取得レベル数
# 10レベル（デフォルト）、20レベル（詳細）
app.market-board.snapshot.max-levels=10

# PostgreSQL Writer キューが空の時のスリープ時間（ミリ秒）
app.postgresql.writer.empty-queue-sleep-ms=500
```

## 期待される効果

### CPU使用率の削減

- **スナップショット取得間隔**: 1秒 → 2秒で50%削減
- **処理レベル数**: 20 → 10で50%削減
- **PostgreSQLWriter**: busy waiting → blockingで大幅削減

**合計**: 約75%のCPU使用率削減が期待されます

### パフォーマンスへの影響

- **データ精度**: スナップショット間隔が2秒になっても、リアルタイム取引には影響なし
- **データ量**: レベル数を10に減らしても、主要な板情報は保持
- **レスポンス**: ブロッキング処理により、CPUリソースが他の処理に回る

## さらなる最適化（オプション）

### 1. スナップショット間隔の調整

よりCPU使用率を削減したい場合：

```properties
# 5秒間隔に変更（CPU使用率をさらに削減）
app.market-board.snapshot.interval-ms=5000
```

### 2. レベル数の調整

必要に応じてレベル数を調整：

```properties
# 5レベルに削減（さらにCPU使用率を削減）
app.market-board.snapshot.max-levels=5

# または20レベルに戻す（より詳細なデータが必要な場合）
app.market-board.snapshot.max-levels=20
```

### 3. バッチサイズの調整

PostgreSQL書き込みの効率を上げる場合：

```properties
# バッチサイズを増やす（書き込み効率向上）
app.postgresql.writer.batch-size=20
```

## モニタリング

CPU使用率を監視して、最適な設定を見つける：

```bash
# CPU使用率を監視
top -p $(pgrep -f ExchSimApplication)

# またはhtopを使用
htop -p $(pgrep -f ExchSimApplication)
```

## 関連ファイル

- `src/main/java/com/ys/exch_sim/domain/market_board/MarketBoardSnapshotService.java` - スナップショット取得処理
- `src/main/java/com/ys/exch_sim/domain/market_board/PostgreSQLWriter.java` - データベース書き込み処理
- `src/main/resources/application.properties` - 設定ファイル

## 変更履歴

- 2025-12-17: CPU使用率最適化を実装
  - PostgreSQLWriterのbusy waitingをblockingに変更
  - スナップショット取得間隔を2秒に変更
  - スナップショット取得レベル数を10に変更

