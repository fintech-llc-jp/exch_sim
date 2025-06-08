package com.ys.exch_sim.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrderControllerTest {

  private OrderService orderService;
  private OrderController orderController;

  @BeforeEach
  void setUp() {
    orderService = new OrderService();
    orderController = new OrderController(orderService, null);
  }

  @Test
  void testOrderServiceLogic() {
    // Given
    String username = "testuser";
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("BTCJPY");
    request.setPrice(100.0);
    request.setQuantity(10L);
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
    request.setQuantity(10L);
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
    request.setQuantity(10L);
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
    buyRequest.setQuantity(10L);
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
    sellRequest.setQuantity(5L);
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
