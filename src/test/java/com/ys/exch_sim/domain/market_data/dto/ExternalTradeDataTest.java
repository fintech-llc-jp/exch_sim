package com.ys.exch_sim.domain.market_data.dto;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExternalTradeDataTest {

    @Test
    void testValidExternalTradeData() {
        // Given
        Instant timestamp = Instant.now();

        // When
        var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", timestamp);

        // Then
        assertEquals("BITFLYER", tradeData.exchange());
        assertEquals("BTC_JPY", tradeData.symbol());
        assertEquals(100.0, tradeData.price());
        assertEquals(1.5, tradeData.quantity());
        assertEquals("BUY", tradeData.side());
        assertEquals(timestamp, tradeData.timestamp());
    }

    @Test
    void testNotionalAmount() {
        // When
        var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());

        // Then
        assertEquals(150.0, tradeData.getNotionalAmount());
    }

    @Test
    void testSideCheckers() {
        // Given
        var buyTrade = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());
        var sellTrade = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "SELL", Instant.now());

        // Then
        assertTrue(buyTrade.isBuy());
        assertFalse(buyTrade.isSell());
        assertFalse(sellTrade.isBuy());
        assertTrue(sellTrade.isSell());
    }

    @Test
    void testInvalidExchange() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("", "BTC_JPY", 100.0, 1.5, "BUY", Instant.now());
        });
    }

    @Test
    void testInvalidSymbol() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", null, 100.0, 1.5, "BUY", Instant.now());
        });
    }

    @Test
    void testInvalidPrice() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", -1.0, 1.5, "BUY", Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", null, 1.5, "BUY", Instant.now());
        });
    }

    @Test
    void testInvalidQuantity() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, -1.0, "BUY", Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, null, "BUY", Instant.now());
        });
    }

    @Test
    void testInvalidSide() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "INVALID", Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, null, Instant.now());
        });
    }

    @Test
    void testTimestampDefault() {
        // When - timestampにnullを渡す
        var tradeData = new ExternalTradeData("BITFLYER", "BTC_JPY", 100.0, 1.5, "BUY", null);

        // Then - 現在時刻が設定される
        assertNotNull(tradeData.timestamp());
        assertTrue(tradeData.timestamp().isBefore(Instant.now().plusSeconds(1)));
    }
}