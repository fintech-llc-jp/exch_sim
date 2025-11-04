# External Trade Data to Execution Flow Analysis

## Summary
External trade data (ExternalTradeData) received from WebSocket feeds is **NOT directly converted to Execution objects**. Instead, external trade data is logged for informational purposes only and **never persisted** in the system.

## Complete Data Flow

### 1. WebSocket Trade Reception
**Source**: BitflyerMarketDataClient.handleExecutionsMessage() (Line 394-418)

```
WebSocket Message (JSON)
  ↓ Parse execution data
  ↓ convertBitflyerTrade() (Line 550-559)
  ↓ Create ExternalTradeData record
  ↓ marketDataService.processTradeAsync(tradeData)
```

### 2. MarketDataService.processTradeAsync()
**File**: MarketDataService.java (Line 262-264)

- Async method decorated with @Async("marketDataTaskExecutor")
- Returns CompletableFuture<Void>
- **Immediately delegates to processTrade()**

```java
@Async("marketDataTaskExecutor")
public CompletableFuture<Void> processTradeAsync(ExternalTradeData data) {
  return CompletableFuture.runAsync(() -> processTrade(data));
}
```

### 3. MarketDataService.processTrade()
**File**: MarketDataService.java (Line 272-303)

**THIS IS THE KEY POINT** - Does NOT convert to Execution:

```java
public void processTrade(ExternalTradeData data) {
  try {
    String targetSymbol = mapSymbol(data.exchange(), data.symbol());
    if (targetSymbol == null) {
      log.debug("🔍 No symbol mapping found for {}:{}", data.exchange(), data.symbol());
      return;  // FILTER 1: Symbol not mapped
    }

    log.debug("💰 Processing Trade for {} -> {} - side: {}, price: {}, quantity: {}",
        data.exchange() + ":" + data.symbol(),
        targetSymbol,
        data.side(),
        data.price(),
        data.quantity());

    // DELEGATE TO OrderedTradeProcessor (does NOT convert to Execution)
    if (orderedTradeProcessor != null) {
      orderedTradeProcessor.submitTrade(targetSymbol, data);
    } else {
      log.warn("⚠️ OrderedTradeProcessor not available, trade processing skipped");
    }
  } catch (Exception e) {
    log.error("❌ Error processing trade for {}:{} - {}",
        data.exchange(), data.symbol(), e.getMessage(), e);
  }
}
```

### 4. OrderedTradeProcessor.submitTrade()
**File**: OrderedTradeProcessor.java (Line 44-64)

- Accepts ExternalTradeData
- Submits to symbol-specific single-threaded executor
- **Does NOT convert to Execution**

### 5. OrderedTradeProcessor.processTradeSynchronously()
**File**: OrderedTradeProcessor.java (Line 67-97)

**KEY FINDING**: This method ONLY LOGS the trade data. NO CONVERSION to Execution:

```java
private void processTradeSynchronously(String symbol, ExternalTradeData tradeData) {
  try {
    log.debug("🔄 Processing trade synchronously for symbol: {} - side: {}, price: {}, quantity: {}",
        symbol, tradeData.side(), tradeData.price(), tradeData.quantity());

    // NOTE: External market data (EXTERNAL_FEED) is NOT saved anywhere
    // Only user executions (from OrderService/TradeController) are saved to BigQuery
    log.debug("ℹ️ External market data NOT persisted (EXTERNAL_FEED data is not stored)");

    log.info("✅ Trade processed successfully for symbol: {} - side: {}, price: {}, quantity: {}",
        symbol, tradeData.side(), tradeData.price(), tradeData.quantity());
  } catch (Exception e) {
    log.error("❌ Synchronous trade processing failed for symbol: {}, trade: {}", symbol, tradeData, e);
    throw e;
  }
}
```

## Where Executions ARE Created

Execution objects are only created from:

### Source 1: Market Board Updates → Matched Orders (Internal)
**File**: MarketDataService.updateMarketBoard() (Line 169-255)

