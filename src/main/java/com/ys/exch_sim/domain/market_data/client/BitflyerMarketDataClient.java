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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    matchIfMissing = true)
public class BitflyerMarketDataClient extends MarketDataWebSocketClient {

  private final WebSocketClient webSocketClient;
  private final AtomicLong jsonRpcId = new AtomicLong(1);
  
  // Connection quality metrics
  private final AtomicLong connectionAttempts = new AtomicLong(0);
  private final AtomicLong successfulConnections = new AtomicLong(0);
  private final AtomicLong retryEvents = new AtomicLong(0);
  private volatile Instant lastConnectionAttempt;
  private volatile Instant lastSuccessfulConnection;

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
    log.info("🚀 Bitflyer WebSocket client initializing...");
    log.info("🔧 Configuration - enabled: {}, wsUrl: {}, maxReconnectAttempts: {}", 
        true, wsUrl, maxReconnectAttempts);
    log.info("🔧 WebSocket client: {}", webSocketClient != null ? webSocketClient.getClass().getSimpleName() : "NULL");
    
    try {
      log.info("🔌 Starting Bitflyer WebSocket connection...");
      connect();
      log.info("✅ Bitflyer WebSocket client initialization completed");
    } catch (Exception e) {
      log.error("❌ Failed to initialize Bitflyer WebSocket client", e);
      throw e;
    }
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

    // Update connection metrics
    connectionAttempts.incrementAndGet();
    lastConnectionAttempt = Instant.now();
    
    log.info("🔌 Connecting to Bitflyer WebSocket: {} (attempt: {}, success rate: {:.1f}%)", 
        wsUrl, connectionAttempts.get(), getSuccessRate());
    log.debug("🔧 WebSocket client details: {}", webSocketClient.getClass().getSimpleName());

