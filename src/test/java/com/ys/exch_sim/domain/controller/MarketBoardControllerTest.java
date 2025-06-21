package com.ys.exch_sim.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.MarketBoardResponse;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.domain.service.OrderService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

@SpringBootTest
class MarketBoardControllerTest {

  private OrderService orderService;
  private MarketBoardController marketBoardController;
  private InstrumentConfig instrumentConfig;

  @BeforeEach
  void setUp() {
    ExecutionQueueService executionQueueService = new ExecutionQueueService();
    PositionManager positionManager = new PositionManager();

    // InstrumentConfigをモック化
    instrumentConfig = mock(InstrumentConfig.class);

    // 有効な商品の設定
    Map<String, InstrumentConfig.InstrumentDefinition> instruments = new HashMap<>();

    InstrumentConfig.InstrumentDefinition btcjpy = new InstrumentConfig.InstrumentDefinition();
    btcjpy.setName("Bitcoin/Japanese Yen");
    btcjpy.setPriceMultiplier(100);
    btcjpy.setQtyMultiplier(1);
    instruments.put("BTCJPY", btcjpy);

    when(instrumentConfig.getInstruments()).thenReturn(instruments);
    when(instrumentConfig.isValidSymbol("BTCJPY")).thenReturn(true);
    when(instrumentConfig.getInstrument("BTCJPY")).thenReturn(btcjpy);

    orderService = new OrderService(executionQueueService, instrumentConfig, positionManager);
    marketBoardController = new MarketBoardController(orderService);
  }

  @Test
  void testGetMarketBoardWithEmptyBoard() {
    // Given
    String symbol = "BTCJPY";

    // When
    ResponseEntity<?> response = marketBoardController.getMarketBoard(symbol, 10);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    MarketBoardResponse marketBoard = (MarketBoardResponse) response.getBody();
    assertNotNull(marketBoard);
    assertEquals(symbol, marketBoard.getSymbol());
    assertThat(marketBoard.getBids()).isEmpty();
    assertThat(marketBoard.getAsks()).isEmpty();
  }

