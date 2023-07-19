package com.ys.exch_sim.domain.market_board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

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
import com.google.common.collect.Iterators;

public class MarketBoard {

    // Maintain orders on the board
    Map<Long, LinkedList<Order>> askOrderBoard = new TreeMap<>();
    Map<Long, LinkedList<Order>> bidOrderBoard = new TreeMap<>(new Comparator<Long>() {
        @Override
        public int compare(Long o1, Long o2) {
            return (int) (o2 - o1);
        }
    });
    // Maintain bid and ask quantity
    Map<Long, Long> askEntryBoard = new TreeMap<>();
    Map<Long, Long> bidEntryBoard = new TreeMap<>(new Comparator<Long>() {
        @Override
        public int compare(Long o1, Long o2) {
            return (int) (o2 - o1);
        }
    });

    Map<ClOrdID, Order> orderMap = new HashMap<>();

    public Pair<Long,Long> getAsk(long index) {
        int cnt = 0;
        for(Map.Entry<Long,Long> ent : askEntryBoard.entrySet()) {
            if(cnt == index) {
                return new Pair<> ( ent.getKey(), ent.getValue());
            }
            cnt++;
        }
        return new Pair<>(0L,0L);
    }

    public Pair<Long,Long> getBid(long index) {
        int cnt = 0;
        for(Map.Entry<Long,Long> ent : bidEntryBoard.entrySet()) {
            if(cnt == index) {
                return new Pair<> ( ent.getKey(), ent.getValue());
            }
            cnt++;
        }
        return new Pair<>(0L,0L);
    }


    private Symbol symbol;

    public MarketBoard(Symbol symbol) {
        this.symbol = symbol;
    }

    void addOrderToBoard(Order order) {
        Long orderPx = order.getOrderPx().getLongPx();
        Long orderQty = order.getOrderQty().getLongQty();
        if(order.getSide() == Side.BUY) {
            LinkedList<Order> orders = bidOrderBoard.get(orderPx);
            if(orders == null) {
                orders = new LinkedList<Order>();
                bidOrderBoard.put(orderPx,orders);
            }
            orders.add(order);
            orderMap.put(order.getClOrdID(), order);
            Long qty = bidEntryBoard.get(orderPx);
            if(qty == null) {
                qty = 0L;
            }
            qty += orderQty;
            bidEntryBoard.put(orderPx,qty);
        } else {
            LinkedList<Order> orders = askOrderBoard.get(orderPx);
            if(orders == null) {
                orders = new LinkedList<Order>();
                askOrderBoard.put(orderPx,orders);
            }
            orders.add(order);
            orderMap.put(order.getClOrdID(), order);
            Long qty = askEntryBoard.get(orderPx);
            if(qty == null) {
                qty = 0L;
            }
            qty += orderQty;
            askEntryBoard.put(orderPx,qty);
        }
    }

    public List<Execution> cancelOrder(Order order) {
        List<Execution> executions = new ArrayList<Execution>();
        if(orderMap.get(order.getClOrdID()) == null) {
            Execution e = createReject(order);
            executions.add(e);
            return executions;
        }
        order = orderMap.get(order.getClOrdID());
        if(order.getSide() == Side.BUY) {
            long qty = bidEntryBoard.get(order.getOrderPx().getLongPx());
            qty -= order.getOrderQty().getLongQty(); 
            if(qty == 0) {
                bidEntryBoard.remove(order.getOrderPx().getLongPx());
            } else {
                bidEntryBoard.put(order.getOrderPx().getLongPx(), qty);
            }
            LinkedList<Order> orders = bidOrderBoard.get(order.getOrderPx().getLongPx());
            orders.remove(order);

        } else {
            long qty = askEntryBoard.get(order.getOrderPx().getLongPx());
            qty -= order.getOrderQty().getLongQty();
            if(qty == 0) {
                askEntryBoard.remove(order.getOrderPx().getLongPx());
            } else {
                askEntryBoard.put(order.getOrderPx().getLongPx(), qty);
            }
            LinkedList<Order> orders = askOrderBoard.get(order.getOrderPx().getLongPx());
            orders.remove(order);
        }
        Execution e = new Execution(order,ExecStatus.CANCELED, order.getOrderPx(), order.getOrderQty());
        executions.add(e);
        return executions;
    }

