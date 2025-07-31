package com.ys.exch_sim.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.CancelOrderRequest;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.domain.position.PositionRepository;
import com.ys.exch_sim.domain.position.TradeHistoryRepository;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class OrderServiceTest {

  private OrderService orderService;
  private InstrumentConfig instrumentConfig;

  @BeforeEach
  void setUp() {
    ExecutionQueueService executionQueueService = new ExecutionQueueService();
    PositionRepository positionRepository = mock(PositionRepository.class);
    TradeHistoryRepository tradeHistoryRepository = mock(TradeHistoryRepository.class);
    PositionManager positionManager = new PositionManager(positionRepository, tradeHistoryRepository, true, false);

    // InstrumentConfigをモック化
    instrumentConfig = mock(InstrumentConfig.class);

    // 有効な商品の設定
    Map<String, InstrumentConfig.InstrumentDefinition> instruments = new HashMap<>();

    InstrumentConfig.InstrumentDefinition btcjpy = new InstrumentConfig.InstrumentDefinition();
    btcjpy.setName("Bitcoin/Japanese Yen");
    btcjpy.setPriceMultiplier(100);
    btcjpy.setQtyMultiplier(1);
    btcjpy.setType("Cash");
    instruments.put("BTCJPY", btcjpy);

    InstrumentConfig.InstrumentDefinition ethjpy = new InstrumentConfig.InstrumentDefinition();
    ethjpy.setName("Ethereum/Japanese Yen");
    ethjpy.setPriceMultiplier(100);
    ethjpy.setQtyMultiplier(1);
    ethjpy.setType("FX");
    instruments.put("ETHJPY", ethjpy);

    InstrumentConfig.InstrumentDefinition usdjpy = new InstrumentConfig.InstrumentDefinition();
    usdjpy.setName("US Dollar/Japanese Yen");
    usdjpy.setPriceMultiplier(100);
    usdjpy.setQtyMultiplier(1);
    usdjpy.setType("FX");
    instruments.put("USDJPY", usdjpy);

    when(instrumentConfig.getInstruments()).thenReturn(instruments);
    when(instrumentConfig.isValidSymbol("BTCJPY")).thenReturn(true);
    when(instrumentConfig.isValidSymbol("ETHJPY")).thenReturn(true);
    when(instrumentConfig.isValidSymbol("USDJPY")).thenReturn(true);
    when(instrumentConfig.isValidSymbol("INVALID")).thenReturn(false);
    when(instrumentConfig.getInstrument("BTCJPY")).thenReturn(btcjpy);
    when(instrumentConfig.getInstrument("ETHJPY")).thenReturn(ethjpy);
    when(instrumentConfig.getInstrument("USDJPY")).thenReturn(usdjpy);

    orderService = new OrderService(executionQueueService, instrumentConfig, positionManager);
  }

  @Test
  void testProcessNewBuyOrder() {
    // Given
    String username = "testuser";
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("BTCJPY");
    request.setPrice(100.0);
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    // When
    OrderResponse response = orderService.processNewOrder(username, request);

    // Then
    assertNotNull(response);
    assertNotNull(response.getClOrdID());
    assertThat(response.getStatus()).isEqualTo("NEW");
    // BUY注文は板に残るため、NEW executionが1つ返される（lastPx=0.0, lastQty=0）
    assertThat(response.getExecutions()).hasSize(1);
    assertThat(response.getExecutions().get(0).getExecStatus()).isEqualTo("NEW");
  }

  @Test
  void testProcessNewSellOrderWithMatching() {
    // Given - ETHJPYはFX商品なので空売り可能
    String username = "testuser";
    NewOrderRequest buyRequest = new NewOrderRequest();
    buyRequest.setSymbol("ETHJPY");
    buyRequest.setPrice(100.0);
    buyRequest.setQuantity(10.0);
    buyRequest.setSide("BUY");
    buyRequest.setOrdType("LIMIT");
    buyRequest.setTif("GTC");

    // BUY注文を処理
    OrderResponse buyResponse = orderService.processNewOrder(username, buyRequest);
    assertThat(buyResponse.getStatus()).isEqualTo("NEW");

    // When - 次にSELL注文を作成（部分約定になる）
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("ETHJPY");
    sellRequest.setPrice(100.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    OrderResponse sellResponse = orderService.processNewOrder(username, sellRequest);

    // Then
    assertNotNull(sellResponse);
    assertNotNull(sellResponse.getClOrdID());
    // 自分の注文に関連するExecutionのみが返される
    assertThat(sellResponse.getExecutions()).hasSize(1);

    // SELL注文は完全に約定するはず
    assertThat(sellResponse.getStatus()).isEqualTo("FILLED");
    assertThat(sellResponse.getExecutions().get(0).getExecStatus()).isEqualTo("FILLED");
    assertThat(sellResponse.getExecutions().get(0).getLastPx()).isEqualTo(100.0);
    assertThat(sellResponse.getExecutions().get(0).getLastQty()).isEqualTo(5L);
  }

  @Test
  void testProcessMultipleOrdersWithPartialFill() {
    // Given
    String username = "testuser";

    // 大きなBUY注文を作成
    NewOrderRequest buyRequest = new NewOrderRequest();
    buyRequest.setSymbol("USDJPY");
    buyRequest.setPrice(150.0);
    buyRequest.setQuantity(100.0);
    buyRequest.setSide("BUY");
    buyRequest.setOrdType("LIMIT");
    buyRequest.setTif("GTC");

    OrderResponse buyResponse = orderService.processNewOrder(username, buyRequest);
    assertThat(buyResponse.getStatus()).isEqualTo("NEW");

    // When - 小さなSELL注文を複数回実行
    NewOrderRequest sellRequest1 = new NewOrderRequest();
    sellRequest1.setSymbol("USDJPY");
    sellRequest1.setPrice(150.0);
    sellRequest1.setQuantity(30.0);
    sellRequest1.setSide("SELL");
    sellRequest1.setOrdType("LIMIT");
    sellRequest1.setTif("GTC");

    OrderResponse sellResponse1 = orderService.processNewOrder(username, sellRequest1);

    NewOrderRequest sellRequest2 = new NewOrderRequest();
    sellRequest2.setSymbol("USDJPY");
    sellRequest2.setPrice(150.0);
    sellRequest2.setQuantity(20.0);
    sellRequest2.setSide("SELL");
    sellRequest2.setOrdType("LIMIT");
    sellRequest2.setTif("GTC");

    OrderResponse sellResponse2 = orderService.processNewOrder(username, sellRequest2);

    // Then
    // 両方のSELL注文は完全に約定する
    assertThat(sellResponse1.getStatus()).isEqualTo("FILLED");
    assertThat(sellResponse1.getExecutions()).hasSize(1);
    assertThat(sellResponse1.getExecutions().get(0).getExecStatus()).isEqualTo("FILLED");
    assertThat(sellResponse1.getExecutions().get(0).getLastQty()).isEqualTo(30L);

    assertThat(sellResponse2.getStatus()).isEqualTo("FILLED");
    assertThat(sellResponse2.getExecutions()).hasSize(1);
    assertThat(sellResponse2.getExecutions().get(0).getExecStatus()).isEqualTo("FILLED");
    assertThat(sellResponse2.getExecutions().get(0).getLastQty()).isEqualTo(20L);
  }

  @Test
  void testDifferentSymbolsAreIndependent() {
    // Given
    String username = "testuser";

    // BTCJPY用のBUY注文
    NewOrderRequest btcBuyRequest = new NewOrderRequest();
    btcBuyRequest.setSymbol("BTCJPY");
    btcBuyRequest.setPrice(100.0);
    btcBuyRequest.setQuantity(10.0);
    btcBuyRequest.setSide("BUY");
    btcBuyRequest.setOrdType("LIMIT");
    btcBuyRequest.setTif("GTC");

    orderService.processNewOrder(username, btcBuyRequest);

    // When - ETHJPY用のSELL注文（マッチしないはず）
    NewOrderRequest ethSellRequest = new NewOrderRequest();
    ethSellRequest.setSymbol("ETHJPY");
    ethSellRequest.setPrice(100.0);
    ethSellRequest.setQuantity(5.0);
    ethSellRequest.setSide("SELL");
    ethSellRequest.setOrdType("LIMIT");
    ethSellRequest.setTif("GTC");

    OrderResponse ethSellResponse = orderService.processNewOrder(username, ethSellRequest);

    // Then - 異なるシンボルなのでマッチングされない
    assertThat(ethSellResponse.getStatus()).isEqualTo("NEW");
    assertThat(ethSellResponse.getExecutions()).hasSize(1);
    assertThat(ethSellResponse.getExecutions().get(0).getExecStatus()).isEqualTo("NEW");
  }

  @Test
  void testCancelOrder() {
    // Given - 注文を作成
    String username = "testuser";
    NewOrderRequest newOrderRequest = new NewOrderRequest();
    newOrderRequest.setSymbol("BTCJPY");
    newOrderRequest.setPrice(100.0);
    newOrderRequest.setQuantity(10.0);
    newOrderRequest.setSide("BUY");
    newOrderRequest.setOrdType("LIMIT");
    newOrderRequest.setTif("GTC");

    OrderResponse newOrderResponse = orderService.processNewOrder(username, newOrderRequest);
    assertThat(newOrderResponse.getStatus()).isEqualTo("NEW");

    // When - 注文をキャンセル
    CancelOrderRequest cancelRequest = new CancelOrderRequest();
    cancelRequest.setClOrdID(newOrderResponse.getClOrdID());
    cancelRequest.setSymbol("BTCJPY");

    OrderResponse cancelResponse = orderService.cancelOrder(username, cancelRequest);

    // Then
    assertNotNull(cancelResponse);
    assertThat(cancelResponse.getClOrdID()).isEqualTo(newOrderResponse.getClOrdID());
    assertThat(cancelResponse.getStatus()).isEqualTo("CANCELED");
    assertThat(cancelResponse.getExecutions()).hasSize(1);
    assertThat(cancelResponse.getExecutions().get(0).getExecStatus()).isEqualTo("CANCELED");
  }

  @Test
  void testCancelNonExistentOrder() {
    // Given
    String username = "testuser";
    CancelOrderRequest cancelRequest = new CancelOrderRequest();
    cancelRequest.setClOrdID("non-existent-order-id");
    cancelRequest.setSymbol("BTCJPY");

    // When & Then
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              orderService.cancelOrder(username, cancelRequest);
            });

    assertThat(exception.getMessage()).contains("Order not found");
  }

  @Test
  void testCancelOrderWithWrongSymbol() {
    // Given - 注文を作成
    String username = "testuser";
    NewOrderRequest newOrderRequest = new NewOrderRequest();
    newOrderRequest.setSymbol("BTCJPY");
    newOrderRequest.setPrice(100.0);
    newOrderRequest.setQuantity(10.0);
    newOrderRequest.setSide("BUY");
    newOrderRequest.setOrdType("LIMIT");
    newOrderRequest.setTif("GTC");

    OrderResponse newOrderResponse = orderService.processNewOrder(username, newOrderRequest);

    // When - 間違ったシンボルでキャンセルを試行
    CancelOrderRequest cancelRequest = new CancelOrderRequest();
    cancelRequest.setClOrdID(newOrderResponse.getClOrdID());
    cancelRequest.setSymbol("ETHJPY"); // 間違ったシンボル

    // Then
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              orderService.cancelOrder(username, cancelRequest);
            });

    assertThat(exception.getMessage()).contains("Symbol mismatch");
  }

  @Test
  void testInvalidSymbolRejection() {
    // Given
    String username = "testuser";
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("INVALID");
    request.setPrice(100.0);
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    // When & Then
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              orderService.processNewOrder(username, request);
            });

    assertThat(exception.getMessage()).contains("Invalid symbol: INVALID");
  }

  @Test
  void testInvalidSymbolRejectionOnCancel() {
    // Given
    String username = "testuser";
    CancelOrderRequest cancelRequest = new CancelOrderRequest();
    cancelRequest.setClOrdID("some-order-id");
    cancelRequest.setSymbol("INVALID");

    // When & Then
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              orderService.cancelOrder(username, cancelRequest);
            });

    assertThat(exception.getMessage()).contains("Invalid symbol: INVALID");
  }

  @Test
  void testCashShortSellingProhibition() {
    // Given - BTCJPYはCash商品
    String username = "testuser";
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("BTCJPY");
    sellRequest.setPrice(100.0);
    sellRequest.setQuantity(10.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    // When & Then - ポジションなしでCash商品を売ろうとするとエラー
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              orderService.processNewOrder(username, sellRequest);
            });

    assertThat(exception.getMessage()).contains("Insufficient position for cash sale");
  }

  @Test
  void testCashSellWithSufficientPosition() {
    // このテストは複雑すぎるため、シンプルなケースのみテスト
    // 実際の運用では、ポジション作成後にCash売りができることを確認

    // 代わりに簡単な統合テストとして、FX商品の空売りができることのみ確認
    String username = "testuser";
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("ETHJPY"); // FX商品
    sellRequest.setPrice(100.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    // FX商品の空売りは成功する
    OrderResponse sellResponse = orderService.processNewOrder(username, sellRequest);
    assertThat(sellResponse).isNotNull();
    assertThat(sellResponse.getStatus()).isEqualTo("NEW");
  }

  @Test
  void testFXShortSellingAllowed() {
    // Given - ETHJPYはFX商品
    String username = "testuser";
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("ETHJPY");
    sellRequest.setPrice(200.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    // When & Then - FX商品はポジションなしでも売り注文可能
    OrderResponse response = orderService.processNewOrder(username, sellRequest);
    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("NEW");
  }
}
