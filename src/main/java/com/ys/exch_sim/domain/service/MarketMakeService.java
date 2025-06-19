package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketMakeService {

  private final OrderService orderService;
  private final InstrumentConfig instrumentConfig;

  // ユーザー別・銘柄別のMarketMake注文IDを管理
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> marketMakeOrders =
      new ConcurrentHashMap<>();

  // シングルスレッド処理を保証するためのロック（銘柄別）
  private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

  public MarketMakeOrderResponse processMarketMakeOrders(
      String username, MarketMakeOrderRequest request) {
    String symbol = request.getSymbol().toUpperCase();

    // 商品の存在チェック
    if (!instrumentConfig.isValidSymbol(symbol)) {
      throw new RuntimeException("Invalid symbol: " + symbol);
    }

    // 商品設定の詳細をログ出力
    InstrumentConfig.InstrumentDefinition instrument = instrumentConfig.getInstrument(symbol);
    log.info(
        "Processing market make orders for user: {} symbol: {} with instrument config: name={},"
            + " priceMultiplier={}, qtyMultiplier={}, type={}",
        username,
        symbol,
        instrument.getName(),
        instrument.getPriceMultiplier(),
        instrument.getQtyMultiplier(),
        instrument.getType());

    // 銘柄別のロックを取得してシングルスレッド処理を保証
    ReentrantLock lock = symbolLocks.computeIfAbsent(symbol, k -> new ReentrantLock());

    lock.lock();
    try {
      log.info(
          "Processing market make orders for user: {} symbol: {} with lock acquired",
          username,
          symbol);

      // 1. 既存のMarketMake注文をすべてキャンセル
      int cancelledCount = cancelAllMarketMakeOrdersInternal(username, symbol);

      // 2. 新しい注文を一括で投入
      List<String> bidOrderIds = new ArrayList<>();
      List<String> askOrderIds = new ArrayList<>();

      // Bid注文を処理
      if (request.getBidLevels() != null) {
        log.info(
            "Processing {} bid levels for user: {} symbol: {}",
            request.getBidLevels().size(),
            username,
            symbol);
        for (MarketMakeOrderRequest.OrderLevel level : request.getBidLevels()) {
          try {
            log.info(
                "Processing bid level: price={}, quantity={}",
                level.getPrice(),
                level.getQuantity());
            NewOrderRequest orderRequest = createOrderRequest(symbol, level, "BUY");
            OrderResponse response = orderService.processNewOrder(username, orderRequest);
            bidOrderIds.add(response.getClOrdID());
            addMarketMakeOrder(username, symbol, response.getClOrdID());
            log.info("Successfully placed bid order with clOrdID: {}", response.getClOrdID());
          } catch (Exception e) {
            log.warn(
                "Failed to place bid order at price {} for user {}: {}",
                level.getPrice(),
                username,
                e.getMessage());
          }
        }
      }

      // Ask注文を処理
      if (request.getAskLevels() != null) {
        log.info(
            "Processing {} ask levels for user: {} symbol: {}",
            request.getAskLevels().size(),
            username,
            symbol);
        for (MarketMakeOrderRequest.OrderLevel level : request.getAskLevels()) {
          try {
            log.info(
                "Processing ask level: price={}, quantity={}",
                level.getPrice(),
                level.getQuantity());
            NewOrderRequest orderRequest = createOrderRequest(symbol, level, "SELL");
            OrderResponse response = orderService.processNewOrder(username, orderRequest);
            askOrderIds.add(response.getClOrdID());
            addMarketMakeOrder(username, symbol, response.getClOrdID());
            log.info("Successfully placed ask order with clOrdID: {}", response.getClOrdID());
          } catch (Exception e) {
            log.warn(
                "Failed to place ask order at price {} for user {}: {}",
                level.getPrice(),
                username,
                e.getMessage());
          }
        }
      }

      // レスポンスを作成
      MarketMakeOrderResponse response = new MarketMakeOrderResponse(username, symbol);
      response.setCancelledOrdersCount(cancelledCount);
      response.setNewBidOrdersCount(bidOrderIds.size());
      response.setNewAskOrdersCount(askOrderIds.size());
      response.setBidOrderIds(bidOrderIds);
      response.setAskOrderIds(askOrderIds);
      response.setMessage("Market make orders processed successfully");

      log.info(
          "Market make orders processed for user: {} symbol: {} - Cancelled: {}, New Bids: {}, New"
              + " Asks: {}",
          username,
          symbol,
          cancelledCount,
          bidOrderIds.size(),
          askOrderIds.size());

      return response;

    } finally {
      lock.unlock();
      log.debug("Released lock for symbol: {}", symbol);
    }
  }

  public int cancelAllMarketMakeOrders(String username, String symbol) {
    String normalizedSymbol = symbol.toUpperCase();

    // 銘柄別のロックを取得
    ReentrantLock lock = symbolLocks.computeIfAbsent(normalizedSymbol, k -> new ReentrantLock());

    lock.lock();
    try {
      return cancelAllMarketMakeOrdersInternal(username, normalizedSymbol);
    } finally {
      lock.unlock();
    }
  }

  private int cancelAllMarketMakeOrdersInternal(String username, String symbol) {
    Set<String> orderIds = getMarketMakeOrders(username, symbol);
    int cancelledCount = 0;

    for (String orderId : new HashSet<>(orderIds)) {
      try {
        CancelOrderRequest cancelRequest = new CancelOrderRequest();
        cancelRequest.setClOrdID(orderId);
        cancelRequest.setSymbol(symbol);

        orderService.cancelOrder(username, cancelRequest);
        removeMarketMakeOrder(username, symbol, orderId);
        cancelledCount++;

      } catch (Exception e) {
        log.warn(
            "Failed to cancel market make order {} for user {}: {}",
            orderId,
            username,
            e.getMessage());
        // 失敗した注文IDは管理から削除
        removeMarketMakeOrder(username, symbol, orderId);
      }
    }

    log.info(
        "Cancelled {} market make orders for user: {} symbol: {}",
        cancelledCount,
        username,
        symbol);
    return cancelledCount;
  }

  public MarketMakeOrderResponse getMarketMakeOrderStatus(String username, String symbol) {
    String normalizedSymbol = symbol.toUpperCase();
    Set<String> orderIds = getMarketMakeOrders(username, normalizedSymbol);

    MarketMakeOrderResponse response = new MarketMakeOrderResponse(username, normalizedSymbol);
    response.setCancelledOrdersCount(0);
    response.setNewBidOrdersCount(0);
    response.setNewAskOrdersCount(0);

    List<String> activeOrderIds = new ArrayList<>(orderIds);
    response.setBidOrderIds(activeOrderIds); // 簡略化：区別せずすべてのIDを返す
    response.setAskOrderIds(new ArrayList<>());
    response.setMessage("Current active market make orders: " + orderIds.size());

    return response;
  }

  private NewOrderRequest createOrderRequest(
      String symbol, MarketMakeOrderRequest.OrderLevel level, String side) {
    log.info(
        "Creating order request for symbol: {} side: {} with level: price={}, quantity={},"
            + " ordType={}, tif={}",
        symbol,
        side,
        level.getPrice(),
        level.getQuantity(),
        level.getOrdType(),
        level.getTif());

    NewOrderRequest request = new NewOrderRequest();
    request.setSymbol(symbol);
    request.setPrice(level.getPrice());
    request.setQuantity(level.getQuantity());
    request.setSide(side);
    request.setOrdType(level.getOrdType());
    request.setTif(level.getTif());

    log.info("Created NewOrderRequest: {}", request);
    return request;
  }

  private void addMarketMakeOrder(String username, String symbol, String orderId) {
    marketMakeOrders
        .computeIfAbsent(username, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet())
        .add(orderId);
  }

  private void removeMarketMakeOrder(String username, String symbol, String orderId) {
    ConcurrentHashMap<String, Set<String>> userOrders = marketMakeOrders.get(username);
    if (userOrders != null) {
      Set<String> symbolOrders = userOrders.get(symbol);
      if (symbolOrders != null) {
        symbolOrders.remove(orderId);
        if (symbolOrders.isEmpty()) {
          userOrders.remove(symbol);
        }
      }
      if (userOrders.isEmpty()) {
        marketMakeOrders.remove(username);
      }
    }
  }

  private Set<String> getMarketMakeOrders(String username, String symbol) {
    return marketMakeOrders
        .getOrDefault(username, new ConcurrentHashMap<>())
        .getOrDefault(symbol, ConcurrentHashMap.newKeySet());
  }

  // テスト用メソッド
  public void clearAllMarketMakeOrders() {
    marketMakeOrders.clear();
    log.info("All market make order tracking data cleared");
  }
}
