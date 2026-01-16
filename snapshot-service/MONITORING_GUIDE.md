# リソース使用量監視ガイド

## 監視方法

### 方法1: シンプルな監視スクリプト（推奨）

```bash
cd snapshot-service
./monitor_resources_simple.sh
```

または、更新間隔を指定（デフォルト1秒）：

```bash
./monitor_resources_simple.sh 2  # 2秒間隔
```

### 方法2: topコマンド

```bash
# プロセス名でフィルタ
top -pid $(pgrep -f snapshot-service | head -1)
```

### 方法3: psコマンド（1回だけ）

```bash
ps -p $(pgrep -f snapshot-service | head -1) -o %cpu,%mem,rss,vsz,th
```

### 方法4: htop（インストールが必要）

```bash
# インストール
brew install htop

# 実行
htop
# 'F4'でフィルタ、'snapshot-service'と入力
```

## 監視項目の説明

- **CPU%**: CPU使用率（%）
- **MEM%**: メモリ使用率（システム全体に対する%）
- **RSS (MB)**: 実メモリ使用量（Resident Set Size、実際に使用している物理メモリ）
- **VSZ (MB)**: 仮想メモリサイズ（Virtual Size、仮想アドレス空間のサイズ）
- **THREADS**: スレッド数

## 期待される値（1秒間隔、8レベル、4銘柄）

### メモリ使用量
- **RSS**: 約20-40 MB
- **VSZ**: 約50-100 MB

### CPU使用率
- **macOS（ローカル）**: 約5-15%（環境による）
- **VPS（2CPU）**: 約20-28%

## 継続的な監視とログ記録

### ログファイルに記録

```bash
./monitor_resources_simple.sh 5 | tee resource_usage.log
```

### 統計情報の集計

```bash
# 平均CPU使用率を計算
grep -v "Process not" resource_usage.log | awk '{sum+=$2; count++} END {print "Average CPU: " sum/count "%"}'

# 最大メモリ使用量を確認
grep -v "Process not" resource_usage.log | awk '{if($4>max) max=$4} END {print "Max RSS: " max " MB"}'
```

## トラブルシューティング

### プロセスが見つからない場合

```bash
# プロセスが実行中か確認
ps aux | grep snapshot-service

# プロセスIDを直接指定
./monitor_resources_simple.sh
# または
ps -p <PID> -o %cpu,%mem,rss,vsz
```

### メモリ使用量が異常に高い場合

1. キューサイズを確認（ログで確認）
2. メモリリークの可能性を調査
3. キューサイズ制限を減らす

### CPU使用率が異常に高い場合

1. スナップショット間隔を確認
2. レベル数を確認
3. DB書き込み頻度を確認

