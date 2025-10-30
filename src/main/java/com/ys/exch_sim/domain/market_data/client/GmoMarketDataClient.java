package com.ys.exch_sim.domain.market_data.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.service.MarketDataService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** GMO WebSocketクライアント実装 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "market-data.gmo.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class GmoMarketDataClient extends MarketDataWebSocketClient {

  private final WebSocketClient webSocketClient;
  
  // データ品質監視用
  private final java.util.concurrent.atomic.AtomicLong askMissingCount = new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong bidMissingCount = new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong totalOrderbookCount = new java.util.concurrent.atomic.AtomicLong(0);
  private volatile Instant lastAskMissingTime;
  private volatile Instant lastBidMissingTime;

  // GMO対象シンボル
  private static final String SYMBOL_BTC_JPY = "BTC_JPY";
  private static final String SYMBOL_BTC = "BTC";

  public GmoMarketDataClient(
      MarketDataService marketDataService,
      MarketDataClientConfig clientConfig,
      WebSocketClient webSocketClient) {
    super(
        marketDataService,
        clientConfig.getGmo().getWsUrl(),
        "GMO",
        clientConfig.getGmo().getReconnectDelay(),
        clientConfig.getGmo().getMaxReconnectAttempts());

    this.webSocketClient = webSocketClient;
  }

  @PostConstruct
  public void autoConnect() {
    log.info("🚀 GMO WebSocket client auto-connecting...");
    logSymbolMappingInfo();
    connect();
  }

  @PreDestroy
  public void cleanup() {
    disconnect();
  }

  @Override
  public void connect() {
    if (connection != null && !connection.isDisposed()) {
      log.debug("🔄 GMO WebSocket already connected");
      return;
    }

    log.info("🔌 Connecting to GMO WebSocket: {}", wsUrl);

    connection =
        webSocketClient
            .execute(
                URI.create(wsUrl),
                session -> {
                  // 接続成功時の処理
                  log.info("✅ GMO WebSocket session established");
                  onConnectionEstablished();
                  
                  // 購読メッセージの送信
                  Flux<WebSocketMessage> subscriptionMessages = createSubscriptionMessages(session);

                  // メッセージ受信処理
                  Flux<String> messageFlux =
                      session
                          .receive()
                          .map(WebSocketMessage::getPayloadAsText)
                          .doOnNext(this::processMessage)
                          .doOnError(this::handleConnectionError);

                  return session.send(subscriptionMessages).thenMany(messageFlux).then();
                })
            .retryWhen(
                Retry.backoff(maxReconnectAttempts, Duration.ofMillis(reconnectDelay))
                    .doBeforeRetry(
                        retrySignal ->
                            log.warn(
                                "🔄 GMO WebSocket retry attempt: {}", retrySignal.totalRetries())))
            .doOnError(this::handleConnectionError)
            .doOnCancel(this::onConnectionClosed)
            .subscribe(
                result -> {
                    log.info("🔚 GMO WebSocket stream completed");
                    onConnectionClosed();
                }, 
                error -> {
                    log.error("❌ GMO WebSocket subscription error: {}", error.getMessage(), error);
                    handleConnectionError(error);
                });
  }

  @Override
  protected void processMessage(String message) {
    try {
      // 基底クラスのメッセージカウンターを更新
      incrementMessageCount();

      JsonNode json = objectMapper.readTree(message);

      // 通常のレスポンスメッセージ
      if (json.has("command")) {
        String command = json.get("command").asText();
        log.debug("📡 GMO command response: {}", command);
        return;
      }

      // チャンネルデータの処理
      if (json.has("channel")) {
        String channel = json.get("channel").asText();
        log.debug("📡 GMO Channel received: {}", channel);

        if (channel.equals("orderbooks")) {
          handleOrderbookMessage(json);
        } else if (channel.equals("trades")) {
          handleTradeMessage(json);
        } else if (channel.equals("ticker")) {
          log.debug("📡 GMO Ticker received (not yet implemented): {}", json.toPrettyString());
        } else {
          log.debug("📡 GMO Unknown channel (not orderbooks/trades/ticker): {}", channel);
        }
      } else {
        // JSONに"channel"がない場合、メッセージタイプを調査
        log.debug("📡 GMO Message without channel field: {}",
            message.length() > 200 ? message.substring(0, 200) + "..." : message);
      }

    } catch (Exception e) {
      handleMessageError(message, e);
    }
  }

  private Flux<WebSocketMessage> createSubscriptionMessages(
      org.springframework.web.reactive.socket.WebSocketSession session) {
    return createOrderbookSubscription(session, SYMBOL_BTC_JPY)
        .concatWith(Mono.delay(Duration.ofSeconds(2)).then(createOrderbookSubscription(session, SYMBOL_BTC)))
        .concatWith(Mono.delay(Duration.ofSeconds(2)).then(createTradesSubscription(session, SYMBOL_BTC)));
    // NOTE: GMO API trades channel only supports BTC symbol, not BTC_JPY
    // Removed: createTradesSubscription(session, SYMBOL_BTC_JPY)
  }

  private Mono<WebSocketMessage> createOrderbookSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session, String symbol) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of(
              "command", "subscribe",
              "channel", "orderbooks",
              "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      log.info("📡 GMO subscribing to orderbooks: {}", symbol);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      log.error("❌ Error creating GMO orderbook subscription for: {}", symbol, e);
      return Mono.empty();
    }
  }

  private Mono<WebSocketMessage> createTradesSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session, String symbol) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of(
              "command", "subscribe",
              "channel", "trades",
              "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      log.info("📡 GMO subscribing to trades: {}", symbol);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      log.error("❌ Error creating GMO trades subscription for: {}", symbol, e);
      return Mono.empty();
    }
  }

  private void handleOrderbookMessage(JsonNode message) {
    try {
      totalOrderbookCount.incrementAndGet();
      String symbol = message.get("symbol").asText();
      JsonNode bidsArray = message.get("bids");
      JsonNode asksArray = message.get("asks");
      
      // 詳細ログ：受信データの状態を記録
      boolean hasBids = bidsArray != null && bidsArray.isArray() && bidsArray.size() > 0;
      boolean hasAsks = asksArray != null && asksArray.isArray() && asksArray.size() > 0;
      
      log.debug("📡 GMO Raw orderbook for {}: bids={} ({}), asks={} ({}), message: {}",
          symbol, 
          hasBids ? bidsArray.size() : "null/empty",
          hasBids,
          hasAsks ? asksArray.size() : "null/empty", 
          hasAsks,
          message.toPrettyString().length() > 500 ? message.toPrettyString().substring(0, 500) + "..." : message.toPrettyString());
      
      // データ品質監視
      if (!hasBids) {
        bidMissingCount.incrementAndGet();
        lastBidMissingTime = Instant.now();
        log.warn("⚠️ GMO {} BID data missing or empty (total missing: {})", symbol, bidMissingCount.get());
      }
      
      if (!hasAsks) {
        askMissingCount.incrementAndGet();
        lastAskMissingTime = Instant.now();
        log.warn("⚠️ GMO {} ASK data missing or empty (total missing: {})", symbol, askMissingCount.get());
      }

      if (bidsArray != null && asksArray != null) {
        ExternalMarketBoardData boardData = convertGmoBoard(symbol, bidsArray, asksArray);
        
        // データ品質警告
        if (boardData.bids().isEmpty() && boardData.asks().isEmpty()) {
          log.warn("🚨 GMO {} Both BID and ASK are empty after processing!", symbol);
        } else if (boardData.asks().isEmpty()) {
          log.warn("🚨 GMO {} ASK is empty after processing (BID count: {})", symbol, boardData.bids().size());
        } else if (boardData.bids().isEmpty()) {
          log.warn("🚨 GMO {} BID is empty after processing (ASK count: {})", symbol, boardData.asks().size());
        }

        log.info(
            "📊 GMO Orderbook: {} - {} bids, {} asks (Quality: {:.1f}% success)",
            symbol,
            boardData.bids().size(),
            boardData.asks().size(),
            getDataQualityRate());

        marketDataService.processMarketBoardAsync(boardData);
      } else {
        log.warn("⚠️ GMO {} Skipping orderbook processing - missing bids or asks arrays", symbol);
      }
    } catch (Exception e) {
      log.error("❌ Error processing GMO orderbook message", e);
    }
  }

  private void handleTradeMessage(JsonNode message) {
    try {
      String symbol = message.get("symbol").asText();
      JsonNode tradesArray = message.get("trades");

      if (tradesArray != null && tradesArray.isArray()) {
        for (JsonNode trade : tradesArray) {
          ExternalTradeData tradeData = convertGmoTrade(symbol, trade);
          if (tradeData != null) {
            log.debug(
                "💰 GMO Trade: {} - {} {} @ {}",
                symbol,
                tradeData.side(),
                tradeData.quantity(),
                tradeData.price());

            marketDataService.processTradeAsync(tradeData);
          }
        }
      }
    } catch (Exception e) {
      log.error("❌ Error processing GMO trade message", e);
    }
  }

  private ExternalMarketBoardData convertGmoBoard(
      String symbol, JsonNode bidsArray, JsonNode asksArray) {
    List<ExternalMarketBoardData.PriceLevel> bids = new ArrayList<>();
    List<ExternalMarketBoardData.PriceLevel> asks = new ArrayList<>();
    
    int bidCount = 0, askCount = 0;
    int bidFiltered = 0, askFiltered = 0;

    // Bids処理
    if (bidsArray.isArray()) {
      bidCount = bidsArray.size();
      for (JsonNode bid : bidsArray) {
        double price = Double.parseDouble(bid.get("price").asText());
        double size = Double.parseDouble(bid.get("size").asText());
        if (size > 0) {
          bids.add(new ExternalMarketBoardData.PriceLevel(price, size));
        } else {
          bidFiltered++;
        }
      }
    }

    // Asks処理
    if (asksArray.isArray()) {
      askCount = asksArray.size();
      for (JsonNode ask : asksArray) {
        double price = Double.parseDouble(ask.get("price").asText());
        double size = Double.parseDouble(ask.get("size").asText());
        if (size > 0) {
          asks.add(new ExternalMarketBoardData.PriceLevel(price, size));
        } else {
          askFiltered++;
        }
      }
    }
    
    // フィルタリング結果のログ
    if (bidFiltered > 0 || askFiltered > 0) {
      log.debug("🔍 GMO {} Filtering results: BID {}/{} kept, ASK {}/{} kept (filtered out zero-size entries)",
          symbol, bids.size(), bidCount, asks.size(), askCount);
    }
    
    // 価格範囲の情報をログ
    if (!bids.isEmpty() && !asks.isEmpty()) {
      double bestBid = bids.get(0).price();
      double bestAsk = asks.get(0).price();
      double spread = bestAsk - bestBid;
      log.debug("💰 GMO {} Price info: Best BID={}, Best ASK={}, Spread={}", 
          symbol, bestBid, bestAsk, spread);
    }

    return new ExternalMarketBoardData("GMO", symbol, bids, asks, Instant.now());
  }

  private ExternalTradeData convertGmoTrade(String symbol, JsonNode trade) {
    try {
      double price = Double.parseDouble(trade.get("price").asText());
      double size = Double.parseDouble(trade.get("size").asText());
      String side = trade.get("side").asText().toUpperCase();

      // GMOのタイムスタンプ処理
      Instant timestamp = Instant.now();
      if (trade.has("timestamp")) {
        try {
          String timestampStr = trade.get("timestamp").asText();
          LocalDateTime dateTime =
              LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
          timestamp = dateTime.toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
          log.warn("⚠️ Could not parse GMO timestamp: {}", trade.get("timestamp").asText());
        }
      }

      return new ExternalTradeData("GMO", symbol, price, size, side, timestamp);
    } catch (Exception e) {
      log.error("❌ Error converting GMO trade: {}", trade, e);
      return null;
    }
  }
  
  /**
   * データ品質率を取得
   */
  private double getDataQualityRate() {
    long total = totalOrderbookCount.get();
    if (total == 0) return 100.0;
    
    long totalMissing = askMissingCount.get() + bidMissingCount.get();
    return ((total * 2 - totalMissing) * 100.0) / (total * 2);
  }
  
  /**
   * シンボルマッピングの検証ログ
   */
  private void logSymbolMappingInfo() {
    log.info("🔍 GMO Symbol Mapping Verification:");
    log.info("  - Orderbooks channel: BTC_JPY and BTC both supported");
    log.info("  - Trades channel: ONLY BTC is supported (not BTC_JPY)");
    log.info("  - BTC_JPY (受信: Orderbook) → G_FX_BTCJPY (内部)");
    log.info("  - BTC (受信: Orderbook + Trades) → G_BTCJPY (内部)");
    log.info("  - Symbol mapping note: GMO API has asymmetric channel support between orderbooks and trades");
  }
  
  @Override
  public String getClientInfo() {
    String baseInfo = super.getClientInfo();
    return baseInfo + String.format(" [Data Quality: %.1f%%, ASK Missing: %d, BID Missing: %d, Total Orderbooks: %d, Last ASK Missing: %s, Last BID Missing: %s]",
        getDataQualityRate(),
        askMissingCount.get(), 
        bidMissingCount.get(),
        totalOrderbookCount.get(),
        lastAskMissingTime != null ? lastAskMissingTime.toString() : "Never",
        lastBidMissingTime != null ? lastBidMissingTime.toString() : "Never");
  }
}
