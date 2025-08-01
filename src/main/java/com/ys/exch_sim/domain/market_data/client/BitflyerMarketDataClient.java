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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** Bitflyer WebSocketクライアント実装 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "market-data.bitflyer.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class BitflyerMarketDataClient extends MarketDataWebSocketClient {

  private final WebSocketClient webSocketClient;
  private final AtomicLong jsonRpcId = new AtomicLong(1);

  // Bitflyer WebSocket チャンネル名
  private static final String CHANNEL_BOARD_SNAPSHOT_PREFIX = "lightning_board_snapshot_";
  private static final String CHANNEL_BOARD_DELTA_PREFIX = "lightning_board_";
  private static final String CHANNEL_EXECUTIONS_PREFIX = "lightning_executions_";

  // 対象シンボル
  private static final String SYMBOL_BTC_SPOT = "BTC_JPY";
  private static final String SYMBOL_BTC_FX = "FX_BTC_JPY";

  // 最新のマーケットボードデータを保持
  private final Map<String, ExternalMarketBoardData> latestBoards = new HashMap<>();

  public BitflyerMarketDataClient(
      DirectMarketDataService marketDataService,
      MarketDataClientConfig clientConfig,
      WebSocketClient webSocketClient) {
    super(
        marketDataService,
        clientConfig.getBitflyer().getWsUrl(),
        "Bitflyer",
        clientConfig.getBitflyer().getReconnectDelay(),
        clientConfig.getBitflyer().getMaxReconnectAttempts());

    this.webSocketClient = webSocketClient;
  }

  @PostConstruct
  public void autoConnect() {
    log.info("🚀 Bitflyer WebSocket client auto-connecting...");
    connect();
  }

  @PreDestroy
  public void cleanup() {
    disconnect();
  }

  @Override
  public void connect() {
    if (connection != null && !connection.isDisposed()) {
      log.debug("🔄 Bitflyer WebSocket already connected");
      return;
    }

    log.info("🔌 Connecting to Bitflyer WebSocket: {}", wsUrl);

    connection =
        webSocketClient
            .execute(
                URI.create(wsUrl),
                session -> {
                  // 購読メッセージの作成と送信
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
                                "🔄 Bitflyer WebSocket retry attempt: {}",
                                retrySignal.totalRetries())))
            .doOnError(this::handleConnectionError)
            .doOnCancel(this::onConnectionClosed)
            .subscribe(result -> onConnectionEstablished(), error -> handleConnectionError(error));
  }

  @Override
  protected void processMessage(String message) {
    try {
      JsonNode json = objectMapper.readTree(message);

      // JSON-RPC応答の処理
      if (json.has("id")) {
        log.debug("📡 Bitflyer JSON-RPC response: {}", json.get("id"));
        return;
      }

      // チャンネルメッセージの処理
      if (json.has("method") && json.has("params")) {
        String method = json.get("method").asText();
        JsonNode params = json.get("params");

        if (method.startsWith("lightning_board_snapshot_")) {
          handleBoardSnapshotMessage(method, params);
        } else if (method.startsWith("lightning_board_")) {
          handleBoardDeltaMessage(method, params);
        } else if (method.startsWith("lightning_executions_")) {
          handleExecutionsMessage(method, params);
        }
      }

    } catch (Exception e) {
      handleMessageError(message, e);
    }
  }

  private Flux<WebSocketMessage> createSubscriptionMessages(
      org.springframework.web.reactive.socket.WebSocketSession session) {
    return Flux.concat(
        // Board Snapshot購読
        createSubscriptionMessage(session, CHANNEL_BOARD_SNAPSHOT_PREFIX + SYMBOL_BTC_SPOT),
        createSubscriptionMessage(session, CHANNEL_BOARD_SNAPSHOT_PREFIX + SYMBOL_BTC_FX),
        // Board Delta購読
        createSubscriptionMessage(session, CHANNEL_BOARD_DELTA_PREFIX + SYMBOL_BTC_SPOT),
        createSubscriptionMessage(session, CHANNEL_BOARD_DELTA_PREFIX + SYMBOL_BTC_FX),
        // Executions購読
        createSubscriptionMessage(session, CHANNEL_EXECUTIONS_PREFIX + SYMBOL_BTC_SPOT),
        createSubscriptionMessage(session, CHANNEL_EXECUTIONS_PREFIX + SYMBOL_BTC_FX));
  }

  private Mono<WebSocketMessage> createSubscriptionMessage(
      org.springframework.web.reactive.socket.WebSocketSession session, String channel) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of(
              "jsonrpc",
              "2.0",
              "method",
              "subscribe",
              "params",
              Map.of("channel", channel),
              "id",
              jsonRpcId.getAndIncrement());

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      log.info("📡 Bitflyer subscribing to channel: {}", channel);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      log.error("❌ Error creating subscription message for channel: {}", channel, e);
      return Mono.empty();
    }
  }

  private void handleBoardSnapshotMessage(String method, JsonNode params) {
    try {
      String symbol = extractSymbolFromMethod(method, CHANNEL_BOARD_SNAPSHOT_PREFIX);
      if (symbol == null) return;

      JsonNode channel = params.get("channel");
      JsonNode message = params.get("message");

      if (message != null) {
        ExternalMarketBoardData boardData = convertBitflyerBoard(symbol, message);
        latestBoards.put(symbol, boardData);

        log.debug(
            "📊 Bitflyer Board Snapshot: {} - {} bids, {} asks",
            symbol,
            boardData.bids().size(),
            boardData.asks().size());

        marketDataService.processMarketBoardAsync(boardData);
      }
    } catch (Exception e) {
      log.error("❌ Error processing Bitflyer board snapshot", e);
    }
  }

  private void handleBoardDeltaMessage(String method, JsonNode params) {
    try {
      String symbol = extractSymbolFromMethod(method, CHANNEL_BOARD_DELTA_PREFIX);
      if (symbol == null) return;

      JsonNode message = params.get("message");
      if (message != null) {
        // Delta更新の場合、既存のボードデータを更新
        ExternalMarketBoardData currentBoard = latestBoards.get(symbol);
        if (currentBoard != null) {
          ExternalMarketBoardData updatedBoard = applyBoardDelta(currentBoard, symbol, message);
          latestBoards.put(symbol, updatedBoard);

          log.debug(
              "📊 Bitflyer Board Delta: {} - {} bids, {} asks",
              symbol,
              updatedBoard.bids().size(),
              updatedBoard.asks().size());

          marketDataService.processMarketBoardAsync(updatedBoard);
        }
      }
    } catch (Exception e) {
      log.error("❌ Error processing Bitflyer board delta", e);
    }
  }

  private void handleExecutionsMessage(String method, JsonNode params) {
    try {
      String symbol = extractSymbolFromMethod(method, CHANNEL_EXECUTIONS_PREFIX);
      if (symbol == null) return;

      JsonNode message = params.get("message");
      if (message != null && message.isArray()) {
        for (JsonNode execution : message) {
          ExternalTradeData tradeData = convertBitflyerTrade(symbol, execution);
          if (tradeData != null) {
            log.debug(
                "💰 Bitflyer Trade: {} - {} {} @ {}",
                symbol,
                tradeData.side(),
                tradeData.quantity(),
                tradeData.price());

            marketDataService.processTradeAsync(tradeData);
          }
        }
      }
    } catch (Exception e) {
      log.error("❌ Error processing Bitflyer executions", e);
    }
  }

  private String extractSymbolFromMethod(String method, String prefix) {
    if (method.startsWith(prefix)) {
      return method.substring(prefix.length());
    }
    return null;
  }

  private ExternalMarketBoardData convertBitflyerBoard(String symbol, JsonNode boardMessage) {
    List<ExternalMarketBoardData.PriceLevel> bids = new ArrayList<>();
    List<ExternalMarketBoardData.PriceLevel> asks = new ArrayList<>();

    // Bids処理
    JsonNode bidsArray = boardMessage.get("bids");
    if (bidsArray != null && bidsArray.isArray()) {
      for (JsonNode bid : bidsArray) {
        double price = bid.get("price").asDouble();
        double size = bid.get("size").asDouble();
        if (size > 0) {
          bids.add(new ExternalMarketBoardData.PriceLevel(price, size));
        }
      }
    }

    // Asks処理
    JsonNode asksArray = boardMessage.get("asks");
    if (asksArray != null && asksArray.isArray()) {
      for (JsonNode ask : asksArray) {
        double price = ask.get("price").asDouble();
        double size = ask.get("size").asDouble();
        if (size > 0) {
          asks.add(new ExternalMarketBoardData.PriceLevel(price, size));
        }
      }
    }

    return new ExternalMarketBoardData("BITFLYER", symbol, bids, asks, Instant.now());
  }

  private ExternalMarketBoardData applyBoardDelta(
      ExternalMarketBoardData currentBoard, String symbol, JsonNode deltaMessage) {
    // 簡略化：Delta更新の代わりに新しいスナップショットとして処理
    return convertBitflyerBoard(symbol, deltaMessage);
  }

  private ExternalTradeData convertBitflyerTrade(String symbol, JsonNode execution) {
    try {
      double price = execution.get("price").asDouble();
      double size = execution.get("size").asDouble();
      String side = execution.get("side").asText().toUpperCase();

      return new ExternalTradeData("BITFLYER", symbol, price, size, side, Instant.now());
    } catch (Exception e) {
      log.error("❌ Error converting Bitflyer trade: {}", execution, e);
      return null;
    }
  }
}
