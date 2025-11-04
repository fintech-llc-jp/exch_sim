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

                  // 購読メッセージの送信（別スレッドで2秒間隔）
                  new Thread(
                          () -> {
                            try {
                              sendSubscription(session, SYMBOL_BTC, "trades");
                              Thread.sleep(2000);
                              sendSubscription(session, SYMBOL_BTC_JPY, "orderbooks");
                              Thread.sleep(2000);
                              sendSubscription(session, SYMBOL_BTC, "orderbooks");
                              Thread.sleep(2000);
                              sendSubscription(session, SYMBOL_BTC_JPY, "trades");
                            } catch (InterruptedException e) {
                              log.error("❌ Subscription sequence interrupted", e);
                              Thread.currentThread().interrupt();
                            }
                          })
                      .start();

                  // メッセージ受信処理
                  Flux<String> messageFlux =
                      session
                          .receive()
                          .map(WebSocketMessage::getPayloadAsText)
                          .doOnNext(this::processMessage)
                          .doOnError(this::handleConnectionError);

                  return messageFlux.then();
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
      incrementMessageCount();
      JsonNode json = objectMapper.readTree(message);

      // コマンドレスポンスはスキップ
      if (json.has("command")) {
        return;
      }

      // チャンネルデータの処理
      if (json.has("channel")) {
        String channel = json.get("channel").asText();

        if ("orderbooks".equals(channel)) {
          handleOrderbookMessage(json);
        } else if ("trades".equals(channel)) {
          log.info("GMO trades message:" + message);
          handleTradeMessage(json);
        }
      }
    } catch (Exception e) {
      handleMessageError(message, e);
    }
  }

  private void sendSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session,
      String symbol,
      String channel) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of(
              "command", "subscribe",
              "channel", channel,
              "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      log.info("📡 GMO subscribing to {}: {}", channel, symbol);

      session.send(Mono.just(session.textMessage(requestJson))).block();
    } catch (Exception e) {
      log.error("❌ Error sending GMO {} subscription for: {}", channel, symbol, e);
    }
  }

  private void handleOrderbookMessage(JsonNode message) {
    try {
      String symbol = message.get("symbol").asText();
      JsonNode bidsArray = message.get("bids");
      JsonNode asksArray = message.get("asks");

      if (bidsArray != null && asksArray != null) {
        ExternalMarketBoardData boardData = convertGmoBoard(symbol, bidsArray, asksArray);
        marketDataService.processMarketBoardAsync(boardData);
      }
    } catch (Exception e) {
      log.error("Error processing GMO orderbook", e);
    }
  }

  private void handleTradeMessage(JsonNode message) {
    try {
      String symbol = message.get("symbol").asText();

      // GMO WebSocket API sends individual trade objects, not arrays
      // Message format: {"channel":"trades","price":"...","side":"...","size":"...","symbol":"..."}
      if (message.has("price") && message.has("side") && message.has("size")) {
        ExternalTradeData tradeData = convertGmoTrade(symbol, message);
        if (tradeData != null) {
          log.debug("📡 GMO Processing trade for symbol: {} - side: {}, price: {}, size: {}",
              symbol, message.get("side").asText(), message.get("price").asText(),
              message.get("size").asText());
          marketDataService.processTradeAsync(tradeData);
        }
      } else {
        log.warn("⚠️ GMO trade message missing required fields: {}", message);
      }
    } catch (Exception e) {
      log.error("Error processing GMO trade message", e);
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

      Instant timestamp = Instant.now();
      if (trade.has("timestamp")) {
        try {
          String timestampStr = trade.get("timestamp").asText();
          LocalDateTime dateTime =
              LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
          timestamp = dateTime.toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
          // タイムスタンプのパース失敗時は現在時刻を使用
        }
      }

      return new ExternalTradeData("GMO", symbol, price, size, side, timestamp);
    } catch (Exception e) {
      log.error("Error converting GMO trade", e);
      return null;
    }
  }
}