    public List<Execution> newOrder(Order order) {
        List<Execution> executions = new ArrayList<Execution>();
        // TODO Duplicate check
        if(orderMap.get(order.getClOrdID()) != null) {
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
            } else  {
                if(order.getTif() == Tif.FOK) {
                    Execution e = createReject(order) ;
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
        if(order.getOrdType() == OrdType.MARKET) {
            return processMarketOrderMatching(order);
        } else {
            return processLimitOrderMatching(order);
        }
    }

    List<Execution> processMarketOrderMatching(Order order) {

        List<Execution> elist = new ArrayList<Execution>();
        long leavesQty = order.getLeavesQty().getLongQty();
        Map<Long, LinkedList<Order>> board = order.getSide() == Side.BUY ? askOrderBoard : bidOrderBoard; 
        for (Entry<Long, LinkedList<Order>> ent : board.entrySet()) {
            Long px = ent.getKey();
            LinkedList<Order> orders = ent.getValue();
            List<Order> removeOrders = new ArrayList<>();
            for(Order counterOrder: orders) {
                long opposingQty = counterOrder.getOrderQty().getLongQty();
                if(leavesQty  > opposingQty ) { // Partial Fill VS Full Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.PARTIAL_FILL, new Px(symbol,px), new Qty(symbol,opposingQty),false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty), true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty -= opposingQty;
                } else if( leavesQty == opposingQty ) { // Full Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty), false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty), true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty = 0;
                } else if( leavesQty < opposingQty ) { // Full Fill VS Partial Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,leavesQty), false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.PARTIAL_FILL, new Px(symbol,px), new Qty(symbol,leavesQty), true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty = 0;
                }
                if(counterOrder.getLeavesQty().getLongQty() == 0L) {
                    removeOrders.add(counterOrder);
                }
            }
            for(Order o : removeOrders) {
                orders.remove(o);
            }
        }

        // TODO : check IOC/FOK status,if orderQty is more than 0, IOC is ok, but if FOK , it is critical error
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
            for(Order counterOrder: orders) {
                // TODO : need to get leavesQty instead of orderQty
                long opposingQty = counterOrder.getLeavesQty().getLongQty();
                long opposingPx = counterOrder.getOrderPx().getLongPx();
                if(leavesQty  > opposingQty && 
                    (side == Side.BUY ? opposingPx <= orderPx : opposingPx >= orderPx)  ) { // Partial Fill VS Full Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.PARTIAL_FILL, new Px(symbol,px), new Qty(symbol,opposingQty), false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty), true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty -= opposingQty;
                } else if( leavesQty == opposingQty && 
                    (side == Side.BUY ? opposingPx <= orderPx : opposingPx >= orderPx)) { // Full Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty),false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,opposingQty),true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty = 0;
                } else if( leavesQty < opposingQty && 
                    (side == Side.BUY ? opposingPx <= orderPx : opposingPx >= orderPx)) { // Full Fill VS Partial Fill
                    Execution e1 = createExecutionAndOperateOrder(order, ExecStatus.FILLED, new Px(symbol,px), new Qty(symbol,leavesQty),false);
                    Execution e2 = createExecutionAndOperateOrder(counterOrder, ExecStatus.PARTIAL_FILL, new Px(symbol,px), new Qty(symbol,leavesQty),true);
                    elist.add(e1);
                    elist.add(e2);
                    leavesQty = 0;
                }
                if(counterOrder.getLeavesQty().getLongQty() == 0L) {
                    removeOrders.add(counterOrder);
                }
            }
            for(Order o : removeOrders) {
                orders.remove(o);
            }
        }
        if(order.getLeavesQty().getLongQty() != 0L) {
            addOrderToBoard(order);
        }

        return elist;
    }


    private Execution createExecutionAndOperateOrder(Order order,ExecStatus execStatus, Px lastPx, Qty lastQty, boolean counterOrder){
        
        long newLeavesQty = order.getLeavesQty().getLongQty() -lastQty.getLongQty();
        order.setLeavesQty(new Qty(order.getSymbol(), newLeavesQty));
        Execution e =  new Execution(order, execStatus,lastPx,lastQty); 
        order.getExecutions().add(e);
        if(counterOrder) {
           Map<Long,Long> entryBoard = order.getSide() == Side.BUY ? bidEntryBoard : askEntryBoard; 
           long qty = entryBoard.get(lastPx.getLongPx()) - lastQty.getLongQty();
           if(qty != 0L) {
            entryBoard.put(lastPx.getLongPx(), qty);
           } else {
            entryBoard.remove(lastPx.getLongPx());
           }
        }
        return e; 
    }

    // Dry Run for checking the matching order existing
    boolean checkMeetingOrder(Order order) {
        if (order.getSide() == Side.BUY) {
            if (askEntryBoard.size() == 0) {
                return false;
            }
            if (order.getTif() == Tif.IOC && order.getOrdType() == OrdType.MARKET) {
                return true;
            }
            if (order.getTif() == Tif.FOK && order.getOrdType() == OrdType.MARKET) {
                long qty = order.getOrderQty().getLongQty();
                long sum = 0;
                for (Entry<Long, Long> ent : bidEntryBoard.entrySet()) {
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
                for (Entry<Long, Long> ent : askEntryBoard.entrySet()) {
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
        return new Execution(order, ExecStatus.REJECTED, 
                    new Px(order.getSymbol(), 0.0), new Qty(order.getSymbol(), 0.0));
    }

    Execution createNew(Order order) {
        return new Execution(order, ExecStatus.NEW, 
                    new Px(order.getSymbol(), 0.0), new Qty(order.getSymbol(), 0.0));

    }
}
