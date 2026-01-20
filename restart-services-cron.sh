#!/bin/bash
# 毎日午前4時にsnapshot-serviceとexch-sim-rustを再起動するスクリプト

# ログファイル
LOG_FILE="/var/log/exch-sim-restart.log"

# ログ関数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

log "=== サービス再起動開始 ==="

# snapshot-serviceを再起動
log "snapshot-serviceを再起動中..."
if systemctl restart snapshot-service; then
    log "snapshot-serviceの再起動に成功"
else
    log "ERROR: snapshot-serviceの再起動に失敗"
    exit 1
fi

# exch-sim-rustを再起動（サービス名を確認してください）
log "exch-sim-rustを再起動中..."
if systemctl restart exch-sim-rust; then
    log "exch-sim-rustの再起動に成功"
else
    log "ERROR: exch-sim-rustの再起動に失敗"
    exit 1
fi

log "=== サービス再起動完了 ==="
