package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.CancelOrderRequest;
import com.ys.exch_sim.domain.dto.MarketBoardResponse;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.infra.Pair;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderService {

  // シンボルごとのMarketBoardを管理
  private final ConcurrentHashMap<String, MarketBoard> marketBoards = new ConcurrentHashMap<>();

  // 注文IDからOrderへのマッピングを管理（キャンセル用）
  private final ConcurrentHashMap<String, Order> orderMap = new ConcurrentHashMap<>();

  // 約定結果キューサービス
  private final ExecutionQueueService executionQueueService;

  // 商品設定
  private final InstrumentConfig instrumentConfig;

  // ポジション管理
  private final PositionManager positionManager;

  public OrderService(
      ExecutionQueueService executionQueueService,
      InstrumentConfig instrumentConfig,
      PositionManager positionManager) {
    this.executionQueueService = executionQueueService;
    this.instrumentConfig = instrumentConfig;
    this.positionManager = positionManager;
  }

  public OrderResponse processNewOrder(String username, NewOrderRequest request) {
    log.info("Processing new order for user: {} with request: {}", username, request);

    try {
      // 商品の存在チェック
      if (!instrumentConfig.isValidSymbol(request.getSymbol())) {
        log.warn("Invalid symbol: {}", request.getSymbol());
        throw new RuntimeException("Invalid symbol: " + request.getSymbol());
      }

      // Cash商品の空売りチェック
      if (request.getIsMarketMake() == null || !request.getIsMarketMake()) {
        InstrumentConfig.InstrumentDefinition instrument =
            instrumentConfig.getInstrument(request.getSymbol());
        if (instrument.isCash() && "SELL".equalsIgnoreCase(request.getSide())) {
          // Cash商品の場合、売り注文前に十分なポジションがあるかチェック
          Position currentPosition = positionManager.getPosition(username, request.getSymbol());
          long availableQty = currentPosition != null ? currentPosition.getNetQty() : 0L;

          if (availableQty < request.getQuantity()) {
            log.warn(
                "Insufficient position for cash sale. User: {}, Symbol: {}, Available: {},"
                    + " Requested: {}",
                username,
                request.getSymbol(),
                availableQty,
                request.getQuantity());
            throw new RuntimeException(
                "Insufficient position for cash sale. Available: "
                    + availableQty
                    + ", Requested: "
                    + request.getQuantity());
          }
        }
      }

      // 注文の作成
      Order order = createOrder(username, request);

      // MarketBoardを取得または作成
      MarketBoard marketBoard = getOrCreateMarketBoard(request.getSymbol());

      // 注文を処理
      List<Execution> executions = marketBoard.newOrder(order);

      // 約定結果をキューに追加（相手方ユーザーにも通知するため）
      processExecutionsForQueue(executions);

      // 注文をマップに保存（キャンセル用）
      orderMap.put(order.getClOrdID().getId(), order);

      // レスポンスを作成
      OrderResponse response = convertToResponse(order, executions);

      log.info(
          "Order processed successfully for user: {} with clOrdID: {}",
          username,
          order.getClOrdID().getId());
      return response;

    } catch (Exception e) {
      log.error("Error processing order for user: " + username, e);
      throw new RuntimeException("Error processing order: " + e.getMessage());
    }
  }

  public OrderResponse cancelOrder(String username, CancelOrderRequest request) {
    log.info("Processing cancel order for user: {} with request: {}", username, request);

    try {
      // 商品の存在チェック
      if (!instrumentConfig.isValidSymbol(request.getSymbol())) {
        log.warn("Invalid symbol: {}", request.getSymbol());
        throw new RuntimeException("Invalid symbol: " + request.getSymbol());
      }
      // 注文を検索
      Order order = orderMap.get(request.getClOrdID());
      if (order == null) {
        log.warn("Order not found for clOrdID: {}", request.getClOrdID());
        throw new RuntimeException("Order not found: " + request.getClOrdID());
      }

      // シンボルの確認
      if (!order.getSymbol().getName().equals(request.getSymbol())) {
        log.warn(
            "Symbol mismatch for clOrdID: {} expected: {} actual: {}",
            request.getClOrdID(),
            request.getSymbol(),
            order.getSymbol().getName());
        throw new RuntimeException("Symbol mismatch for order: " + request.getClOrdID());
      }

      // MarketBoardを取得
      MarketBoard marketBoard = marketBoards.get(request.getSymbol());
      if (marketBoard == null) {
        log.warn("MarketBoard not found for symbol: {}", request.getSymbol());
        throw new RuntimeException("MarketBoard not found for symbol: " + request.getSymbol());
      }

      // 注文をキャンセル
      List<Execution> executions = marketBoard.cancelOrder(order);

      // 約定結果をキューに追加
      processExecutionsForQueue(executions);

      // 注文をマップから削除
      orderMap.remove(request.getClOrdID());

      // レスポンスを作成
      OrderResponse response = convertToResponse(order, executions);

      log.info(
          "Order cancelled successfully for user: {} with clOrdID: {}",
          username,
          order.getClOrdID().getId());
      return response;

    } catch (Exception e) {
      log.error("Error cancelling order for user: " + username, e);
      throw new RuntimeException("Error cancelling order: " + e.getMessage());
    }
  }

  private Order createOrder(String username, NewOrderRequest request) {
    // 商品設定から精度情報を取得
    InstrumentConfig.InstrumentDefinition instrument =
        instrumentConfig.getInstrument(request.getSymbol());

    log.info(
        "Creating order for user: {} symbol: {} with instrument config: priceMultiplier={},"
            + " qtyMultiplier={}",
        username,
        request.getSymbol(),
        instrument.getPriceMultiplier(),
        instrument.getQtyMultiplier());
    log.info(
        "Raw request values: price={}, quantity={}", request.getPrice(), request.getQuantity());

    // シンボルオブジェクトの作成（設定値から精度を取得）
    Symbol symbol =
        new Symbol(
            request.getSymbol().toUpperCase(),
            instrument.getPriceMultiplier(),
            instrument.getQtyMultiplier());

    // 各フィールドの作成
    Px px = new Px(symbol, request.getPrice());
    Qty qty = new Qty(symbol, request.getQuantity());

    log.info(
        "Calculated values: px.getLongPx()={}, qty.getLongQty()={}",
        px.getLongPx(),
        qty.getLongQty());
    log.info(
        "Price calculation: {} * {} = {}",
        request.getPrice(),
        instrument.getPriceMultiplier(),
        px.getLongPx());
    log.info(
        "Quantity calculation: {} * {} = {}",
        request.getQuantity(),
        instrument.getQtyMultiplier(),
        qty.getLongQty());

    Side side = Side.valueOf(request.getSide().toUpperCase());
    ClOrdID clOrdID = new ClOrdID(UUID.randomUUID().toString());
    Timestamp timestamp = new Timestamp(LocalDateTime.now());
    OrdType ordType = OrdType.valueOf(request.getOrdType().toUpperCase());
    Tif tif = Tif.valueOf(request.getTif().toUpperCase());

    return new Order(symbol, px, qty, side, clOrdID, timestamp, ordType, tif, username);
  }

  private MarketBoard getOrCreateMarketBoard(String symbolName) {
    return marketBoards.computeIfAbsent(
        symbolName.toUpperCase(),
        k -> {
          InstrumentConfig.InstrumentDefinition instrument =
              instrumentConfig.getInstrument(symbolName);
          Symbol symbol =
              new Symbol(k, instrument.getPriceMultiplier(), instrument.getQtyMultiplier());
          log.info("Creating new MarketBoard for symbol: {}", k);
          return new MarketBoard(symbol);
        });
  }

  private OrderResponse convertToResponse(Order order, List<Execution> executions) {
    OrderResponse response = new OrderResponse();
    response.setClOrdID(order.getClOrdID().getId());

    // 自分の注文に関連するExecutionのみをフィルタリング
    List<Execution> myExecutions =
        executions.stream()
            .filter(exec -> exec.getOrder().getClOrdID().getId().equals(order.getClOrdID().getId()))
            .collect(Collectors.toList());

    // ステータスを決定（最新のExecutionのステータスを使用）
    String status = "NEW";
    if (!myExecutions.isEmpty()) {
      Execution lastExecution = myExecutions.get(myExecutions.size() - 1);
      status = lastExecution.getExecStatus().toString();
    }
    response.setStatus(status);

    List<OrderResponse.ExecutionDto> executionDtos =
        myExecutions.stream()
            .map(
                exec ->
                    new OrderResponse.ExecutionDto(
                        UUID.randomUUID().toString(),
                        exec.getExecStatus().toString(),
                        getPxValue(exec.getLastPx()),
                        exec.getLastQty().getLongQty()))
            .collect(Collectors.toList());

    response.setExecutions(executionDtos);
    return response;
  }

  private Double getPxValue(Px px) {
    // Symbolから精度情報を取得して実際の価格に変換
    return (double) px.getLongPx() / px.getSymbol().getPxMultiplier();
  }

  private void processExecutionsForQueue(List<Execution> executions) {
    for (Execution execution : executions) {
      String username = execution.getOrder().getUsername();
      if (username != null) {
        executionQueueService.addExecution(username, execution);

        if (execution.getExecStatus() == ExecStatus.PARTIAL_FILL
            || execution.getExecStatus() == ExecStatus.FILLED) {
          positionManager.processExecution(execution);
        }
      }
    }
  }

  public MarketBoardResponse getMarketBoard(String symbolName, int depth) {
    log.info("Getting market board for symbol: {} with depth: {}", symbolName, depth);

    // 商品の存在チェック
    if (!instrumentConfig.isValidSymbol(symbolName)) {
      log.warn("Invalid symbol: {}", symbolName);
      throw new RuntimeException("Invalid symbol: " + symbolName);
    }

    MarketBoard marketBoard = marketBoards.get(symbolName.toUpperCase());
    if (marketBoard == null) {
      log.warn("MarketBoard not found for symbol: {}", symbolName);
      // 空の板情報を返す
      return new MarketBoardResponse(symbolName, new ArrayList<>(), new ArrayList<>());
    }

    // 指定された深度まで板情報を取得
    List<MarketBoardResponse.PriceLevel> bids = new ArrayList<>();
    List<MarketBoardResponse.PriceLevel> asks = new ArrayList<>();

    // 商品設定から精度情報を取得
    InstrumentConfig.InstrumentDefinition instrument = instrumentConfig.getInstrument(symbolName);

    // ビッド（買い注文）を取得
    for (int i = 0; i < depth; i++) {
      Pair<Long, Long> bid = marketBoard.getBid(i);
      if (bid.getLeft() != 0L && bid.getRight() != 0L) {
        double price = (double) bid.getLeft() / instrument.getPriceMultiplier();
        double quantity = (double) bid.getRight() / instrument.getQtyMultiplier();
        bids.add(new MarketBoardResponse.PriceLevel(price, quantity));
      } else {
        break; // これ以上の板情報がない場合は終了
      }
    }

    // アスク（売り注文）を取得
    for (int i = 0; i < depth; i++) {
      Pair<Long, Long> ask = marketBoard.getAsk(i);
      if (ask.getLeft() != 0L && ask.getRight() != 0L) {
        double price = (double) ask.getLeft() / instrument.getPriceMultiplier();
        double quantity = (double) ask.getRight() / instrument.getQtyMultiplier();
        asks.add(new MarketBoardResponse.PriceLevel(price, quantity));
      } else {
        break; // これ以上の板情報がない場合は終了
      }
    }

    log.info(
        "Retrieved market board for {}: {} bids, {} asks", symbolName, bids.size(), asks.size());
    return new MarketBoardResponse(symbolName, bids, asks);
  }
}
