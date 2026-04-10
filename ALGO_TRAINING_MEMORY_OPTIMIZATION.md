# アルゴトレーニング用メモリ最適化設定

## 背景

アルゴリズムトレーディングのトレーニングにスナップショットデータが必要なため、スナップショット機能を有効化しつつ、OutOfMemoryErrorを防ぐための最適化を実施しました。

## 実施した最適化

### 1. スナップショット取得間隔の延長

**変更前**: 10秒間隔  
**変更後**: 30秒間隔

```properties
app.market-board.snapshot.interval-ms=30000
```

**効果**:
- データ取得頻度を1/3に削減
- メモリ使用量を約66%削減
- アルゴトレーニングには30秒間隔でも十分なデータが取得可能

**データ量の目安**:
- 10秒間隔: 1時間あたり360スナップショット
- 30秒間隔: 1時間あたり120スナップショット
- 1日あたり: 2,880スナップショット（十分なトレーニングデータ）

### 2. スナップショット取得レベル数の削減

**変更前**: 5レベル  
**変更後**: 3レベル

```properties
app.market-board.snapshot.max-levels=3
```

**効果**:
- 1スナップショットあたりのデータ量を約40%削減
- メモリ使用量を大幅に削減
- アルゴトレーニングには3レベル（Best Bid/Ask + 1レベル）で十分

### 3. バッチサイズの増加

**変更前**: 20件  
**変更後**: 50件

```properties
app.postgresql.writer.batch-size=50
```

**効果**:
- データベース書き込み頻度を削減
- CPU使用率を削減
- トランザクション効率が向上

### 4. キューサイズ制限の追加（重要）

**新規追加**: キューサイズ上限を500件に設定

```properties
app.postgresql.writer.queue-size-limit=500
```

**実装内容**:
- `LinkedBlockingQueue`にサイズ制限を設定
- キューが満杯の場合、古いスナップショットを破棄して新しいものを追加
- メモリ使用量を制御可能に

**動作**:
- キューサイズが500件に達すると、新しいスナップショットを追加する際に古いものを破棄
- メモリ使用量が一定以下に保たれる
- データ損失は最小限（最新のデータが優先される）

## メモリ使用量の推定

### スナップショット1件あたりのメモリ使用量

- **3レベル、4シンボル**の場合:
  - スナップショット本体: 約1KB
  - PriceLevel（3 bids + 3 asks）: 約0.5KB
  - **合計**: 約1.5KB/スナップショット

### キューサイズ500件の場合

- **メモリ使用量**: 約750KB（500件 × 1.5KB）
- **安全マージン込み**: 約1MB

### 全体のメモリ使用量

- **基本アプリケーション**: 約100-150MB
- **Spring Boot + 依存関係**: 約50-100MB
- **スナップショットキュー（500件）**: 約1MB
- **その他（接続プール、スレッドなど）**: 約50-100MB

**合計**: 約200-350MB（安全マージン込みで512MB推奨）

## 推奨JVM設定

### 2CPU/4GB VPS用

```bash
JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### systemdサービス設定

```ini
[Service]
# メモリ設定（512MBヒープ）
Environment="JAVA_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# メモリ制限を設定（OOM Killerを防ぐ）
MemoryLimit=1G
MemoryHigh=900M
```

## データ取得頻度の比較

| 設定 | 間隔 | 1時間あたり | 1日あたり | メモリ使用量 |
|------|------|------------|----------|-------------|
| 最適化前 | 10秒 | 360件 | 8,640件 | 高 |
| 最適化後 | 30秒 | 120件 | 2,880件 | 低 |

**アルゴトレーニングへの影響**:
- 30秒間隔でも十分なデータが取得可能
- 1日あたり2,880スナップショットは、トレーニングに十分な量
- データの質は変わらない（3レベルでもBest Bid/Ask + 1レベルで十分）

## モニタリング

### キューサイズの確認

アプリケーションログで以下を確認：

```
PostgreSQLWriter: Queued 100 snapshots (queue size: 50)
```

### キューが満杯になった場合の警告

```
PostgreSQLWriter: Queue full, dropped 100 snapshots (queue size: 500)
```

この警告が頻繁に表示される場合は：
1. `app.postgresql.writer.batch-size`を増やす（50→100）
2. `app.postgresql.writer.queue-size-limit`を増やす（500→1000）
3. ただし、メモリ使用量が増加するため注意

## トラブルシューティング

### OutOfMemoryErrorが発生する場合

1. **ヒープサイズを確認**
   ```bash
   ps aux | grep java
   # -Xmx512m が設定されているか確認
   ```

2. **キューサイズを確認**
   - ログで `Queue Size: X/500` を確認
   - 500に近い値が続く場合は、バッチサイズを増やす

3. **スナップショット間隔をさらに延長**
   ```properties
   app.market-board.snapshot.interval-ms=60000  # 60秒間隔
   ```

4. **レベル数をさらに削減**
   ```properties
   app.market-board.snapshot.max-levels=2  # 2レベル（Best Bid/Askのみ）
   ```

### データ取得が不足する場合

アルゴトレーニングに必要なデータ量が不足する場合は：

1. **間隔を短くする**（メモリに余裕がある場合）
   ```properties
   app.market-board.snapshot.interval-ms=20000  # 20秒間隔
   ```

2. **レベル数を増やす**（メモリに余裕がある場合）
   ```properties
   app.market-board.snapshot.max-levels=5  # 5レベル
   ```

3. **VPSのメモリを増やす**（根本的な解決策）
   - 4GB → 8GBにアップグレード

## まとめ

- ✅ スナップショット機能を有効化（アルゴトレーニング用）
- ✅ メモリ使用量を大幅に削減（間隔30秒、3レベル、キューサイズ制限）
- ✅ OutOfMemoryErrorを防ぐ（キューサイズ制限、ヒープサイズ512MB）
- ✅ アルゴトレーニングに十分なデータを取得（1日あたり2,880スナップショット）

**推奨設定**:
- `app.market-board.snapshot.interval-ms=30000`
- `app.market-board.snapshot.max-levels=3`
- `app.postgresql.writer.batch-size=50`
- `app.postgresql.writer.queue-size-limit=500`
- `-Xmx512m`（JVMヒープサイズ）

