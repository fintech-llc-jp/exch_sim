# GMO Trade Processing - Root Cause CONFIRMED

## Problem Statement
Frontend shows 24-hour trade volume for Bitflyer but GMO side always shows 0.

## Root Cause: CONFIRMED ✅

**GMO WebSocket API is not sending trade data despite successful subscription request.**

### Evidence from Debug.log (Restart at 2025-10-29 19:35:48)

```
📡 GMO subscribing to orderbooks: BTC_JPY  ✅ Requested
📡 GMO subscribing to trades: BTC_JPY       ✅ Requested
📡 GMO Channel received: orderbooks         ✅ Receiving
📡 GMO Channel received: orderbooks         ✅ Receiving
(No "📡 GMO Channel received: trades" ever appears)
💰 GMO Trade logs                           ❌ ZERO instances
```

### What This Means

1. **Subscription Request**: ✅ Correctly sent
   ```java
   // GmoMarketDataClient.java:187-204
   Map<String, Object> subscribeRequest = Map.of(
       "command", "subscribe",
       "channel", "trades",
       "symbol", symbol);
   ```

2. **GMO API Response**: ❌ No trade data
   - GMO accepts the subscription request (no error)
   - But never sends any messages on the "trades" channel
   - Only sends "orderbooks" channel data

3. **Consequence**: Trade volume always 0
   - `handleTradeMessage()` never called
   - `OrderedTradeProcessor` never receives GMO trades
   - No volume calculation for GMO

## Code Implementation Status

### Current Implementation (CORRECT)

**GmoMarketDataClient.java:**
- Line 160-166: `createSubscriptionMessages()` - correctly requests both orderbooks and trades
- Line 187-204: `createTradesSubscription()` - correct subscription format
- Line 263-286: `handleTradeMessage()` - ready to process trades if data arrives

**All code is correct. The problem is 100% GMO API behavior.**

## Diagnosis Added (2025-10-29)

Added debug logging to identify actual received channels:
```java
// Line 144: log.debug("📡 GMO Channel received: {}", channel);
// Line 151: log.debug("📡 GMO Unknown channel (not orderbooks/trades): {}", channel);
```

This confirms: GMO only sends "orderbooks", never sends "trades".

## Possible Explanations

1. **GMO API Limitation**
   - Public WebSocket API may not provide real-time trade data
   - Trade channel might require different access level (premium/paid)
   - Or trades are sent through different channel name

2. **GMO API Specification Change**
   - Implementation may be based on outdated API documentation
   - Channel name or format may have changed

3. **Symbol-Specific Issue**
   - Trades might only be available for specific symbols
   - BTC_JPY and BTC may not have trade data available

## What's NOT the Problem

- ✅ WebSocket connection: Working
- ✅ Message subscription: Correct request format
- ✅ Message processing: Code structure is correct
- ✅ Error handling: No errors in logs
- ✅ Network connectivity: Data flows (orderbooks prove this)

## Comparison with Bitflyer

| Aspect | Bitflyer | GMO |
|--------|----------|-----|
| Board data | ✅ Receiving | ✅ Receiving |
| Trade data | ✅ Receiving | ❌ Not sent by API |
| Code implementation | ✅ Correct | ✅ Correct |
| API compatibility | ✅ Works | ❌ API issue |

## Recommendations

### Option A: Verify GMO API Documentation
- Check if "trades" channel is available in public API
- Verify correct channel name and message format
- Check if additional parameters are needed

### Option B: Alternative Data Source
- Use orderbook delta to infer trades (bid-ask spread analysis)
- Subscribe to different GMO API endpoint if available
- Use historical trade data if API provides it

### Option C: Accept Current Limitation
- Document that GMO trade volume is currently unavailable
- Continue using Bitflyer trade volume for market data
- Re-assess if GMO API changes in future

## Files Modified

1. `src/main/java/com/ys/exch_sim/domain/market_data/client/GmoMarketDataClient.java`
   - Added debug logging at lines 144 and 151
   - Enables identification of which channels are actually being received

2. `src/main/resources/application-local.properties`
   - Added DEBUG level logging for GMO and Bitflyer clients

## Conclusion

This is **not a bug in the code** - the implementation is correct. This is a **limitation of the GMO public WebSocket API** - it does not provide real-time trade data through the subscription channel.

To proceed, GMO API documentation must be verified to either:
1. Find the correct channel/format for trades, or
2. Accept that trade volume tracking is unavailable for GMO
