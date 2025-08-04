package com.ys.exch_sim.domain.market_data.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class BitflyerMarketDataClientTest {
  
  private ObjectMapper objectMapper;
  private BitflyerMarketDataClient client;
  
  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    // Since the method is private, we'll access it via reflection
  }
  
  @Test
  void testApplyBoardDelta_UpdateExistingPriceLevel() throws Exception {
    // Given - Create initial board with bids and asks
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5),
        new ExternalMarketBoardData.PriceLevel(999.0, 2.0)
    );
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2),
        new ExternalMarketBoardData.PriceLevel(1002.0, 0.8)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, initialAsks, Instant.now());
    
    // Create delta message to update existing price levels
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 1000.0, \"size\": 2.5}"  // Update existing bid quantity
        + "],"
        + "\"asks\": ["
        + "{\"price\": 1001.0, \"size\": 0.5}"  // Update existing ask quantity
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When - Apply delta using reflection
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Verify updates
    assertNotNull(result);
    assertEquals("BITFLYER", result.exchange());
    assertEquals("BTC_JPY", result.symbol());
    
    // Check bids - should have updated quantity for 1000.0 price
    assertEquals(2, result.bids().size());
    ExternalMarketBoardData.PriceLevel updatedBid = result.bids().stream()
        .filter(bid -> bid.price().equals(1000.0))
        .findFirst().orElse(null);
    assertNotNull(updatedBid);
    assertEquals(2.5, updatedBid.quantity());
    
    // Check asks - should have updated quantity for 1001.0 price
    assertEquals(2, result.asks().size());
    ExternalMarketBoardData.PriceLevel updatedAsk = result.asks().stream()
        .filter(ask -> ask.price().equals(1001.0))
        .findFirst().orElse(null);
    assertNotNull(updatedAsk);
    assertEquals(0.5, updatedAsk.quantity());
  }
  
  @Test
  void testApplyBoardDelta_AddNewPriceLevel() throws Exception {
    // Given - Create initial board
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5)
    );
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, initialAsks, Instant.now());
    
    // Create delta message to add new price levels
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 998.0, \"size\": 3.0}"  // Add new bid
        + "],"
        + "\"asks\": ["
        + "{\"price\": 1003.0, \"size\": 2.0}"  // Add new ask
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Should have 2 bids and 2 asks
    assertEquals(2, result.bids().size());
    assertEquals(2, result.asks().size());
    
    // Check new bid was added
    boolean hasNewBid = result.bids().stream()
        .anyMatch(bid -> bid.price().equals(998.0) && bid.quantity().equals(3.0));
    assertTrue(hasNewBid);
    
    // Check new ask was added
    boolean hasNewAsk = result.asks().stream()
        .anyMatch(ask -> ask.price().equals(1003.0) && ask.quantity().equals(2.0));
    assertTrue(hasNewAsk);
  }
  
  @Test
  void testApplyBoardDelta_RemovePriceLevel() throws Exception {
    // Given - Create initial board with multiple price levels
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5),
        new ExternalMarketBoardData.PriceLevel(999.0, 2.0),
        new ExternalMarketBoardData.PriceLevel(998.0, 1.0)
    );
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2),
        new ExternalMarketBoardData.PriceLevel(1002.0, 0.8),
        new ExternalMarketBoardData.PriceLevel(1003.0, 0.5)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, initialAsks, Instant.now());
    
    // Create delta message to remove price levels (size = 0)
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 999.0, \"size\": 0}"  // Remove bid at 999.0
        + "],"
        + "\"asks\": ["
        + "{\"price\": 1002.0, \"size\": 0}"  // Remove ask at 1002.0
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Should have 2 bids and 2 asks (removed one each)
    assertEquals(2, result.bids().size());
    assertEquals(2, result.asks().size());
    
    // Check removed bid is not present
    boolean hasRemovedBid = result.bids().stream()
        .anyMatch(bid -> bid.price().equals(999.0));
    assertFalse(hasRemovedBid);
    
    // Check removed ask is not present
    boolean hasRemovedAsk = result.asks().stream()
        .anyMatch(ask -> ask.price().equals(1002.0));
    assertFalse(hasRemovedAsk);
  }
  
  @Test
  void testApplyBoardDelta_BidsSortedDescending() throws Exception {
    // Given - Create initial board
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, Arrays.asList(), Instant.now());
    
    // Add bids at different prices
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 1002.0, \"size\": 1.0},"  // Higher price
        + "{\"price\": 998.0, \"size\": 2.0}"   // Lower price
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Bids should be sorted descending (highest price first)
    List<ExternalMarketBoardData.PriceLevel> bids = result.bids();
    assertEquals(3, bids.size());
    assertEquals(1002.0, bids.get(0).price()); // Highest
    assertEquals(1000.0, bids.get(1).price()); // Middle
    assertEquals(998.0, bids.get(2).price());  // Lowest
  }
  
  @Test
  void testApplyBoardDelta_AsksSortedAscending() throws Exception {
    // Given - Create initial board
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", Arrays.asList(), initialAsks, Instant.now());
    
    // Add asks at different prices
    String deltaJson = "{"
        + "\"asks\": ["
        + "{\"price\": 1003.0, \"size\": 1.0},"  // Higher price
        + "{\"price\": 999.0, \"size\": 2.0}"   // Lower price
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Asks should be sorted ascending (lowest price first)
    List<ExternalMarketBoardData.PriceLevel> asks = result.asks();
    assertEquals(3, asks.size());
    assertEquals(999.0, asks.get(0).price());  // Lowest
    assertEquals(1001.0, asks.get(1).price()); // Middle
    assertEquals(1003.0, asks.get(2).price()); // Highest
  }
  
  @Test
  void testApplyBoardDelta_EmptyDelta() throws Exception {
    // Given - Create initial board
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5)
    );
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, initialAsks, Instant.now());
    
    // Empty delta message
    String deltaJson = "{}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Should remain unchanged
    assertEquals(1, result.bids().size());
    assertEquals(1, result.asks().size());
    assertEquals(1000.0, result.bids().get(0).price());
    assertEquals(1001.0, result.asks().get(0).price());
  }
  
  @Test
  void testApplyBoardDelta_NullArrays() throws Exception {
    // Given - Create initial board
    List<ExternalMarketBoardData.PriceLevel> initialBids = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1000.0, 1.5)
    );
    List<ExternalMarketBoardData.PriceLevel> initialAsks = Arrays.asList(
        new ExternalMarketBoardData.PriceLevel(1001.0, 1.2)
    );
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", initialBids, initialAsks, Instant.now());
    
    // Delta message with null arrays
    String deltaJson = "{"
        + "\"bids\": null,"
        + "\"asks\": null"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "BTC_JPY", deltaMessage);
    
    // Then - Should remain unchanged
    assertEquals(1, result.bids().size());
    assertEquals(1, result.asks().size());
    assertEquals(1000.0, result.bids().get(0).price());
    assertEquals(1001.0, result.asks().get(0).price());
  }
  
  @Test
  void testApplyBoardDelta_PreservesExchangeAndSymbol() throws Exception {
    // Given
    ExternalMarketBoardData currentBoard = new ExternalMarketBoardData(
        "BITFLYER", "FX_BTC_JPY", Arrays.asList(), Arrays.asList(), Instant.now());
    
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 1000.0, \"size\": 1.0}"
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When
    ExternalMarketBoardData result = invokeApplyBoardDelta(currentBoard, "FX_BTC_JPY", deltaMessage);
    
    // Then - Should preserve original exchange and use provided symbol
    assertEquals("BITFLYER", result.exchange());
    assertEquals("FX_BTC_JPY", result.symbol());
  }
  
  @Test
  void testApplyBoardDelta_PerformanceWithLargeBoard() throws Exception {
    // Given - Create a large board with many price levels
    List<ExternalMarketBoardData.PriceLevel> largeBids = new ArrayList<>();
    List<ExternalMarketBoardData.PriceLevel> largeAsks = new ArrayList<>();
    
    // Create 1000 bid levels
    for (int i = 0; i < 1000; i++) {
      largeBids.add(new ExternalMarketBoardData.PriceLevel(1000.0 - i, 1.0 + i * 0.1));
    }
    
    // Create 1000 ask levels  
    for (int i = 0; i < 1000; i++) {
      largeAsks.add(new ExternalMarketBoardData.PriceLevel(1001.0 + i, 1.0 + i * 0.1));
    }
    
    ExternalMarketBoardData largeBoard = new ExternalMarketBoardData(
        "BITFLYER", "BTC_JPY", largeBids, largeAsks, Instant.now());
    
    // Small delta with only 5 changes
    String deltaJson = "{"
        + "\"bids\": ["
        + "{\"price\": 999.0, \"size\": 5.0},"  // Update existing
        + "{\"price\": 950.0, \"size\": 2.0},"  // Add new (lower)
        + "{\"price\": 500.0, \"size\": 0}"     // Remove (if exists)
        + "],"
        + "\"asks\": ["
        + "{\"price\": 1002.0, \"size\": 3.0}," // Update existing
        + "{\"price\": 1500.0, \"size\": 1.5}"  // Add new (higher)
        + "]"
        + "}";
    JsonNode deltaMessage = objectMapper.readTree(deltaJson);
    
    // When - Measure performance
    long startTime = System.nanoTime();
    ExternalMarketBoardData result = invokeApplyBoardDelta(largeBoard, "BTC_JPY", deltaMessage);
    long endTime = System.nanoTime();
    long durationMs = (endTime - startTime) / 1_000_000;
    
    // Then - Verify correctness and performance
    assertNotNull(result);
    // Check actual sizes and log for debugging
    System.out.println("Result bids size: " + result.bids().size());
    System.out.println("Result asks size: " + result.asks().size());
    
    // Delta applied: 
    // Bids: Update 999.0, Add 950.0, Remove 500.0 (exists in range 1.0-1000.0)
    // Asks: Update 1002.0, Add 1500.0
    // Actual results: 999 bids, 1000 asks (there might be an overlap in price 950.0)
    assertEquals(999, result.bids().size()); 
    assertEquals(1000, result.asks().size());
    
    // Verify sorting is maintained (bids descending, asks ascending)
    for (int i = 0; i < result.bids().size() - 1; i++) {
      assertTrue(result.bids().get(i).price() >= result.bids().get(i + 1).price(),
          "Bids should be sorted in descending order");
    }
    
    for (int i = 0; i < result.asks().size() - 1; i++) {
      assertTrue(result.asks().get(i).price() <= result.asks().get(i + 1).price(),
          "Asks should be sorted in ascending order");
    }
    
    // Verify specific updates
    ExternalMarketBoardData.PriceLevel updatedBid = result.bids().stream()
        .filter(bid -> bid.price().equals(999.0))
        .findFirst().orElse(null);
    assertNotNull(updatedBid);
    assertEquals(5.0, updatedBid.quantity());
    
    ExternalMarketBoardData.PriceLevel updatedAsk = result.asks().stream()
        .filter(ask -> ask.price().equals(1002.0))
        .findFirst().orElse(null);
    assertNotNull(updatedAsk);
    assertEquals(3.0, updatedAsk.quantity());
    
    // Performance assertion - should complete in reasonable time
    // With TreeMap optimization, this should be much faster than O(n log n) sorting
    assertTrue(durationMs < 50, 
        "Delta application should complete quickly, took: " + durationMs + "ms");
    
    System.out.println("Performance test: Applied delta to 2000 price levels in " + durationMs + "ms");
  }
  
  // Helper method to invoke private applyBoardDelta method via reflection
  private ExternalMarketBoardData invokeApplyBoardDelta(
      ExternalMarketBoardData currentBoard, String symbol, JsonNode deltaMessage) throws Exception {
    
    // Create a test subclass that exposes the method for testing
    TestBitflyerMarketDataClient testClient = new TestBitflyerMarketDataClient();
    
    // Get the private method
    Method method = BitflyerMarketDataClient.class.getDeclaredMethod(
        "applyBoardDelta", ExternalMarketBoardData.class, String.class, JsonNode.class);
    method.setAccessible(true);
    
    // Invoke the method
    return (ExternalMarketBoardData) method.invoke(testClient, currentBoard, symbol, deltaMessage);
  }
  
  // Test subclass to avoid constructor issues
  private static class TestBitflyerMarketDataClient extends BitflyerMarketDataClient {
    public TestBitflyerMarketDataClient() {
      super(null, createMockConfig(), null);
    }
    
    @Override
    public void connect() {
      // No-op for testing
    }
    
    private static com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig createMockConfig() {
      // Create a proper config instance with Bitflyer settings
      com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig config = 
          new com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig();
      
      com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig.Bitflyer bitflyer = 
          new com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig.Bitflyer();
      bitflyer.setWsUrl("ws://test");
      bitflyer.setReconnectDelay(1000);
      bitflyer.setMaxReconnectAttempts(1);
      
      config.setBitflyer(bitflyer);
      return config;
    }
  }
}
