# Bitflyer to MarketBoard Data Flow Issues

## Identified Issues (2025-10-28)

### High Priority Issues

#### 1. Race Condition: Symbol-Level Lock vs ConcurrentHashMap
- **Location:** `MarketDataSyncService:68-71` vs `OrderService:336`
- **Issue:** MarketDataSyncService uses symbol-specific locks for board updates, but OrderService may access MarketBoard without consistent locking
- **Impact:** Data corruption in bid/ask levels, execution duplicates, order matching errors
- **Severity:** HIGH

#### 2. Asynchronous Delta Board Application Without Message Ordering
- **Location:** `BitflyerMarketDataClient:330-392`
- **Issue:** Board snapshots and deltas processed asynchronously without ordering guarantee. Delta can arrive before snapshot and be skipped (line 376)
- **Impact:** Board can revert to older state, missing intermediate price movements
- **Severity:** HIGH

### Medium Priority Issues

#### 3. Clearance Inconsistency: clearBids/clearAsks Before Order Addition
- **Location:** `MarketDataSyncService:76-77` and `MarketBoard:555-603`
- **Issue:** Board cleared (removing market maker orders) BEFORE new orders created. User orders may match against stale state during this window
- **Impact:** Orders matched at wrong prices
- **Severity:** MEDIUM

#### 4. Rate Limiting May Drop Critical Board Updates
- **Location:** `DirectMarketDataService:53-67`
- **Issue:** Board updates throttled to 1-second intervals. Rapid price movements may be skipped
- **Impact:** MarketBoard lags real market, stale prices for matching
- **Severity:** MEDIUM

#### 5. No Persistence for External Trade Data
- **Location:** `OrderedTradeProcessor:71-101`, `MarketDataSyncService:185-193`
- **Issue:** External market trade data explicitly NOT persisted, only processed for board updates
- **Impact:** Incomplete audit trail, cannot reconstruct market state history
- **Severity:** MEDIUM

#### 6. Symbol Mapping Failure Silent Handling
- **Location:** `DirectMarketDataService:45-51`, `MarketDataSyncService:62-65`
- **Issue:** Symbol mapping failures silently dropped with only debug logging
- **Impact:** Market data silently stops flowing without alerts
- **Severity:** MEDIUM

## Data Flow Architecture

```
Bitflyer WebSocket
  ↓
BitflyerMarketDataClient (snapshot/delta/execution processing)
  ↓
DirectMarketDataService (rate limiting, async processing)
  ↓
MarketDataSyncService (symbol locks, board updates)
  ↓
MarketBoard (order matching)
  ↓
OrderService (execution processing)
```

## Key Components

### BitflyerMarketDataClient
- Lines 330-353: handleBoardSnapshotMessage()
- Lines 355-392: handleBoardDeltaMessage()
- Lines 394-418: handleExecutionsMessage()
- Lines 487-548: applyBoardDelta()

### DirectMarketDataService
- Lines 40-42: processMarketBoardAsync() - non-blocking updates
- Lines 45-99: processMarketBoard() - sync with rate limiting
- Lines 103-135: processTradeAsync() & B_processTrade()

### MarketDataSyncService
- Lines 58-163: updateMarketBoard() - symbol-level locks
- Lines 166-208: insertTrade() - external data NOT persisted
- Lines 242-253: createMarketMakerOrder()

### MarketBoard
- Lines 198-228: newOrder()
- Lines 238-342: processMarketOrderMatching()
- Lines 344-439: processLimitOrderMatching()
- Lines 555-603: clearBids() & clearAsks()

## Testing Gaps
1. Concurrent board update stress tests
2. Symbol mapping failure scenarios
3. Message reordering/duplication validation
