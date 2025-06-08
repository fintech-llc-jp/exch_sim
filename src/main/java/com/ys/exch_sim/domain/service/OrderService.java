package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import java.time.LocalDateTime;
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

  public OrderResponse processNewOrder(String username, NewOrderRequest request) {
    log.info("Processing new order for user: {} with request: {}", username, request);

    try {
      // 注文の作成
      Order order = createOrder(username, request);

      // MarketBoardを取得または作成
      MarketBoard marketBoard = getOrCreateMarketBoard(request.getSymbol());

      // 注文を処理
      List<Execution> executions = marketBoard.newOrder(order);

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

  private Order createOrder(String username, NewOrderRequest request) {
    // シンボルオブジェクトの作成（価格と数量の精度は固定値）
    Symbol symbol = new Symbol(request.getSymbol(), 100, 1);

    // 各フィールドの作成
    Px px = new Px(symbol, request.getPrice());
    Qty qty = new Qty(symbol, request.getQuantity());
    Side side = Side.valueOf(request.getSide().toUpperCase());
    ClOrdID clOrdID = new ClOrdID(UUID.randomUUID().toString());
    Timestamp timestamp = new Timestamp(LocalDateTime.now());
    OrdType ordType = OrdType.valueOf(request.getOrdType().toUpperCase());
    Tif tif = Tif.valueOf(request.getTif().toUpperCase());

    return new Order(symbol, px, qty, side, clOrdID, timestamp, ordType, tif);
  }

  private MarketBoard getOrCreateMarketBoard(String symbolName) {
    return marketBoards.computeIfAbsent(
        symbolName,
        k -> {
          Symbol symbol = new Symbol(symbolName, 100, 1);
          log.info("Creating new MarketBoard for symbol: {}", symbolName);
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
}
