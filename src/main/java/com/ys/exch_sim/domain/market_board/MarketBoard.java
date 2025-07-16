package com.ys.exch_sim.domain.market_board;

import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.infra.Pair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MarketBoard {

  // Maintain orders on the board
  Map<Long, LinkedList<Order>> askOrderBoard = new TreeMap<>();
  Map<Long, LinkedList<Order>> bidOrderBoard =
      new TreeMap<>(
          new Comparator<Long>() {
            @Override
            public int compare(Long o1, Long o2) {
              return (int) (o2 - o1);
            }
          });
  // Maintain bid and ask quantity
  Map<Long, Long> askEntryBoard = new TreeMap<>();
  Map<Long, Long> bidEntryBoard =
      new TreeMap<>(
          new Comparator<Long>() {
            @Override
            public int compare(Long o1, Long o2) {
              return (int) (o2 - o1);
            }
          });

  Map<ClOrdID, Order> orderMap = new HashMap<>();

  public synchronized Pair<Long, Long> getAsk(long index) {
    int cnt = 0;
    for (Map.Entry<Long, Long> ent : askEntryBoard.entrySet()) {
      if (cnt == index) {
        return new Pair<>(ent.getKey(), ent.getValue());
      }
      cnt++;
    }
    return new Pair<>(0L, 0L);
  }

  public synchronized Pair<Long, Long> getBid(long index) {
    int cnt = 0;
    for (Map.Entry<Long, Long> ent : bidEntryBoard.entrySet()) {
      if (cnt == index) {
        return new Pair<>(ent.getKey(), ent.getValue());
      }
      cnt++;
    }
    return new Pair<>(0L, 0L);
  }

  private Symbol symbol;

  public MarketBoard(Symbol symbol) {
    this.symbol = symbol;
  }

  private void addOrderToBoard(Order order) {
    Long orderPx = order.getOrderPx().getLongPx();
    Long orderQty = order.getOrderQty().getLongQty();
    if (order.getSide() == Side.BUY) {
      LinkedList<Order> orders = bidOrderBoard.get(orderPx);
      if (orders == null) {
        orders = new LinkedList<Order>();
        bidOrderBoard.put(orderPx, orders);
      }
      orders.add(order);
      orderMap.put(order.getClOrdID(), order);
      Long qty = bidEntryBoard.get(orderPx);
      if (qty == null) {
        qty = 0L;
      }
      qty += orderQty;
      bidEntryBoard.put(orderPx, qty);
    } else {
      LinkedList<Order> orders = askOrderBoard.get(orderPx);
      if (orders == null) {
        orders = new LinkedList<Order>();
        askOrderBoard.put(orderPx, orders);
      }
      orders.add(order);
      orderMap.put(order.getClOrdID(), order);
      Long qty = askEntryBoard.get(orderPx);
      if (qty == null) {
        qty = 0L;
      }
      qty += orderQty;
      askEntryBoard.put(orderPx, qty);
    }
  }

  public synchronized List<Execution> cancelOrder(Order order) {
    List<Execution> executions = new ArrayList<Execution>();
    if (orderMap.get(order.getClOrdID()) == null) {
      Execution e = createReject(order);
      executions.add(e);
      return executions;
    }
    order = orderMap.get(order.getClOrdID());
    if (order.getSide() == Side.BUY) {
      Long currentQty = bidEntryBoard.get(order.getOrderPx().getLongPx());
      if (currentQty == null) {
        log.warn(
            "No quantity found for BUY order at price {} during cancellation, order: {}",
            order.getOrderPx().getLongPx(),
            order.getClOrdID().getId());
        currentQty = 0L;
      }
      long newQty = currentQty - order.getLeavesQty().getLongQty();
      if (newQty < 0) {
        log.warn(
            "Negative quantity detected during BUY order cancellation: {} - {} = {}, resetting to"
                + " 0",
            currentQty,
            order.getLeavesQty().getLongQty(),
            newQty);
        newQty = 0;
      }
      if (newQty <= 0) {
        bidEntryBoard.remove(order.getOrderPx().getLongPx());
      } else {
        bidEntryBoard.put(order.getOrderPx().getLongPx(), newQty);
      }
      LinkedList<Order> orders = bidOrderBoard.get(order.getOrderPx().getLongPx());
      if (orders != null) {
        orders.remove(order);
      }

    } else {
      Long currentQty = askEntryBoard.get(order.getOrderPx().getLongPx());
      if (currentQty == null) {
        log.warn(
            "No quantity found for SELL order at price {} during cancellation, order: {}",
            order.getOrderPx().getLongPx(),
            order.getClOrdID().getId());
        currentQty = 0L;
      }
      long newQty = currentQty - order.getLeavesQty().getLongQty();
      if (newQty < 0) {
        log.warn(
            "Negative quantity detected during SELL order cancellation: {} - {} = {}, resetting to"
                + " 0",
            currentQty,
            order.getLeavesQty().getLongQty(),
            newQty);
        newQty = 0;
      }
      if (newQty <= 0) {
        askEntryBoard.remove(order.getOrderPx().getLongPx());
      } else {
        askEntryBoard.put(order.getOrderPx().getLongPx(), newQty);
      }
      LinkedList<Order> orders = askOrderBoard.get(order.getOrderPx().getLongPx());
      if (orders != null) {
        orders.remove(order);
      }
    }
    Execution e =
        new Execution(order, ExecStatus.CANCELED, order.getOrderPx(), order.getOrderQty());
    executions.add(e);
    return executions;
  }

  public synchronized List<Execution> newOrder(Order order) {
    List<Execution> executions = new ArrayList<Execution>();
    // TODO Duplicate check
    if (orderMap.get(order.getClOrdID()) != null) {
      Execution e = createReject(order);
      executions.add(e);
      return executions;
    }

    if (checkMeetingOrder(order)) {
      return processOrderMatching(order);
    } else {
      if (order.getOrdType() == OrdType.MARKET) {
        Execution e = createReject(order);
        executions.add(e);
        return executions;
      } else {
        if (order.getTif() == Tif.FOK) {
          Execution e = createReject(order);
          executions.add(e);
          return executions;
        } else {
          Execution e = createNew(order);
          executions.add(e);
          // maintain order tree
          addOrderToBoard(order);
          return executions;
        }
      }
    }
  }

  List<Execution> processOrderMatching(Order order) {
    if (order.getOrdType() == OrdType.MARKET) {
      return processMarketOrderMatching(order);
    } else {
      return processLimitOrderMatching(order);
    }
  }

  List<Execution> processMarketOrderMatching(Order order) {

    List<Execution> elist = new ArrayList<Execution>();
    long leavesQty = order.getLeavesQty().getLongQty();
    Map<Long, LinkedList<Order>> board =
        order.getSide() == Side.BUY ? askOrderBoard : bidOrderBoard;
    Map<Long, Long> entryBoard = order.getSide() == Side.BUY ? askEntryBoard : bidEntryBoard;

    for (Entry<Long, LinkedList<Order>> ent : board.entrySet()) {
      Long px = ent.getKey();
      LinkedList<Order> orders = ent.getValue();
      List<Order> removeOrders = new ArrayList<>();
      for (Order counterOrder : orders) {
        long opposingQty = counterOrder.getOrderQty().getLongQty();
        if (leavesQty > opposingQty) { // Partial Fill VS Full Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.PARTIAL_FILL,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty -= opposingQty;
        } else if (leavesQty == opposingQty) { // Full Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty = 0;
        } else if (leavesQty < opposingQty) { // Full Fill VS Partial Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, leavesQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.PARTIAL_FILL,
                  new Px(symbol, px),
                  new Qty(symbol, leavesQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty = 0;
        }
        if (counterOrder.getLeavesQty().getLongQty() == 0L) {
          removeOrders.add(counterOrder);
        }
      }
      // 完全約定した注文を削除し、注文マップからも削除
      for (Order o : removeOrders) {
        orders.remove(o);
        orderMap.remove(o.getClOrdID());
      }
      // 価格レベルに注文が残っていない場合は板情報から削除
      if (orders.isEmpty()) {
        entryBoard.remove(px);
      } else {
        // 価格レベルに注文が残っている場合は、残りの数量を正確に計算
        long totalQty = 0;
        for (Order remainingOrder : orders) {
          totalQty += remainingOrder.getLeavesQty().getLongQty();
        }
        entryBoard.put(px, totalQty);
      }
      // 完全約定したらループ終了
      if (leavesQty == 0) {
        break;
      }
    }

    // TODO : check IOC/FOK status,if orderQty is more than 0, IOC is ok, but if FOK , it is
    // critical error
    return elist;
  }

  List<Execution> processLimitOrderMatching(Order order) {
    List<Execution> elist = new ArrayList<Execution>();
    long leavesQty = order.getLeavesQty().getLongQty();
    long orderPx = order.getOrderPx().getLongPx();
    Side side = order.getSide();
    Map<Long, LinkedList<Order>> board = (side == Side.BUY) ? askOrderBoard : bidOrderBoard;
    for (Entry<Long, LinkedList<Order>> ent : board.entrySet()) {
      Long px = ent.getKey();
      LinkedList<Order> orders = ent.getValue();
      List<Order> removeOrders = new ArrayList<>();
      for (Order counterOrder : orders) {
        // TODO : need to get leavesQty instead of orderQty
        long opposingQty = counterOrder.getLeavesQty().getLongQty();
        long opposingPx = counterOrder.getOrderPx().getLongPx();
        if (leavesQty > opposingQty
            && (side == Side.BUY
                ? opposingPx <= orderPx
                : opposingPx >= orderPx)) { // Partial Fill VS Full Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.PARTIAL_FILL,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty -= opposingQty;
        } else if (leavesQty == opposingQty
            && (side == Side.BUY ? opposingPx <= orderPx : opposingPx >= orderPx)) { // Full Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, opposingQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty = 0;
        } else if (leavesQty < opposingQty
            && (side == Side.BUY
                ? opposingPx <= orderPx
                : opposingPx >= orderPx)) { // Full Fill VS Partial Fill
          Execution e1 =
              createExecutionAndOperateOrder(
                  order,
                  ExecStatus.FILLED,
                  new Px(symbol, px),
                  new Qty(symbol, leavesQty),
                  false,
                  counterOrder.getUsername());
          Execution e2 =
              createExecutionAndOperateOrder(
                  counterOrder,
                  ExecStatus.PARTIAL_FILL,
                  new Px(symbol, px),
                  new Qty(symbol, leavesQty),
                  true,
                  order.getUsername());
          elist.add(e1);
          elist.add(e2);
          leavesQty = 0;
        }
        if (counterOrder.getLeavesQty().getLongQty() == 0L) {
          removeOrders.add(counterOrder);
        }
      }
      for (Order o : removeOrders) {
        orders.remove(o);
      }
    }
    if (order.getLeavesQty().getLongQty() != 0L) {
      addOrderToBoard(order);
    }

    return elist;
  }

  private Execution createExecutionAndOperateOrder(
      Order order,
      ExecStatus execStatus,
      Px lastPx,
      Qty lastQty,
      boolean counterOrder,
      String counterPartyUsername) {

    long newLeavesQty = order.getLeavesQty().getLongQty() - lastQty.getLongQty();
    order.setLeavesQty(new Qty(order.getSymbol(), newLeavesQty));
    Execution e = new Execution(order, execStatus, lastPx, lastQty, counterPartyUsername);
    order.getExecutions().add(e);
    // Entry board quantity update is now handled in the main processing loop
    return e;
  }

  // Backward compatibility method
  private Execution createExecutionAndOperateOrder(
      Order order, ExecStatus execStatus, Px lastPx, Qty lastQty, boolean counterOrder) {
    return createExecutionAndOperateOrder(order, execStatus, lastPx, lastQty, counterOrder, null);
  }

  // Dry Run for checking the matching order existing
  boolean checkMeetingOrder(Order order) {
    if (order.getSide() == Side.BUY) {
      log.debug("checkMeetingOrder: BUY order, askEntryBoard.size()={}, order.getTif()={}, order.getOrdType()={}", 
        askEntryBoard.size(), order.getTif(), order.getOrdType());
      if (askEntryBoard.size() == 0) {
        log.debug("checkMeetingOrder: No ask orders available, returning false");
        return false;
      }
      if (order.getTif() == Tif.IOC && order.getOrdType() == OrdType.MARKET) {
        log.debug("checkMeetingOrder: IOC MARKET order, returning true");
        return true;
      }
      if (order.getTif() == Tif.FOK && order.getOrdType() == OrdType.MARKET) {
        long qty = order.getOrderQty().getLongQty();
        long sum = 0;
        // For BUY market orders, check available quantity on the ASK side
        for (Entry<Long, Long> ent : askEntryBoard.entrySet()) {
          sum += ent.getValue();
          if (sum >= qty) {
            return true;
          }
        }
        return false;
      }
      return checkMeetingAsk(order);
    } else {
      if (bidEntryBoard.size() == 0) {
        return false;
      }
      if (order.getTif() == Tif.IOC && order.getOrdType() == OrdType.MARKET) {
        return true;
      }
      if (order.getTif() == Tif.FOK && order.getOrdType() == OrdType.MARKET) {
        long qty = order.getOrderQty().getLongQty();
        long sum = 0;
        // For SELL market orders, check available quantity on the BID side
        for (Entry<Long, Long> ent : bidEntryBoard.entrySet()) {
          sum += ent.getValue();
          if (sum >= qty) {
            return true;
          }
        }
      }
      return checkMeetingBid(order);
    }
  }

  // Here is only BUY LIMIT order
  private boolean checkMeetingAsk(Order order) {
    // for the performance, use native long instead of Qty class
    long qty = order.getOrderQty().getLongQty();
    long sum = 0;
    for (Entry<Long, Long> ent : askEntryBoard.entrySet()) {
      if (ent.getKey() > order.getOrderPx().getLongPx()) {
        return false;
      } else {
        return true;
      }
    }
    return false;
  }

  // Here is only Limit SELL order
  private boolean checkMeetingBid(Order order) {
    // for the performance, use native long instead of Qty class
    long qty = order.getOrderQty().getLongQty();
    long sum = 0;
    for (Entry<Long, Long> ent : bidEntryBoard.entrySet()) {
      if (ent.getKey() < order.getOrderPx().getLongPx()) {
        return false;
      } else {
        return true;
      }
    }
    return false;
  }

  Execution createReject(Order order) {
    return new Execution(
        order,
        ExecStatus.REJECTED,
        new Px(order.getSymbol(), 0.0),
        new Qty(order.getSymbol(), 0.0));
  }

  Execution createNew(Order order) {
    return new Execution(
        order, ExecStatus.NEW, new Px(order.getSymbol(), 0.0), new Qty(order.getSymbol(), 0.0));
  }

  // Methods for Redis integration to update board directly
  public synchronized void clearBids() {
    bidOrderBoard.clear();
    bidEntryBoard.clear();
  }

  public synchronized void clearAsks() {
    askOrderBoard.clear();
    askEntryBoard.clear();
  }

  public synchronized void setBid(int index, Pair<Long, Long> priceQty) {
    if (priceQty.getLeft() > 0 && priceQty.getRight() > 0) {
      bidEntryBoard.put(priceQty.getLeft(), priceQty.getRight());
    }
  }

  public synchronized void setAsk(int index, Pair<Long, Long> priceQty) {
    if (priceQty.getLeft() > 0 && priceQty.getRight() > 0) {
      askEntryBoard.put(priceQty.getLeft(), priceQty.getRight());
    }
  }

  // Debug methods
  public synchronized int getAskEntryBoardSize() {
    return askEntryBoard.size();
  }

  public synchronized int getBidEntryBoardSize() {
    return bidEntryBoard.size();
  }

  // Method to add market maker orders (for external data sync)
  public synchronized void addMarketMakerOrder(Order order) {
    addOrderToBoard(order);
  }
}