    connection =
        webSocketClient
            .execute(
                URI.create(wsUrl),
                session -> {
                  // 接続成功時の処理
                  successfulConnections.incrementAndGet();
                  lastSuccessfulConnection = Instant.now();
                  log.info("✅ Bitflyer WebSocket session established (success: {}/{}, rate: {:.1f}%)", 
                      successfulConnections.get(), connectionAttempts.get(), getSuccessRate());
                  onConnectionEstablished();
                  
                  // 購読メッセージの作成と送信
                  Flux<WebSocketMessage> subscriptionMessages = createSubscriptionMessages(session);

                  // メッセージ受信処理
                  Flux<String> messageFlux =
                      session
                          .receive()
                          .map(WebSocketMessage::getPayloadAsText)
                          .doOnNext(this::processMessage)
                          .doOnError(error -> {
                              // Log processing errors but don't trigger reconnection
                              log.warn("⚠️ Bitflyer message processing error: {}", error.getMessage());
                              incrementMessageCount(); // Count as activity to prevent timeout
                          });

                  return session.send(subscriptionMessages).thenMany(messageFlux).then();
                })
            .doOnError(error -> {
                log.error("❌ Bitflyer WebSocket connection error: {}", error.getMessage());
                // Let retryWhen handle reconnection, just log here
            })
            .doOnCancel(() -> {
                log.warn("🛑 Bitflyer WebSocket connection cancelled");
                onConnectionClosed();
            })
            .retryWhen(
                Retry.backoff(maxReconnectAttempts, Duration.ofMillis(reconnectDelay))
                    .maxBackoff(Duration.ofSeconds(30))
                    .jitter(0.1)
                    .filter(throwable -> {
                        // Only filter out truly non-recoverable errors
                        String message = throwable.getMessage();
                        boolean isNonRetryable = message != null && (
                            message.contains("401") ||  // Unauthorized
                            message.contains("403") ||  // Forbidden
                            message.contains("invalid credentials") ||
                            message.contains("authentication failed")
                        );
                        
                        if (isNonRetryable) {
                            log.error("🚫 Bitflyer non-retryable authentication error: {}", message);
                            return false;
                        } else {
                            // Log all retryable errors for analysis
                            log.info("🔄 Bitflyer connection error (will retry): {}", message);
                            return true;
                        }
                    })
                    .doBeforeRetry(
                        retrySignal -> {
                            retryEvents.incrementAndGet();
                            int attempt = (int) retrySignal.totalRetries() + 1;
                            Throwable error = retrySignal.failure();
                            long delayMs = retrySignal.totalRetriesInARow() == 0 ? reconnectDelay : 
                                Math.min(reconnectDelay * (long) Math.pow(2, retrySignal.totalRetriesInARow()), 30000);
                            
                            // Detailed error categorization for analysis
                            String errorCategory = categorizeError(error);
                            log.warn(
                                "🔄 Bitflyer WebSocket retry attempt: {}/{} in {}ms - Category: {} - Error: {}",
                                attempt, maxReconnectAttempts, delayMs, errorCategory, error.getMessage());
                        })
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                        log.error("💥 Bitflyer WebSocket max retry attempts ({}) exceeded. Last error: {}", 
                            maxReconnectAttempts, retrySignal.failure().getMessage());
                        return new RuntimeException("Max retry attempts exceeded after " + maxReconnectAttempts + " attempts", 
                            retrySignal.failure());
                    }))
            .subscribe(
                result -> {
                    log.info("🔚 Bitflyer WebSocket stream completed");
                    onConnectionClosed();
                }, 
                error -> {
                    log.error("❌ Bitflyer WebSocket final subscription error (after all retries): {}", error.getMessage());
                    // Mark as disconnected since retries are exhausted
                    isConnected.set(false);
                    shouldReconnect.set(false);
                    onConnectionClosed();
                });
  }

  @Override
  protected void processMessage(String message) {
    try {
      // 基底クラスのメッセージカウンターを更新
      incrementMessageCount();
      
      JsonNode json = objectMapper.readTree(message);

      // JSON-RPC応答の処理
      if (json.has("id")) {
        log.debug("📡 Bitflyer JSON-RPC response: {}", json.get("id"));
        // JSON-RPC応答も活動として記録
        updateActivityTime();
        return;
      }

      // チャンネルメッセージの処理
      if (json.has("method") && json.has("params")) {
        String method = json.get("method").asText();
        JsonNode params = json.get("params");

        log.debug("📡 Bitflyer received method: {}", method);

        if (method.equals("channelMessage")) {
          handleChannelMessage(params);
        } else if (method.startsWith("lightning_board_snapshot_")) {
          handleBoardSnapshotMessage(method, params);
        } else if (method.startsWith("lightning_board_")) {
          handleBoardDeltaMessage(method, params);
        } else if (method.startsWith("lightning_executions_")) {
          handleExecutionsMessage(method, params);
        } else {
          log.debug("📡 Bitflyer unknown method: {}", method);
        }
      } else {
        log.debug("📡 Bitflyer message without method/params: {}", message.length() > 200 ? message.substring(0, 200) + "..." : message);
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

        log.info(
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

          log.info(
              "📊 Bitflyer Board Delta: {} - {} bids, {} asks",
              symbol,
              updatedBoard.bids().size(),
              updatedBoard.asks().size());

          marketDataService.processMarketBoardAsync(updatedBoard);
        } else {
          log.warn("⚠️ Bitflyer Board Delta skipped - no existing board for symbol: {}. " +
              "Snapshot may not have been received yet. Available boards: {}", 
              symbol, latestBoards.keySet());
          
          // 初回のスナップショットが来ていない場合は、Deltaメッセージを基にボードを作成
          if (!latestBoards.containsKey(symbol)) {
            log.info("🔄 Creating initial board from Delta for symbol: {}", symbol);
            ExternalMarketBoardData initialBoard = convertBitflyerBoard(symbol, message);
            latestBoards.put(symbol, initialBoard);
            marketDataService.processMarketBoardAsync(initialBoard);
          }
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
            log.info(
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

  private void handleChannelMessage(JsonNode params) {
    try {
      JsonNode channel = params.get("channel");
      JsonNode message = params.get("message");
      
      if (channel == null || message == null) {
        log.debug("📡 Bitflyer channelMessage missing channel or message");
        return;
      }
      
      String channelName = channel.asText();
      log.debug("📡 Bitflyer channel: {}", channelName);
      
      // チャンネル名に応じて適切な処理に振り分け
      if (channelName.startsWith("lightning_board_snapshot_")) {
        handleBoardSnapshotMessage(channelName, params);
      } else if (channelName.startsWith("lightning_board_")) {
        handleBoardDeltaMessage(channelName, params);
      } else if (channelName.startsWith("lightning_executions_")) {
        handleExecutionsMessage(channelName, params);
      } else {
        log.debug("📡 Bitflyer unknown channel: {}", channelName);
      }
      
    } catch (Exception e) {
      log.error("❌ Error processing Bitflyer channelMessage", e);
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
    
    // TreeMapを使用して常にソート済み状態を維持（パフォーマンス最適化）
    // Bids: 降順（高い価格が最初）
    TreeMap<Double, Double> bidMap = new TreeMap<>(Collections.reverseOrder());
    // Asks: 昇順（低い価格が最初）  
    TreeMap<Double, Double> askMap = new TreeMap<>();
    
    // 現在のボードデータをTreeMapに変換（既にソート済みなので効率的）
    for (ExternalMarketBoardData.PriceLevel bid : currentBoard.bids()) {
      bidMap.put(bid.price(), bid.quantity());
    }
    for (ExternalMarketBoardData.PriceLevel ask : currentBoard.asks()) {
      askMap.put(ask.price(), ask.quantity());
    }
    
    // Deltaのbidsを適用（O(d log n)の計算量）
    JsonNode deltaBids = deltaMessage.get("bids");
    if (deltaBids != null && deltaBids.isArray()) {
      for (JsonNode bid : deltaBids) {
        double price = bid.get("price").asDouble();
        double size = bid.get("size").asDouble();
        
        if (size == 0) {
          // サイズが0の場合は削除
          bidMap.remove(price);
        } else {
          // サイズが0以外の場合は更新または追加
          bidMap.put(price, size);
        }
      }
    }
    
    // Deltaのasksを適用（O(d log n)の計算量）
    JsonNode deltaAsks = deltaMessage.get("asks");
    if (deltaAsks != null && deltaAsks.isArray()) {
      for (JsonNode ask : deltaAsks) {
        double price = ask.get("price").asDouble();
        double size = ask.get("size").asDouble();
        
        if (size == 0) {
          // サイズが0の場合は削除
          askMap.remove(price);
        } else {
          // サイズが0以外の場合は更新または追加
          askMap.put(price, size);
        }
      }
    }
    
    // TreeMapから直接リストに変換（既にソート済みなのでO(n)）
    List<ExternalMarketBoardData.PriceLevel> updatedBids = bidMap.entrySet().stream()
        .map(entry -> new ExternalMarketBoardData.PriceLevel(entry.getKey(), entry.getValue()))
        .collect(java.util.stream.Collectors.toList());
        
    List<ExternalMarketBoardData.PriceLevel> updatedAsks = askMap.entrySet().stream()
        .map(entry -> new ExternalMarketBoardData.PriceLevel(entry.getKey(), entry.getValue()))
        .collect(java.util.stream.Collectors.toList());
    
    return new ExternalMarketBoardData(currentBoard.exchange(), symbol, updatedBids, updatedAsks, Instant.now());
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
  
  private double getSuccessRate() {
    long attempts = connectionAttempts.get();
    if (attempts == 0) return 0.0;
    return (successfulConnections.get() * 100.0) / attempts;
  }
  
  private String categorizeError(Throwable error) {
    if (error == null || error.getMessage() == null) {
      return "UNKNOWN";
    }
    
    String message = error.getMessage().toLowerCase();
    
    if (message.contains("handshake")) {
      return "HANDSHAKE";
    } else if (message.contains("timeout") || message.contains("timed out")) {
      return "TIMEOUT";
    } else if (message.contains("connection reset") || message.contains("reset by peer")) {
      return "CONNECTION_RESET";
    } else if (message.contains("prematurely closed")) {
      return "PREMATURE_CLOSE";
    } else if (message.contains("network") || message.contains("host")) {
      return "NETWORK";
    } else if (message.contains("ssl") || message.contains("tls")) {
      return "SSL_TLS";
    } else if (message.contains("401") || message.contains("403") || message.contains("unauthorized")) {
      return "AUTH";
    } else if (message.contains("websocket")) {
      return "WEBSOCKET";
    } else {
      return "OTHER";
    }
  }
  
  @Override
  public String getClientInfo() {
    String baseInfo = super.getClientInfo();
    return baseInfo + String.format(" [Connections: %d/%d (%.1f%%), Retries: %d, Last Attempt: %s, Last Success: %s]",
        successfulConnections.get(), connectionAttempts.get(), getSuccessRate(), retryEvents.get(),
        lastConnectionAttempt != null ? lastConnectionAttempt.toString() : "Never",
        lastSuccessfulConnection != null ? lastSuccessfulConnection.toString() : "Never");
  }
}
