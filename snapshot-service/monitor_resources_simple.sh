#!/bin/bash

# シンプルなリソース監視スクリプト（bc不要）

PROCESS_NAME="snapshot-service"
INTERVAL=${1:-1}

echo "Monitoring: $PROCESS_NAME (Press Ctrl+C to stop)"
echo "Time      CPU%    MEM%    RSS(MB)"
echo "----------------------------------------"

while true; do
    PID=$(pgrep -f "$PROCESS_NAME" | head -1)
    
    if [ -z "$PID" ]; then
        echo "$(date +%H:%M:%S) - Process not found"
        sleep $INTERVAL
        continue
    fi
    
    # macOS用のpsコマンド（rssとvszはKB単位）
    ps -p $PID -o %cpu=,%mem=,rss=,vsz= 2>/dev/null | while read CPU MEM RSS_KB VSZ_KB; do
        if [ -n "$CPU" ] && [ -n "$RSS_KB" ]; then
            # KBをMBに変換（整数除算）
            RSS_MB=$((RSS_KB / 1024))
            # VSZは表示しない（macOSでは値が不正確な場合がある）
            
            printf "%-9s %-7s %-7s %-8s\n" \
                "$(date +%H:%M:%S)" \
                "$CPU" \
                "$MEM" \
                "$RSS_MB"
        fi
    done
    
    sleep $INTERVAL
done

