package com.ys.exch_sim.domain.market_data.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ys.exch_sim.domain.market_data.config.WebSocketClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

/** WebSocket Client設定のテスト */
class WebSocketClientConfigTest {

  @Test
  void testWebSocketClientConfiguration() {
    WebSocketClientConfig config = new WebSocketClientConfig();
    WebSocketClient client = config.webSocketClient();

    assertNotNull(client);
    assertTrue(client instanceof ReactorNettyWebSocketClient);

    ReactorNettyWebSocketClient reactorClient = (ReactorNettyWebSocketClient) client;
    assertNotNull(reactorClient.getHttpClient());
  }
}
