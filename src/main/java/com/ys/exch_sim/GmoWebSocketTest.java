package com.ys.exch_sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GMO Coin WebSocket API テストプログラム
 * Tradeチャンネルを直接リッスンして、実際にデータが来ているか確認します
 */
public class GmoWebSocketTest {

  private static final String GMO_WS_URL = "wss://api.coin.z.com/ws/public/v1";
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static int messageCount = 0;
  private static int tradeCount = 0;
  private static int orderbookCount = 0;
  private static int tickerCount = 0;
  private static int unknownCount = 0;

  public static void main(String[] args) throws Exception {
    System.out.println("🚀 Starting GMO WebSocket Trade Channel Test...");
    System.out.println("URL: " + GMO_WS_URL);
    System.out.println();

    ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
    CountDownLatch latch = new CountDownLatch(1);

    webSocketClient
        .execute(
            URI.create(GMO_WS_URL),
            session -> {
              System.out.println("✅ WebSocket session established");
              System.out.println();

              // Subscribe to trades channel
              Flux<WebSocketMessage> subscriptions =
                  createTradesSubscription(session, "BTC")
                      .concatWith(
                          Mono.delay(Duration.ofSeconds(2))
                              .then(createOrderbooksSubscription(session, "BTC")))
                      .concatWith(
                          Mono.delay(Duration.ofSeconds(2))
                              .then(createTickerSubscription(session, "BTC")));

              // Receive messages
              Flux<String> messageFlux =
                  session
                      .receive()
                      .map(WebSocketMessage::getPayloadAsText)
                      .doOnNext(GmoWebSocketTest::processMessage)
                      .doOnError(
                          error -> {
                            System.err.println("❌ WebSocket error: " + error.getMessage());
                            latch.countDown();
                          });

              return session.send(subscriptions).thenMany(messageFlux).then();
            })
        .doOnError(
            error -> {
              System.err.println("❌ Connection error: " + error.getMessage());
              error.printStackTrace();
              latch.countDown();
            })
        .subscribe(
            result -> {
              System.out.println("🔚 WebSocket stream completed");
              latch.countDown();
            },
            error -> {
              System.err.println("❌ Subscription error: " + error.getMessage());
              error.printStackTrace();
              latch.countDown();
            });

    // Wait for 60 seconds or until error
    boolean completed = latch.await(60, TimeUnit.SECONDS);

    System.out.println();
    System.out.println("========== TEST RESULTS ==========");
    System.out.println("Total messages: " + messageCount);
    System.out.println("Trade messages: " + tradeCount);
    System.out.println("Orderbook messages: " + orderbookCount);
    System.out.println("Ticker messages: " + tickerCount);
    System.out.println("Unknown messages: " + unknownCount);
    System.out.println("================================");
    System.out.println();

    if (tradeCount > 0) {
      System.out.println("✅ SUCCESS: Trade channel is working!");
    } else {
      System.out.println("❌ FAILURE: Trade channel is NOT sending data from GMO API");
      System.out.println("   This confirms the issue - GMO public API does not send trade data");
    }

    System.exit(0);
  }

  private static Mono<WebSocketMessage> createTradesSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session, String symbol) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of("command", "subscribe", "channel", "trades", "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      System.out.println("📡 Subscribing to trades channel: " + symbol);
      System.out.println("   Request: " + requestJson);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      System.err.println("❌ Error creating trades subscription: " + e.getMessage());
      return Mono.empty();
    }
  }

  private static Mono<WebSocketMessage> createOrderbooksSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session, String symbol) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of("command", "subscribe", "channel", "orderbooks", "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      System.out.println("📡 Subscribing to orderbooks channel: " + symbol);
      System.out.println("   Request: " + requestJson);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      System.err.println("❌ Error creating orderbooks subscription: " + e.getMessage());
      return Mono.empty();
    }
  }

  private static Mono<WebSocketMessage> createTickerSubscription(
      org.springframework.web.reactive.socket.WebSocketSession session, String symbol) {
    try {
      Map<String, Object> subscribeRequest =
          Map.of("command", "subscribe", "channel", "ticker", "symbol", symbol);

      String requestJson = objectMapper.writeValueAsString(subscribeRequest);
      System.out.println("📡 Subscribing to ticker channel: " + symbol);
      System.out.println("   Request: " + requestJson);

      return Mono.just(session.textMessage(requestJson));
    } catch (Exception e) {
      System.err.println("❌ Error creating ticker subscription: " + e.getMessage());
      return Mono.empty();
    }
  }

  private static void processMessage(String message) {
    messageCount++;

    try {
      JsonNode json = objectMapper.readTree(message);

      // Command response
      if (json.has("command")) {
        String command = json.get("command").asText();
        System.out.println("✓ Command response: " + command);
        return;
      }

      // Channel data
      if (json.has("channel")) {
        String channel = json.get("channel").asText();

        if ("trades".equals(channel)) {
          tradeCount++;
          System.out.println();
          System.out.println("🎉 TRADES MESSAGE RECEIVED! #" + tradeCount);
          System.out.println(json.toPrettyString());
          System.out.println();
        } else if ("orderbooks".equals(channel)) {
          orderbookCount++;
          if (orderbookCount <= 3) { // Print first 3 orderbook messages
            System.out.println("📊 Orderbook #" + orderbookCount + " - " +
                json.get("symbol").asText() +
                " (bids: " + json.get("bids").size() +
                ", asks: " + json.get("asks").size() + ")");
          }
        } else if ("ticker".equals(channel)) {
          tickerCount++;
          if (tickerCount <= 3) { // Print first 3 ticker messages
            JsonNode ticker = json;
            System.out.println("💹 Ticker #" + tickerCount + " - " +
                ticker.get("symbol").asText() +
                " | Last: " + ticker.get("last").asText() +
                " | Bid: " + ticker.get("bid").asText() +
                " | Ask: " + ticker.get("ask").asText());
          }
        } else {
          unknownCount++;
          System.out.println("❓ Unknown channel: " + channel);
        }
      } else {
        unknownCount++;
        System.out.println("❓ Message without channel field: " +
            (message.length() > 100 ? message.substring(0, 100) + "..." : message));
      }
    } catch (Exception e) {
      System.err.println("❌ Error processing message: " + e.getMessage());
      System.err.println("   Message: " + (message.length() > 200 ? message.substring(0, 200) + "..." : message));
    }
  }
}
