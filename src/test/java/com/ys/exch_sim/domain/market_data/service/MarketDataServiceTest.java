package com.ys.exch_sim.domain.market_data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.queue.OrderedTradeProcessor;
import com.ys.exch_sim.domain.service.OrderService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {

  @Mock private OrderService orderService;

  @Mock private InstrumentConfig instrumentConfig;

  @Mock private MarketDataClientConfig clientConfig;

  @Mock private OrderedTradeProcessor orderedTradeProcessor;

  private MarketDataService service;

  @BeforeEach
  void setUp() {
    service =
        new MarketDataService(
            orderService, instrumentConfig, clientConfig, orderedTradeProcessor);
  }

  @Test
  void testProcessMarketBoard_Success() {
    // Given
    var bids =
        Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(100.0, 1.5),
            new ExternalMarketBoardData.PriceLevel(99.0, 2.0));
    var asks =
        Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(101.0, 1.2),
            new ExternalMarketBoardData.PriceLevel(102.0, 0.8));
    var boardData = new ExternalMarketBoardData("BITFLYER", "BTC_JPY", bids, asks, Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY")).thenReturn("G_BTCJPY");
    when(instrumentConfig.isValidSymbol("G_BTCJPY")).thenReturn(true);

    // When
    service.processMarketBoard(boardData);

    // Then
    verify(clientConfig, times(1)).mapSymbol("BITFLYER", "BTC_JPY");
  }

  @Test
  void testProcessMarketBoard_NoMapping() {
    // Given
    var boardData =
        new ExternalMarketBoardData(
            "BITFLYER", "UNKNOWN", Collections.emptyList(), Collections.emptyList(), Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "UNKNOWN")).thenReturn(null);

    // When
    service.processMarketBoard(boardData);

    // Then
    verify(clientConfig, times(1)).mapSymbol("BITFLYER", "UNKNOWN");
  }

  @Test
  void testProcessMarketBoard_InvalidSymbol() {
    // Given
    var boardData =
        new ExternalMarketBoardData(
            "BITFLYER", "BTC_JPY", Collections.emptyList(), Collections.emptyList(), Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY")).thenReturn("INVALID_SYMBOL");
    when(instrumentConfig.isValidSymbol("INVALID_SYMBOL")).thenReturn(false);

    // When
    service.processMarketBoard(boardData);

    // Then - invalid symbols should not cause errors
  }

  @Test
  void testProcessTrade_Success() {
    // Given
    var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY")).thenReturn("G_BTCJPY");
    when(instrumentConfig.isValidSymbol("G_BTCJPY")).thenReturn(true);

    // When
    service.processTrade(tradeData);

    // Then
    verify(orderedTradeProcessor, times(1)).submitTrade("G_BTCJPY", tradeData);
    verify(clientConfig, times(1)).mapSymbol("BITFLYER", "BTC_JPY");
  }

  @Test
  void testProcessTrade_NoMapping() {
    // Given
    var tradeData = new ExternalTradeData("BITFLYER", "UNKNOWN", 100.0, 1.5, "BUY", Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "UNKNOWN")).thenReturn(null);

    // When
    service.processTrade(tradeData);

    // Then
    verify(orderedTradeProcessor, never()).submitTrade(any(), any());
    verify(clientConfig, times(1)).mapSymbol("BITFLYER", "UNKNOWN");
  }

  @Test
  void testProcessTrade_InvalidSymbol() {
    // Given
    var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());

    // Mock設定
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY")).thenReturn("INVALID_SYMBOL");
    when(instrumentConfig.isValidSymbol("INVALID_SYMBOL")).thenReturn(false);

    // When
    service.processTrade(tradeData);

    // Then
    verify(orderedTradeProcessor, never()).submitTrade(any(), any());
  }

  @Test
  void testProcessTrade_ErrorHandling() {
    // Given
    var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());

    // Mock設定 - エラーを発生させる
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY"))
        .thenThrow(new RuntimeException("Test error"));

    // When & Then - エラーが発生しても処理が継続されることを確認
    assertDoesNotThrow(() -> service.processTrade(tradeData));
  }

  @Test
  void testProcessMarketBoard_ErrorHandling() {
    // Given
    var boardData =
        new ExternalMarketBoardData(
            "BITFLYER", "BTC_JPY", Collections.emptyList(), Collections.emptyList(), Instant.now());

    // Mock設定 - エラーを発生させる
    when(clientConfig.mapSymbol("BITFLYER", "BTC_JPY"))
        .thenThrow(new RuntimeException("Test error"));

    // When & Then - エラーが発生しても処理が継続されることを確認
    assertDoesNotThrow(() -> service.processMarketBoard(boardData));
  }

  @Test
  void testGetServiceStats() {
    // Given
    InstrumentConfig.InstrumentDefinition mockDef = new InstrumentConfig.InstrumentDefinition();
    when(instrumentConfig.getInstruments()).thenReturn(Map.of("G_BTCJPY", mockDef));

    // When
    String stats = service.getServiceStats();

    // Then
    assertNotNull(stats);
    assertTrue(stats.contains("MarketDataService"));
    assertTrue(stats.contains("Instruments: 1"));
    verify(instrumentConfig, atLeastOnce()).getInstruments();
  }

  @Test
  void testGetServiceStats_NullInstruments() {
    // Given
    when(instrumentConfig.getInstruments()).thenReturn(null);

    // When
    String stats = service.getServiceStats();

    // Then
    assertNotNull(stats);
    assertTrue(stats.contains("MarketDataService"));
    assertTrue(stats.contains("Instruments: 0"));
    verify(instrumentConfig, atLeastOnce()).getInstruments();
  }
}
