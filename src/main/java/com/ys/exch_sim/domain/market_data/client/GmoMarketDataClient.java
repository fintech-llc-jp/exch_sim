package com.ys.exch_sim.domain.market_data.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.service.DirectMarketDataService;
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

  // GMO対象シンボル
  private static final String SYMBOL_BTC_JPY = "BTC_JPY";
  private static final String SYMBOL_BTC = "BTC";

  public GmoMarketDataClient(
      DirectMarketDataService marketDataService,
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
            .subscribe(result -> onConnectionEstablished(), error -> handleConnectionError(error));
  }

  @Override
  protected void processMessage(String message) {
    try {
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

        if (channel.equals("orderbooks")) {
          handleOrderbookMessage(json);
        } else if (channel.equals("trades")) {
          handleTradeMessage(json);
        }
      }

    } catch (Exception e) {
      handleMessageError(message, e);
    }
  }

  private Flux<WebSocketMessage> createSubscriptionMessages(
      org.springframework.web.reactive.socket.WebSocketSession session) {
    return Flux.concat(
        // Orderbook購読
        createOrderbookSubscription(session, SYMBOL_BTC_JPY),
        createOrderbookSubscription(session, SYMBOL_BTC),
        // Trades購読
        createTradesSubscription(session, SYMBOL_BTC_JPY),
        createTradesSubscription(session, SYMBOL_BTC));
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
      String symbol = message.get("symbol").asText();
      JsonNode bidsArray = message.get("bids");
      JsonNode asksArray = message.get("asks");

      if (bidsArray != null && asksArray != null) {
        ExternalMarketBoardData boardData = convertGmoBoard(symbol, bidsArray, asksArray);

        log.debug(
            "📊 GMO Orderbook: {} - {} bids, {} asks",
            symbol,
            boardData.bids().size(),
            boardData.asks().size());

        marketDataService.processMarketBoardAsync(boardData);
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

    // Bids処理
    if (bidsArray.isArray()) {
      for (JsonNode bid : bidsArray) {
        double price = Double.parseDouble(bid.get("price").asText());
        double size = Double.parseDouble(bid.get("size").asText());
        if (size > 0) {
          bids.add(new ExternalMarketBoardData.PriceLevel(price, size));
        }
      }
    }

    // Asks処理
    if (asksArray.isArray()) {
      for (JsonNode ask : asksArray) {
        double price = Double.parseDouble(ask.get("price").asText());
        double size = Double.parseDouble(ask.get("size").asText());
        if (size > 0) {
          asks.add(new ExternalMarketBoardData.PriceLevel(price, size));
        }
      }
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
}
