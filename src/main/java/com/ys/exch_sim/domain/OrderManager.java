package com.ys.exch_sim.domain;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.Session;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;

public class OrderManager {

    ConcurrentHashMap<Symbol, MarketBoard> marketBoards = new ConcurrentHashMap<>();
    public boolean addMarketBoard(Symbol symbol) {
        if(marketBoards.containsKey(symbol)) {
            return false;
        }
        MarketBoard mb = new MarketBoard(symbol);
        marketBoards.put(symbol,mb);
        return true;
    }
    
    ConcurrentHashMap<String,Session> sessions = new ConcurrentHashMap<>();
    public boolean addSession(Session session) {
        if(sessions.containsKey(session.getId())) {
            return false;
        }
        sessions.put(session.getId(), session);
        return true;
    }

    synchronized public boolean newOrder(Order newOrder) {
        if(newOrder.getSession() == null) {
            return false;
        }
        MarketBoard mb = marketBoards.get(newOrder.getSymbol());
        if(mb == null) {
            return false;
        }
        List<Execution> executions = mb.newOrder(newOrder);
        for(Execution execution : executions) {
            Session session = sessions.get(execution.getOrder().getSession().getId());
            session.onExecutionReport(execution);
        }
        return true;
    }
    synchronized public void cancelOrder(Order order) {
        MarketBoard mb = marketBoards.get(order.getSymbol());
        List<Execution> executions = mb.cancelOrder(order);
        for(Execution execution : executions) {
            Session session = sessions.get(execution.getOrder().getSession().getId());
            session.onExecutionReport(execution);
        }
    }

}