  @Test
  void testGetMarketBoardWithOrders() {
    // Given
    String symbol = "BTCJPY";

    // BUY注文を作成
    NewOrderRequest buyRequest = new NewOrderRequest();
    buyRequest.setSymbol(symbol);
    buyRequest.setPrice(100.0);
    buyRequest.setQuantity(10.0);
    buyRequest.setSide("BUY");
    buyRequest.setOrdType("LIMIT");
    buyRequest.setTif("GTC");

    // SELL注文を作成
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol(symbol);
    sellRequest.setPrice(102.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    // 注文を処理
    orderService.processNewOrder("user1", buyRequest);
    orderService.processNewOrder("user2", sellRequest);

    // When
    ResponseEntity<?> response = marketBoardController.getMarketBoard(symbol, 10);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    MarketBoardResponse marketBoard = (MarketBoardResponse) response.getBody();
    assertNotNull(marketBoard);
    assertEquals(symbol, marketBoard.getSymbol());

    // BUY注文（bid）が1つあることを確認
    assertThat(marketBoard.getBids()).hasSize(1);
    MarketBoardResponse.PriceLevel bid = marketBoard.getBids().get(0);
    assertEquals(100.0, bid.getPrice());
    assertEquals(10L, bid.getQuantity());

    // SELL注文（ask）が1つあることを確認
    assertThat(marketBoard.getAsks()).hasSize(1);
    MarketBoardResponse.PriceLevel ask = marketBoard.getAsks().get(0);
    assertEquals(102.0, ask.getPrice());
    assertEquals(5L, ask.getQuantity());
  }

  @Test
  void testGetMarketBoardWithMultipleOrders() {
    // Given
    String symbol = "BTCJPY";

    // 複数のBUY注文を作成（価格降順になるはず）
    NewOrderRequest buyRequest1 = new NewOrderRequest();
    buyRequest1.setSymbol(symbol);
    buyRequest1.setPrice(100.0);
    buyRequest1.setQuantity(10.0);
    buyRequest1.setSide("BUY");
    buyRequest1.setOrdType("LIMIT");
    buyRequest1.setTif("GTC");

    NewOrderRequest buyRequest2 = new NewOrderRequest();
    buyRequest2.setSymbol(symbol);
    buyRequest2.setPrice(99.0);
    buyRequest2.setQuantity(15.0);
    buyRequest2.setSide("BUY");
    buyRequest2.setOrdType("LIMIT");
    buyRequest2.setTif("GTC");

    // 複数のSELL注文を作成（価格昇順になるはず）
    NewOrderRequest sellRequest1 = new NewOrderRequest();
    sellRequest1.setSymbol(symbol);
    sellRequest1.setPrice(101.0);
    sellRequest1.setQuantity(8.0);
    sellRequest1.setSide("SELL");
    sellRequest1.setOrdType("LIMIT");
    sellRequest1.setTif("GTC");

    NewOrderRequest sellRequest2 = new NewOrderRequest();
    sellRequest2.setSymbol(symbol);
    sellRequest2.setPrice(102.0);
    sellRequest2.setQuantity(12.0);
    sellRequest2.setSide("SELL");
    sellRequest2.setOrdType("LIMIT");
    sellRequest2.setTif("GTC");

    // 注文を処理
    orderService.processNewOrder("user1", buyRequest1);
    orderService.processNewOrder("user2", buyRequest2);
    orderService.processNewOrder("user3", sellRequest1);
    orderService.processNewOrder("user4", sellRequest2);

    // When
    ResponseEntity<?> response = marketBoardController.getMarketBoard(symbol, 10);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    MarketBoardResponse marketBoard = (MarketBoardResponse) response.getBody();
    assertNotNull(marketBoard);

    // BUY注文が2つあり、価格降順になっていることを確認
    assertThat(marketBoard.getBids()).hasSize(2);
    assertEquals(100.0, marketBoard.getBids().get(0).getPrice()); // 最高価格が最初
    assertEquals(99.0, marketBoard.getBids().get(1).getPrice());

    // SELL注文が2つあり、価格昇順になっていることを確認
    assertThat(marketBoard.getAsks()).hasSize(2);
    assertEquals(101.0, marketBoard.getAsks().get(0).getPrice()); // 最低価格が最初
    assertEquals(102.0, marketBoard.getAsks().get(1).getPrice());
  }

  @Test
  void testGetMarketBoardSimple() {
    // Given
    String symbol = "BTCJPY";

    // When
    ResponseEntity<?> response = marketBoardController.getMarketBoardSimple(symbol);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    MarketBoardResponse marketBoard = (MarketBoardResponse) response.getBody();
    assertNotNull(marketBoard);
    assertEquals(symbol, marketBoard.getSymbol());
  }

  @Test
  void testGetMarketBoardWithInvalidSymbol() {
    // When
    ResponseEntity<?> response = marketBoardController.getMarketBoard("", 10);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertEquals("Symbol is required", response.getBody());
  }

  @Test
  void testGetMarketBoardWithInvalidDepth() {
    // When
    ResponseEntity<?> response1 = marketBoardController.getMarketBoard("BTCJPY", 0);
    ResponseEntity<?> response2 = marketBoardController.getMarketBoard("BTCJPY", 101);

    // Then
    assertThat(response1.getStatusCode().value()).isEqualTo(400);
    assertThat(response2.getStatusCode().value()).isEqualTo(400);
    assertEquals("Depth must be between 1 and 100", response1.getBody());
    assertEquals("Depth must be between 1 and 100", response2.getBody());
  }

  @Test
  void testGetMarketBoardWithDepthLimitation() {
    // Given
    String symbol = "BTCJPY";

    // 3つのBUY注文を作成
    for (int i = 0; i < 3; i++) {
      NewOrderRequest buyRequest = new NewOrderRequest();
      buyRequest.setSymbol(symbol);
      buyRequest.setPrice(100.0 - i);
      buyRequest.setQuantity(10.0);
      buyRequest.setSide("BUY");
      buyRequest.setOrdType("LIMIT");
      buyRequest.setTif("GTC");
      orderService.processNewOrder("user" + i, buyRequest);
    }

    // When - 深度2で取得
    ResponseEntity<?> response = marketBoardController.getMarketBoard(symbol, 2);

    // Then
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    MarketBoardResponse marketBoard = (MarketBoardResponse) response.getBody();
    assertNotNull(marketBoard);

    // 深度2なので2つまでしか取得されない
    assertThat(marketBoard.getBids()).hasSize(2);
    assertEquals(100.0, marketBoard.getBids().get(0).getPrice());
    assertEquals(99.0, marketBoard.getBids().get(1).getPrice());
  }
}
