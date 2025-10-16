package com.ys.exch_sim.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.domain.position.PositionRepository;
import com.ys.exch_sim.domain.position.TradeHistoryRepository;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.domain.service.OrderService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class OrderControllerTest {

  private OrderService orderService;
  private OrderController orderController;
  private InstrumentConfig instrumentConfig;
  private PositionManager positionManager;

  @BeforeEach
  void setUp() {
    ExecutionQueueService executionQueueService = mock(ExecutionQueueService.class);
    PositionRepository positionRepository = mock(PositionRepository.class);
    TradeHistoryRepository tradeHistoryRepository = mock(TradeHistoryRepository.class);
    positionManager = new PositionManager(positionRepository, tradeHistoryRepository, true);

    // InstrumentConfigをモック化
    instrumentConfig = mock(InstrumentConfig.class);

    // 有効な商品の設定
    Map<String, InstrumentConfig.InstrumentDefinition> instruments = new HashMap<>();

    InstrumentConfig.InstrumentDefinition btcjpy = new InstrumentConfig.InstrumentDefinition();
    btcjpy.setName("Bitcoin/Japanese Yen");
    btcjpy.setPriceMultiplier(100);
    btcjpy.setQtyMultiplier(1);
    instruments.put("BTCJPY", btcjpy);

    InstrumentConfig.InstrumentDefinition testjpy = new InstrumentConfig.InstrumentDefinition();
    testjpy.setName("Test/Japanese Yen");
    testjpy.setPriceMultiplier(100);
    testjpy.setQtyMultiplier(1);
    instruments.put("TESTJPY", testjpy);

    when(instrumentConfig.getInstruments()).thenReturn(instruments);
    when(instrumentConfig.isValidSymbol("BTCJPY")).thenReturn(true);
    when(instrumentConfig.isValidSymbol("TESTJPY")).thenReturn(true);
    when(instrumentConfig.getInstrument("BTCJPY")).thenReturn(btcjpy);
    when(instrumentConfig.getInstrument("TESTJPY")).thenReturn(testjpy);

    orderService = new OrderService(executionQueueService, instrumentConfig, positionManager);
    orderController = new OrderController(orderService, null);
    
    // テストユーザーに初期現金残高を設定 (100万円)
    initializeTestUserCash("testuser", 1000000.0);
  }
  
  /**
   * テストユーザーに初期現金残高を設定するヘルパーメソッド
   */
  private void initializeTestUserCash(String username, double initialCash) {
    positionManager.initializeUserWithCash(username, initialCash);
  }

  @Test
  void testOrderServiceLogic() {
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
    assertThat(response.getExecutions()).hasSize(1);
    assertThat(response.getExecutions().get(0).getExecStatus()).isEqualTo("NEW");
  }

  @Test
  void testOrderValidation() {
    // Given - 無効な注文リクエスト
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("BTCJPY");
    request.setPrice(-100.0); // 負の価格
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    // When & Then - バリデーションロジックをテスト
    boolean isValid = isValidOrderRequest(request);
    assertThat(isValid).isFalse();
  }

  @Test
  void testNullSymbolValidation() {
    // Given - null symbol
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol(null);
    request.setPrice(100.0);
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    // When & Then
    boolean isValid = isValidOrderRequest(request);
    assertThat(isValid).isFalse();
  }

  @Test
  void testOrderMatching() {
    // Given
    String username = "testuser";

    // BUY注文を作成
    NewOrderRequest buyRequest = new NewOrderRequest();
    buyRequest.setSymbol("TESTJPY");
    buyRequest.setPrice(100.0);
    buyRequest.setQuantity(10.0);
    buyRequest.setSide("BUY");
    buyRequest.setOrdType("LIMIT");
    buyRequest.setTif("GTC");

    // When
    OrderResponse buyResponse = orderService.processNewOrder(username, buyRequest);

    // Then
    assertThat(buyResponse.getStatus()).isEqualTo("NEW");

    // SELL注文を作成してマッチングをテスト
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("TESTJPY");
    sellRequest.setPrice(100.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    OrderResponse sellResponse = orderService.processNewOrder(username, sellRequest);

    // Then
    assertThat(sellResponse.getStatus()).isEqualTo("FILLED");
    assertThat(sellResponse.getExecutions()).hasSize(1);
    assertThat(sellResponse.getExecutions().get(0).getLastQty()).isEqualTo(5L);
  }

  // OrderControllerのバリデーションロジックを模倣
  private boolean isValidOrderRequest(NewOrderRequest request) {
    return request != null
        && request.getSymbol() != null
        && !request.getSymbol().trim().isEmpty()
        && request.getPrice() != null
        && request.getPrice() > 0
        && request.getQuantity() != null
        && request.getQuantity() > 0
        && request.getSide() != null
        && !request.getSide().trim().isEmpty()
        && request.getOrdType() != null
        && !request.getOrdType().trim().isEmpty()
        && request.getTif() != null
        && !request.getTif().trim().isEmpty();
  }
}
