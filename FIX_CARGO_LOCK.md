# Cargoビルドロックの解除方法

`Blocking waiting for file lock on build directory` エラーが発生した場合の対処法です。

## 原因

- 前回のビルドプロセスが正常に終了せず、ロックファイルが残っている
- SSH接続が切れた際に、cargoプロセスが残っている
- 別のプロセスがビルドディレクトリをロックしている

## 対処法

### 方法1: スクリプトを使用（推奨）

```bash
cd /opt/exch_sim/exch-sim-rust
chmod +x ../fix-cargo-lock.sh
../fix-cargo-lock.sh
```

### 方法2: 手動で対処

```bash
cd /opt/exch_sim/exch-sim-rust

# 1. 実行中のcargoプロセスを確認
ps aux | grep cargo

# 2. 実行中のcargoプロセスを終了
pkill -9 cargo

# 3. ロックファイルを削除
rm -f target/.cargo-lock
rm -rf target/.cargo-lock

# 4. 再度ビルド
cargo build --release
```

### 方法3: より徹底的にクリーンアップ

```bash
cd /opt/exch_sim/exch-sim-rust

# 1. すべてのcargoプロセスを終了
pkill -9 cargo
pkill -9 rustc

# 2. ロックファイルを削除
find . -name ".cargo-lock" -type f -delete
find . -path "*/target/.rustc_info.json.lock" -type f -delete
find . -path "*/target/.cargo-lock" -type f -delete

# 3. ビルドキャッシュをクリーン（必要に応じて）
# cargo clean  # これは時間がかかるので、必要に応じてのみ実行

# 4. 再度ビルド
cargo build --release
```

## 確認コマンド

```bash
# 実行中のcargoプロセスを確認
ps aux | grep -E "(cargo|rustc)" | grep -v grep

# ロックファイルの存在を確認
find . -name ".cargo-lock" -o -name "*.lock" | grep -E "(target|cargo)"

# ビルドディレクトリの状態を確認
ls -la target/ 2>/dev/null | head -20
```

## 予防策

- ビルド中は`screen`や`tmux`を使用してSSH接続が切れてもプロセスが続行されるようにする
- 長時間のビルドはバックグラウンドで実行する

```bash
# screenを使用する場合
screen -S cargo-build
cargo build --release
# Ctrl+A, D でデタッチ

# 再接続
screen -r cargo-build

# tmuxを使用する場合
tmux new -s cargo-build
cargo build --release
# Ctrl+B, D でデタッチ

# 再接続
tmux attach -t cargo-build
```

## トラブルシューティング

### ロックファイルが削除できない場合

```bash
# ファイルの所有者を確認
ls -la target/.cargo-lock

# 強制的に削除
sudo rm -f target/.cargo-lock
```

### プロセスが終了しない場合

```bash
# プロセスIDを確認
ps aux | grep cargo

# 強制終了
kill -9 <PID>
```

### それでも解決しない場合

```bash
# ビルドディレクトリ全体を削除（時間がかかります）
cargo clean

# 再度ビルド
cargo build --release
```
