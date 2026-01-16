#!/bin/bash

# Snapshot Serviceのリソース使用量を監視するスクリプト

PROCESS_NAME="snapshot-service"
INTERVAL=${1:-1}  # デフォルト1秒間隔

echo "Monitoring resource usage for: $PROCESS_NAME"
echo "Press Ctrl+C to stop"
echo "----------------------------------------"
printf "%-8s %-8s %-8s %-12s %-12s %-10s\n" "TIME" "CPU%" "MEM%" "RSS(MB)" "VSZ(MB)" "THREADS"
echo "----------------------------------------"

while true; do
    # プロセスIDを取得
    PID=$(pgrep -f "$PROCESS_NAME" | head -1)
    
    if [ -z "$PID" ]; then
        echo "$(date +%H:%M:%S) - Process not found"
        sleep $INTERVAL
        continue
    fi
    
    # リソース情報を取得
    STATS=$(ps -p $PID -o %cpu,%mem,rss,vsz,th 2>/dev/null | tail -1)
    
    if [ -z "$STATS" ]; then
        echo "$(date +%H:%M:%S) - Process not running"
        sleep $INTERVAL
        continue
    fi
    
    # 値を抽出
    CPU=$(echo $STATS | awk '{print $1}')
    MEM=$(echo $STATS | awk '{print $2}')
    RSS_KB=$(echo $STATS | awk '{print $3}')
    VSZ_KB=$(echo $STATS | awk '{print $4}')
    THREADS=$(echo $STATS | awk '{print $5}')
    
    # KBをMBに変換
    RSS_MB=$(echo "scale=2; $RSS_KB / 1024" | bc)
    VSZ_MB=$(echo "scale=2; $VSZ_KB / 1024" | bc)
    
    # 表示
    printf "%-8s %-8s %-8s %-12s %-12s %-10s\n" \
        "$(date +%H:%M:%S)" \
        "$CPU" \
        "$MEM" \
        "$RSS_MB" \
        "$VSZ_MB" \
        "$THREADS"
    
    sleep $INTERVAL
done

