package com.ys.exch_sim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.security.model.AuthenticationRequest;
import com.ys.exch_sim.security.model.AuthenticationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Disabled("Integration tests temporarily disabled - needs authentication setup fix")
class OrderIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private ObjectMapper objectMapper;

  private String jwtToken;
  private String baseUrl;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port;

    // 認証してJWTトークンを取得
    AuthenticationRequest authRequest = new AuthenticationRequest();
    authRequest.setUsername("admin");
    authRequest.setPassword("admin123");

    ResponseEntity<AuthenticationResponse> authResponse =
        restTemplate.postForEntity(
            baseUrl + "/api/auth/login", authRequest, AuthenticationResponse.class);

    assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    jwtToken = authResponse.getBody().getToken();
  }

  @Test
  void testCompleteOrderFlow() {
    // テストシナリオ: 実際のテストで実行したのと同じフロー

    // 1. BUY注文を作成
    NewOrderRequest buyRequest = new NewOrderRequest();
    buyRequest.setSymbol("BTCJPY");
    buyRequest.setPrice(100.0);
    buyRequest.setQuantity(10.0);
    buyRequest.setSide("BUY");
    buyRequest.setOrdType("LIMIT");
    buyRequest.setTif("GTC");

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwtToken);
    HttpEntity<NewOrderRequest> buyEntity = new HttpEntity<>(buyRequest, headers);

    ResponseEntity<OrderResponse> buyResponse =
        restTemplate.exchange(
            baseUrl + "/api/orders/new", HttpMethod.POST, buyEntity, OrderResponse.class);

    assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    OrderResponse buyOrderResponse = buyResponse.getBody();
    assertThat(buyOrderResponse.getStatus()).isEqualTo("NEW");
    assertThat(buyOrderResponse.getClOrdID()).isNotNull();
    assertThat(buyOrderResponse.getExecutions()).isEmpty();

    // 2. SELL注文を作成（BUY注文とマッチング）
    NewOrderRequest sellRequest = new NewOrderRequest();
    sellRequest.setSymbol("BTCJPY");
    sellRequest.setPrice(100.0);
    sellRequest.setQuantity(5.0);
    sellRequest.setSide("SELL");
    sellRequest.setOrdType("LIMIT");
    sellRequest.setTif("GTC");

    HttpEntity<NewOrderRequest> sellEntity = new HttpEntity<>(sellRequest, headers);

    ResponseEntity<OrderResponse> sellResponse =
        restTemplate.exchange(
            baseUrl + "/api/orders/new", HttpMethod.POST, sellEntity, OrderResponse.class);

    assertThat(sellResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    OrderResponse sellOrderResponse = sellResponse.getBody();
    assertThat(sellOrderResponse.getStatus()).isEqualTo("FILLED");
    assertThat(sellOrderResponse.getClOrdID()).isNotNull();
    assertThat(sellOrderResponse.getExecutions()).isNotEmpty();
    assertThat(sellOrderResponse.getExecutions().get(0).getLastPx()).isEqualTo(100.0);
    assertThat(sellOrderResponse.getExecutions().get(0).getLastQty()).isEqualTo(5L);
  }

  @Test
  void testUnauthorizedAccess() {
    // 認証なしでの注文送信テスト
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("BTCJPY");
    request.setPrice(100.0);
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    ResponseEntity<String> response =
        restTemplate.postForEntity(baseUrl + "/api/orders/new", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testInvalidJwtToken() {
    // 無効なJWTトークンでのアクセステスト
    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol("BTCJPY");
    request.setPrice(100.0);
    request.setQuantity(10.0);
    request.setSide("BUY");
    request.setOrdType("LIMIT");
    request.setTif("GTC");

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("invalid-token");
    HttpEntity<NewOrderRequest> entity = new HttpEntity<>(request, headers);

    ResponseEntity<String> response =
        restTemplate.exchange(baseUrl + "/api/orders/new", HttpMethod.POST, entity, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testMultipleSymbolsIndependency() {
    // 異なるシンボルの注文が独立していることをテスト

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwtToken);

    // SYMBOL1でBUY注文
    NewOrderRequest buy1Request = new NewOrderRequest();
    buy1Request.setSymbol("SYMBOL1");
    buy1Request.setPrice(100.0);
    buy1Request.setQuantity(10.0);
    buy1Request.setSide("BUY");
    buy1Request.setOrdType("LIMIT");
    buy1Request.setTif("GTC");

    HttpEntity<NewOrderRequest> buy1Entity = new HttpEntity<>(buy1Request, headers);
    ResponseEntity<OrderResponse> buy1Response =
        restTemplate.exchange(
            baseUrl + "/api/orders/new", HttpMethod.POST, buy1Entity, OrderResponse.class);

    assertThat(buy1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(buy1Response.getBody().getStatus()).isEqualTo("NEW");

    // SYMBOL2でSELL注文（マッチしないはず）
    NewOrderRequest sell2Request = new NewOrderRequest();
    sell2Request.setSymbol("SYMBOL2");
    sell2Request.setPrice(100.0);
    sell2Request.setQuantity(5.0);
    sell2Request.setSide("SELL");
    sell2Request.setOrdType("LIMIT");
    sell2Request.setTif("GTC");

    HttpEntity<NewOrderRequest> sell2Entity = new HttpEntity<>(sell2Request, headers);
    ResponseEntity<OrderResponse> sell2Response =
        restTemplate.exchange(
            baseUrl + "/api/orders/new", HttpMethod.POST, sell2Entity, OrderResponse.class);

    assertThat(sell2Response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sell2Response.getBody().getStatus()).isEqualTo("NEW"); // マッチしないのでNEWのまま
    assertThat(sell2Response.getBody().getExecutions()).isEmpty();
  }
}
