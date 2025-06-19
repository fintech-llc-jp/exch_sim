package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.MarketMakeOrderRequest;
import com.ys.exch_sim.domain.dto.MarketMakeOrderResponse;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.position.PositionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class MarketMakeServiceTest {

    private MarketMakeService marketMakeService;
    private OrderService orderService;
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
        btcjpy.setType("FX"); // MarketMakeはFX商品で行う
        instruments.put("BTCJPY", btcjpy);
        
        when(instrumentConfig.getInstruments()).thenReturn(instruments);
        when(instrumentConfig.isValidSymbol("BTCJPY")).thenReturn(true);
        when(instrumentConfig.isValidSymbol("INVALID")).thenReturn(false);
        when(instrumentConfig.getInstrument("BTCJPY")).thenReturn(btcjpy);
        
        orderService = new OrderService(executionQueueService, instrumentConfig, positionManager);
        marketMakeService = new MarketMakeService(orderService, instrumentConfig);
    }

    @Test
    void testProcessMarketMakeOrders() {
        // Given
        String username = "marketmaker1";
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("BTCJPY");
        
        // Bid levels
        request.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L),
            new MarketMakeOrderRequest.OrderLevel(98.0, 20L)
        ));
        
        // Ask levels
        request.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(101.0, 10L),
            new MarketMakeOrderRequest.OrderLevel(102.0, 20L)
        ));

        // When
        MarketMakeOrderResponse response = marketMakeService.processMarketMakeOrders(username, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getUsername()).isEqualTo(username);
        assertThat(response.getSymbol()).isEqualTo("BTCJPY");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getNewBidOrdersCount()).isEqualTo(2);
        assertThat(response.getNewAskOrdersCount()).isEqualTo(2);
        assertThat(response.getCancelledOrdersCount()).isEqualTo(0); // 初回なのでキャンセルなし
        assertThat(response.getBidOrderIds()).hasSize(2);
        assertThat(response.getAskOrderIds()).hasSize(2);
    }

    @Test
    void testProcessMarketMakeOrdersWithPreviousOrders() {
        // Given
        String username = "marketmaker1";
        
        // 最初のMarketMake注文
        MarketMakeOrderRequest firstRequest = new MarketMakeOrderRequest();
        firstRequest.setSymbol("BTCJPY");
        firstRequest.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L)
        ));
        firstRequest.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(101.0, 10L)
        ));
        
        marketMakeService.processMarketMakeOrders(username, firstRequest);

        // 2回目のMarketMake注文
        MarketMakeOrderRequest secondRequest = new MarketMakeOrderRequest();
        secondRequest.setSymbol("BTCJPY");
        secondRequest.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.5, 15L),
            new MarketMakeOrderRequest.OrderLevel(98.5, 25L)
        ));
        secondRequest.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(100.5, 15L)
        ));

        // When
        MarketMakeOrderResponse response = marketMakeService.processMarketMakeOrders(username, secondRequest);

        // Then
        assertThat(response.getCancelledOrdersCount()).isEqualTo(2); // 前回の注文2つをキャンセル
        assertThat(response.getNewBidOrdersCount()).isEqualTo(2);
        assertThat(response.getNewAskOrdersCount()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void testInvalidSymbolRejection() {
        // Given
        String username = "marketmaker1";
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("INVALID");
        request.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L)
        ));

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            marketMakeService.processMarketMakeOrders(username, request);
        });

        assertThat(exception.getMessage()).contains("Invalid symbol: INVALID");
    }

    @Test
    void testCancelAllMarketMakeOrders() {
        // Given
        String username = "marketmaker1";
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("BTCJPY");
        request.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L),
            new MarketMakeOrderRequest.OrderLevel(98.0, 20L)
        ));
        request.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(101.0, 10L)
        ));
        
        // MarketMake注文を投入
        marketMakeService.processMarketMakeOrders(username, request);

        // When
        int cancelledCount = marketMakeService.cancelAllMarketMakeOrders(username, "BTCJPY");

        // Then
        assertThat(cancelledCount).isEqualTo(3); // Bid 2個 + Ask 1個
    }

    @Test
    void testGetMarketMakeOrderStatus() {
        // Given
        String username = "marketmaker1";
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("BTCJPY");
        request.setBidLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(99.0, 10L)
        ));
        request.setAskLevels(Arrays.asList(
            new MarketMakeOrderRequest.OrderLevel(101.0, 10L)
        ));
        
        // MarketMake注文を投入
        marketMakeService.processMarketMakeOrders(username, request);

        // When
        MarketMakeOrderResponse status = marketMakeService.getMarketMakeOrderStatus(username, "BTCJPY");

        // Then
        assertThat(status).isNotNull();
        assertThat(status.getUsername()).isEqualTo(username);
        assertThat(status.getSymbol()).isEqualTo("BTCJPY");
        assertThat(status.getBidOrderIds()).hasSize(2); // すべてのアクティブ注文ID
        assertThat(status.getMessage()).contains("Current active market make orders: 2");
    }

    @Test
    void testEmptyOrderLevels() {
        // Given
        String username = "marketmaker1";
        MarketMakeOrderRequest request = new MarketMakeOrderRequest();
        request.setSymbol("BTCJPY");
        request.setBidLevels(Arrays.asList()); // 空のリスト
        request.setAskLevels(Arrays.asList()); // 空のリスト

        // When
        MarketMakeOrderResponse response = marketMakeService.processMarketMakeOrders(username, request);

        // Then
        assertThat(response.getNewBidOrdersCount()).isEqualTo(0);
        assertThat(response.getNewAskOrdersCount()).isEqualTo(0);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }
}