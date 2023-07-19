package com.ys.exch_sim.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Session;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionCallback;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.domain.message.field.Timestamp;

public class OrderManagerTest implements ExecutionCallback{
    @Test
    void testNewOrder() {
        Symbol symbol = new Symbol("BTCJPY",100,1);
        OrderManager om = new OrderManager(); 
       om.addMarketBoard(symbol);
       Session session = new Session("session1");
       om.addSession(session);

        Order ask1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,5), 
            Side.SELL, new ClOrdID("id-ask1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        session.setExecutionCallback(this);
        ask1.setSession(session);
        om.newOrder(ask1);
        om.cancelOrder(ask1);

    }

    @Override
    public void setExecutionCallback(ExecutionCallback callback) {
    }

    private int count = 0;
    @Override
    public void onExecutionReport(Execution execution) {
        if(count == 0) {
            assertEquals(execution.getExecStatus(),ExecStatus.NEW);
        }
        else if(count == 2) {
            assertEquals(execution.getExecStatus(), ExecStatus.CANCELED);

        }

        count++;

    }
}
