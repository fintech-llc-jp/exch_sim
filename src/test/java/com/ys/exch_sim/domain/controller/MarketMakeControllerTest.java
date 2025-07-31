package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.MarketMakeOrderRequest;
import com.ys.exch_sim.domain.dto.MarketMakeOrderResponse;
import com.ys.exch_sim.domain.service.MarketMakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

class MarketMakeControllerTest {

    private MarketMakeController marketMakeController;
    private MarketMakeService marketMakeService;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        marketMakeService = mock(MarketMakeService.class);
        marketMakeController = new MarketMakeController(marketMakeService);
        
        // MARKET_MAKER権限を持つ認証オブジェクトを作成
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("marketmaker1");
        when(authentication.isAuthenticated()).thenReturn(true);
        
        Collection<GrantedAuthority> authorities = Arrays.asList(
            new SimpleGrantedAuthority("ROLE_MARKET_MAKER"),
            new SimpleGrantedAuthority("ROLE_USER")
        );
        doReturn(authorities).when(authentication).getAuthorities();
    }

    @Test
    void testSubmitMarketMakeOrders() {
        // Given
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("BTCJPY");
        request.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L)
        ));
        request.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(101.0, 10L)
        ));

        MarketMakeOrderResponse expectedResponse = new MarketMakeOrderResponse("marketmaker1", "BTCJPY");
        expectedResponse.setNewBidOrdersCount(1);
        expectedResponse.setNewAskOrdersCount(1);
        expectedResponse.setCancelledOrdersCount(0);

        when(marketMakeService.processMarketMakeOrders("marketmaker1", request))
            .thenReturn(expectedResponse);

        // When
        ResponseEntity<?> response = marketMakeController.submitMarketMakeOrders(request, authentication);

        // Then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(MarketMakeOrderResponse.class);
        
        MarketMakeOrderResponse responseBody = (MarketMakeOrderResponse) response.getBody();
        assertThat(responseBody.getUsername()).isEqualTo("marketmaker1");
        assertThat(responseBody.getSymbol()).isEqualTo("BTCJPY");
        assertThat(responseBody.getNewBidOrdersCount()).isEqualTo(1);
        assertThat(responseBody.getNewAskOrdersCount()).isEqualTo(1);

        verify(marketMakeService).processMarketMakeOrders("marketmaker1", request);
    }

    @Test
    void testSubmitMarketMakeOrdersWithError() {
        // Given
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("INVALID");

        when(marketMakeService.processMarketMakeOrders("marketmaker1", request))
            .thenThrow(new RuntimeException("Invalid symbol"));

        // When
        ResponseEntity<?> response = marketMakeController.submitMarketMakeOrders(request, authentication);

        // Then
        assertThat(response.getStatusCodeValue()).isEqualTo(500);
        assertThat(response.getBody()).isInstanceOf(MarketMakeOrderResponse.class);
        
        MarketMakeOrderResponse responseBody = (MarketMakeOrderResponse) response.getBody();
        assertThat(responseBody.getStatus()).isEqualTo("ERROR");
        assertThat(responseBody.getMessage()).contains("Invalid symbol");
    }

    @Test
    void testCancelAllMarketMakeOrders() {
        // Given
        String symbol = "BTCJPY";
        when(marketMakeService.cancelAllMarketMakeOrders("marketmaker1", symbol))
            .thenReturn(3);

        // When
        ResponseEntity<?> response = marketMakeController.cancelAllMarketMakeOrders(symbol, authentication);

        // Then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(MarketMakeOrderResponse.class);
        
        MarketMakeOrderResponse responseBody = (MarketMakeOrderResponse) response.getBody();
        assertThat(responseBody.getUsername()).isEqualTo("marketmaker1");
        assertThat(responseBody.getSymbol()).isEqualTo(symbol);
        assertThat(responseBody.getCancelledOrdersCount()).isEqualTo(3);
        assertThat(responseBody.getMessage()).contains("All market make orders cancelled");

        verify(marketMakeService).cancelAllMarketMakeOrders("marketmaker1", symbol);
    }

    @Test
    void testGetMarketMakeOrderStatus() {
        // Given
        String symbol = "BTCJPY";
        MarketMakeOrderResponse expectedResponse = new MarketMakeOrderResponse("marketmaker1", symbol);
        expectedResponse.setBidOrderIds(Arrays.asList("order1", "order2"));
        expectedResponse.setMessage("Current active market make orders: 2");

        when(marketMakeService.getMarketMakeOrderStatus("marketmaker1", symbol))
            .thenReturn(expectedResponse);

        // When
        ResponseEntity<?> response = marketMakeController.getMarketMakeOrderStatus(symbol, authentication);

        // Then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(MarketMakeOrderResponse.class);
        
        MarketMakeOrderResponse responseBody = (MarketMakeOrderResponse) response.getBody();
        assertThat(responseBody.getUsername()).isEqualTo("marketmaker1");
        assertThat(responseBody.getSymbol()).isEqualTo(symbol);
        assertThat(responseBody.getBidOrderIds()).hasSize(2);

        verify(marketMakeService).getMarketMakeOrderStatus("marketmaker1", symbol);
    }
}