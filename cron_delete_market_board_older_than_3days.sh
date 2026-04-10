#!/bin/bash
# cron 用: snapshot-service / exch-sim-rust を停止 → 3日より古い板スナップショット削除 → 両サービス再起動
# market_board_snapshots / market_board_price_levels のみ対象（delete_market_board_data_older_than_3days.sql）
#
# 使用例（root の crontab）:
#   0 3 * * * /opt/exch_sim/cron_delete_market_board_older_than_3days.sh >> /var/log/exch_sim_board_cleanup.log 2>&1
#
# 非 root の crontab で使う場合は、systemctl と sudo -u postgres に NOPASSWD を付けるか、root で実行してください。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/delete_market_board_data_older_than_3days.sql"

log() {
  echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"
}

if [[ ! -f "$SQL_FILE" ]]; then
  log "ERROR: SQL が見つかりません: $SQL_FILE"
  exit 1
fi

log "=== 開始: マーケットボード古データ削除（cron） ==="

log "停止: snapshot-service"
sudo systemctl stop snapshot-service

log "停止: exch-sim-rust"
sudo systemctl stop exch-sim-rust

log "SQL 実行: sudo -u postgres psql -d exch_sim -f ..."
sudo -u postgres psql -d exch_sim -v ON_ERROR_STOP=1 -f "$SQL_FILE"

log "起動: snapshot-service"
sudo systemctl start snapshot-service

log "起動: exch-sim-rust"
sudo systemctl start exch-sim-rust

log "状態確認"
sudo systemctl is-active snapshot-service exch-sim-rust || true

log "=== 完了 ==="
