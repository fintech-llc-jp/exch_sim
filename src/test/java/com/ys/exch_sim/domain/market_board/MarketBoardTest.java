package com.ys.exch_sim.domain.market_board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.OrdStatus;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.infra.Pair;

public class MarketBoardTest {


    @Test
    void testFindMatchingOrder() {

        Symbol symbol = new Symbol("BTCJPY",100,1);
        MarketBoard mb = new MarketBoard(symbol);
        mb.askEntryBoard.put(100L,100L);

        Order buy1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,10), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        boolean ret1 = mb.checkMeetingOrder(buy1);
        assertEquals(ret1,true);


        Order buy2 = new Order(symbol,new Px(symbol,0.9), new Qty(symbol,10), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret2 = mb.checkMeetingOrder(buy2);
        assertEquals(ret2,false);

        Order buy3 = new Order(symbol,new Px(symbol,1.1), new Qty(symbol,10), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret3 = mb.checkMeetingOrder(buy3);
        assertEquals(ret3,true);

        mb.askEntryBoard.remove(100L);
        mb.bidEntryBoard.put(100L,100L);

        Order sell1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,10), 
            Side.SELL, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        boolean ret4 = mb.checkMeetingOrder(sell1);
        assertEquals(ret4,true);

         Order sell2 = new Order(symbol,new Px(symbol,0.9), new Qty(symbol,10), 
            Side.SELL, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret5 = mb.checkMeetingOrder(sell2);
        assertEquals(ret5,true);

        Order sell3 = new Order(symbol,new Px(symbol,1.1), new Qty(symbol,10), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret6 = mb.checkMeetingOrder(sell3);
        assertEquals(ret6,false);
    }

    @Test
    void testProcessMarketBuyOrderMatching() {

        Symbol symbol = new Symbol("BTCJPY",100,1);
        MarketBoard mb = new MarketBoard(symbol);

        Order ask1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,5), 
            Side.SELL, new ClOrdID("id-ask1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order buy1 = new Order(symbol,null, new Qty(symbol,5), 
            Side.BUY, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.MARKET, Tif.IOC);

        List<Execution> elist1 = mb.newOrder(ask1);
        List<Execution> elist2 = mb.newOrder(buy1);
        
        System.out.println(elist1);
        System.out.println(elist2);
        assertEquals(elist1.size(),1);
        assertEquals(elist1.get(0).getExecStatus(),ExecStatus.NEW);;
        assertEquals(elist2.size(),2);
        Execution e1 = elist2.get(0);
        Execution e2 = elist2.get(1);
        assertEquals(e1.getExecStatus(),ExecStatus.FILLED);
        assertEquals(e2.getExecStatus(),ExecStatus.FILLED);
    }
     @Test
    void testProcessMarketSellOrderMatching() {

        Symbol symbol = new Symbol("BTCJPY",100,1);
        MarketBoard mb = new MarketBoard(symbol);

        Order buy1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,5), 
            Side.BUY, new ClOrdID("id-ask1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell1 = new Order(symbol,null, new Qty(symbol,4), 
            Side.SELL, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.MARKET, Tif.IOC);

        List<Execution> elist1 = mb.newOrder(buy1);
        List<Execution> elist2 = mb.newOrder(sell1);
        
        assertEquals(elist1.size(),1);
        assertEquals(elist1.get(0).getExecStatus(),ExecStatus.NEW);;
        assertEquals(elist2.size(),2);
        Execution e1 = elist2.get(0);
        Execution e2 = elist2.get(1);
        assertEquals(e1.getExecStatus(),ExecStatus.FILLED);
        assertEquals(e2.getExecStatus(),ExecStatus.PARTIAL_FILL);
    }

     @Test
    void testProcessLimitOrderMatching1() {

        Symbol symbol = new Symbol("BTCJPY",100,1);
        MarketBoard mb = new MarketBoard(symbol);

        Order buy1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,5), 
            Side.BUY, new ClOrdID("id-ask1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,4), 
            Side.SELL, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.IOC);

        List<Execution> elist1 = mb.newOrder(buy1);
        List<Execution> elist2 = mb.newOrder(sell1);
        
        assertEquals(elist1.size(),1);
        assertEquals(elist1.get(0).getExecStatus(),ExecStatus.NEW);;
        assertEquals(elist2.size(),2);
        Execution e1 = elist2.get(0);
        Execution e2 = elist2.get(1);
        assertEquals(e1.getExecStatus(),ExecStatus.FILLED);
        assertEquals(e2.getExecStatus(),ExecStatus.PARTIAL_FILL);
    }

    @Test
    void testProcessLimitOrderMatching2() {

        Symbol symbol = new Symbol("BTCJPY",1,100);
        MarketBoard mb = new MarketBoard(symbol);

        Order buy1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,5), 
            Side.BUY, new ClOrdID("id-ask1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell1 = new Order(symbol,new Px(symbol,1.1), new Qty(symbol,4), 
            Side.SELL, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        List<Execution> elist1 = mb.newOrder(buy1);
        Pair<Long,Long> bids1 = mb.getBid(0);
        List<Execution> elist2 = mb.newOrder(sell1);
        Pair<Long,Long> bids2 = mb.getBid(0);
        System.out.println(bids1);
        System.out.println(bids2);
        
        assertEquals(elist1.size(),1);
        assertEquals(elist1.get(0).getExecStatus(),ExecStatus.NEW);;
        assertEquals(elist2.size(),2);

        Execution e1 = elist2.get(0);
        Order sell2 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,6), 
            Side.SELL, new ClOrdID("id-sell2") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        List<Execution> elist3 = mb.newOrder(sell2);
        assertEquals(elist2.size(),2);

    }
}
