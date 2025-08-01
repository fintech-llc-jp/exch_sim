package com.ys.exch_sim.domain.market_data.dto;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ExternalMarketBoardDataTest {

    @Test
    void testValidExternalMarketBoardData() {
        // Given
        var bids = Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(100.0, 1.5),
            new ExternalMarketBoardData.PriceLevel(99.0, 2.0)
        );
        var asks = Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(101.0, 1.2),
            new ExternalMarketBoardData.PriceLevel(102.0, 0.8)
        );
        Instant timestamp = Instant.now();

        // When
        var boardData = new ExternalMarketBoardData("BITFLYER", "BTC_JPY", bids, asks, timestamp);

        // Then
        assertEquals("BITFLYER", boardData.exchange());
        assertEquals("BTC_JPY", boardData.symbol());
        assertEquals(2, boardData.bids().size());
        assertEquals(2, boardData.asks().size());
        assertEquals(timestamp, boardData.timestamp());
    }

    @Test
    void testBestPrices() {
        // Given
        var bids = Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(100.0, 1.5),
            new ExternalMarketBoardData.PriceLevel(99.0, 2.0)
        );
        var asks = Arrays.asList(
            new ExternalMarketBoardData.PriceLevel(101.0, 1.2),
            new ExternalMarketBoardData.PriceLevel(102.0, 0.8)
        );

        // When
        var boardData = new ExternalMarketBoardData("BITFLYER", "BTC_JPY", bids, asks, Instant.now());

        // Then
        assertEquals(100.0, boardData.getBestBidPrice());
        assertEquals(101.0, boardData.getBestAskPrice());
        assertEquals(1.0, boardData.getSpread());
    }

    @Test
    void testEmptyBidsAsks() {
        // When
        var boardData = new ExternalMarketBoardData("BITFLYER", "BTC_JPY", 
            Collections.emptyList(), Collections.emptyList(), Instant.now());

        // Then
        assertNull(boardData.getBestBidPrice());
        assertNull(boardData.getBestAskPrice());
        assertNull(boardData.getSpread());
    }

    @Test
    void testInvalidExchange() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData("", "BTC_JPY", 
                Collections.emptyList(), Collections.emptyList(), Instant.now());
        });
    }

    @Test
    void testInvalidSymbol() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData("BITFLYER", null, 
                Collections.emptyList(), Collections.emptyList(), Instant.now());
        });
    }

    @Test
    void testNullBids() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData("BITFLYER", "BTC_JPY", 
                null, Collections.emptyList(), Instant.now());
        });
    }

    @Test
    void testPriceLevelValidation() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData.PriceLevel(-1.0, 1.0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData.PriceLevel(100.0, -1.0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ExternalMarketBoardData.PriceLevel(null, 1.0);
        });
    }
}