When external market board data updates arrive:
1. Market maker orders are created from board levels
2. These orders are added to MarketBoard
3. MarketBoard.addMarketMakerOrder() returns executions
4. Executions are processed by orderService.processExecutionsForQueue()

### Source 2: Direct Trade Insertion via API (User Generated)
**File**: TradeController.insertTrade() (Line 60-155)

When a user inserts a trade manually via /api/trade/insert:
1. Either places an order that matches existing board orders
2. Or inserts execution directly with ExecStatus.FILLED
3. Execution is saved to BigQuery (if enabled)
4. Execution is used for volume calculation

### Source 3: User Order Execution (Internal)
**File**: TradeController.processOrderThroughOrderService() (Line 445-513)

When user places an order:
1. OrderService.processNewOrder() processes the order
2. Returns OrderResponse with ExecutionDto objects
3. ExecutionDto objects are converted to Execution objects
4. Executions are saved to BigQuery

## Filtering and Transformation Points

### FILTER 1: Symbol Mapping
**Location**: MarketDataService.processTrade() (Line 274-278)
- ExternalTradeData.symbol (e.g., "BTC_JPY") must map to internal symbol (e.g., "B_BTCJPY")
- Mapping configured in MarketDataClientConfig
- **If no mapping exists, trade is silently dropped with debug log**

### FILTER 2: OrderedTradeProcessor Null Check
**Location**: MarketDataService.processTrade() (Line 289-293)
- If OrderedTradeProcessor bean is not available, trade is logged as warning but skipped
- This is unlikely in normal operation

### FILTER 3: BigQuery Execution Filter (NEW)
**Location**: ExecutionQueueService.enqueueExecution() (Lines 34-58)
- Only executions with ExecStatus.PARTIAL_FILL or ExecStatus.FILLED are persisted to BigQuery
- ExecStatus.NEW, REJECTED, CANCELED are NOT saved
- This prevents qty=0 data from appearing in BigQuery

## Important Design Decisions

1. **ExternalTradeData is NOT converted to Execution**
   - External trade data is reference data only
   - Used for market awareness, NOT for order matching or execution tracking
   - This is intentional design (see comment in OrderedTradeProcessor line 22)

2. **External trades do NOT trigger order matching**
   - Only market board updates (bid/ask changes) trigger matching
   - External trade data is informational only

3. **Executions are created only for:**
   - Market maker orders added from board updates
   - User-initiated trades via API
   - User order matching against the market

4. **Volume Calculation**
   - Uses Execution objects (internal trades only)
   - External trade data is NOT used for volume
   - This is why GMO trade volume is 0 (no trades flowing through Execution path)

## Potential Issues

### Issue 1: GMO Trade Data Never Persisted
- GMO trade data follows same path as Bitflyer
- But since it's not converted to Execution, it's never counted as volume
- External trades are informational only

### Issue 2: Symbol Mapping Silent Failure
- If symbol mapping fails (no mapping configured), trades are silently dropped
- Only a debug log is generated
- No error alert or count tracking

### Issue 3: External Trade Flow Dead Code
- OrderedTradeProcessor.processTradeSynchronously() essentially does nothing
- Receives data, logs it, then discards it
- This is intentional but could be confusing

## Recommendations

1. **If you want external trades to contribute to volume:**
   - Convert ExternalTradeData to Execution in OrderedTradeProcessor
   - Set isMarketMaker = true or create internal flag
   - Mark origin as EXTERNAL_FEED
   - Save to BigQuery

2. **If you want symbol mapping failures to be tracked:**
   - Add counter/metrics for failed symbol mappings
   - Log at WARN level with exchange and symbol for alerting
   - Consider allowing manual symbol mapping configuration

3. **If OrderedTradeProcessor is truly just informational:**
   - Document this clearly
   - Consider removing or marking as deprecated
   - Move logging to BitflyerMarketDataClient directly

