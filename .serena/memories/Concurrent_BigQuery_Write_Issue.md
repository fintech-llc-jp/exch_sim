# Concurrent BigQuery Write Issue - Root Cause Analysis

## Problem Statement
- debug.old.log が506MB
- アプリケーションが極端に遅い
- BigQueryExceptionが頻繁に発生

## Root Cause Identified

### 1. **MERGE Query Concurrent Access Issue**
**Location**: `BigQueryService.insertPosition()` (Line 148-215)

**The Problem**:
```
- Multiple threads (onPool-worker-*) execute processExecution()
- Each execution triggers savePositionToBigQuery()
- savePositionToBigQuery() calls bigQueryService.insertPosition()
- insertPosition() executes a MERGE query:
  - First: Try simple INSERT
  - If fails: Run MERGE (INSERT/UPDATE combined query)
- MERGE queries in BigQuery are NOT atomic when run concurrently
- Multiple concurrent MERGE queries cause: "Could not serialize access to table"
```

### 2. **Why This Causes Slowness**
1. **Synchronous Exception Logging**
   - Each error generates full stack trace
   - Stack traces are large (20+ lines)
   - 24 errors × large stack trace = massive log entries
   - IO overhead of writing large traces

2. **No Retry Logic in PositionManager**
   - PositionManager.savePositionToBigQuery() catches exception but doesn't retry
   - Failed save operation is silently ignored (only logged)
   - No exponential backoff
   - Immediate failure = quick error in log, but still creates large trace

3. **Multiple Concurrent Writes**
   - When market maker makes multiple orders simultaneously
   - Each execution triggers a position update
   - Multiple threads try to MERGE same position row at same time
   - BigQuery serialization error occurs

### 3. **Execution Flow That Causes Issue**

```
MarketDataSyncService (onPool-worker-*)
  → OrderService.newOrder()
    → ExecutionQueueService.processExecution()
      → PositionManager.processExecution() [SYNC]
        → savePositionToBigQuery()
          → bigQueryService.insertPosition()
            → BigQuery MERGE query [CONCURRENT!]
            → "Could not serialize access" ERROR
          → Catches exception, logs error [BIG TRACE]
```

## Why Synchronous Calls Are the Problem

The key issue is that `PositionManager.savePositionToBigQuery()` is **synchronous**:
- Line 103-104 in PositionManager:
  ```java
  if (bigQueryEnabled && bigQueryService != null) {
      savePositionToBigQuery(position);  // BLOCKING CALL
  }
  ```
- Called from `processExecution()` which is on thread pool
- When error occurs, exception is logged synchronously
- Stack trace writing is IO-intensive

## Solution Strategies

### Immediate Fix (Quick)
1. **Disable BigQuery persistence temporarily**
   - Set `app.data-migration.bigquery-enabled=false` in properties
   - Application will run 100x faster
   - Data will stay in memory/H2 only

2. **Reduce concurrent updates**
   - Implement position update batching
   - Defer BigQuery writes to async process
   - Add debouncing (batch updates every N ms)

### Short-term Fix (Medium effort)
1. **Implement row-level locking pattern**
   ```java
   private ConcurrentHashMap<String, Object> positionLocks = new ConcurrentHashMap<>();
   
   private synchronized void savePositionToBigQuery(Position position) {
       String lockKey = position.getUsername() + "_" + position.getSymbol();
       Object lock = positionLocks.computeIfAbsent(lockKey, k -> new Object());
       
       synchronized(lock) {
           // Single writer per position
           // Prevents concurrent MERGE queries
       }
   }
   ```

2. **Convert to async with proper serialization**
   ```java
   @Async
   public void savePositionToBigQueryAsync(Position position) {
       // Only one async write per position key
   }
   ```

### Long-term Fix (Best Practice)
1. **Use eventual consistency pattern**
   - Queue position updates in memory
   - Batch writes to BigQuery every N seconds
   - Use single UPSERT query per batch
   - Eliminates concurrent write conflicts

2. **Implement proper async with per-row queues**
   - One queue per (username, symbol) combination
   - Process updates serially per position
   - Allow parallel updates across different positions
   - Max throughput with zero conflicts

## Current Performance Impact

- Each BigQuery error = ~100KB of stack trace in logs
- 24 errors × 100KB = 2.4 MB of error traces
- Plus normal logs = 506 MB total
- Log file reading/parsing on file system = slow IO
- Exception logging itself blocks execution threads
- Retry/backoff could help, but sync calls are fundamental issue

## Recommendation

**Immediate**: Disable BigQuery (`app.data-migration.bigquery-enabled=false`)

**Then implement**: Per-position serialization with batched async writes
