package com.ys.exch_sim.domain.market_board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

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

    @Test
    void testMarketBoard() { 
        Symbol symbol = new Symbol("BTCJPY",1,100);
        MarketBoard mb = new MarketBoard(symbol);

        Order buy1 = new Order(symbol,new Px(symbol,100), new Qty(symbol,0.1), 
            Side.BUY, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order buy2 = new Order(symbol,new Px(symbol,99), new Qty(symbol,0.2), 
            Side.BUY, new ClOrdID("id-buy2") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order buy3 = new Order(symbol,new Px(symbol,98), new Qty(symbol,0.3), 
            Side.BUY, new ClOrdID("id-buy3") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell1 = new Order(symbol,new Px(symbol,101), new Qty(symbol,0.1), 
            Side.SELL, new ClOrdID("id-sell1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell2 = new Order(symbol,new Px(symbol,102), new Qty(symbol,0.2), 
            Side.SELL, new ClOrdID("id-sell2") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order sell3 = new Order(symbol,new Px(symbol,103), new Qty(symbol,0.3), 
            Side.SELL, new ClOrdID("id-sell3") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        List<Execution> elist1 = mb.newOrder(buy1);
        List<Execution> elist2 = mb.newOrder(buy2);
        List<Execution> elist3 = mb.newOrder(buy3);
        List<Execution> elist4 = mb.newOrder(sell1);
        List<Execution> elist5 = mb.newOrder(sell2);
        List<Execution> elist6 = mb.newOrder(sell3);
        Pair<Long,Long> ask1 = mb.getAsk(0);
        Pair<Long,Long> bid1 = mb.getBid(0);
        Pair<Long,Long> ask2 = mb.getAsk(1);
        Pair<Long,Long> bid2 = mb.getBid(1);
        Pair<Long,Long> ask3 = mb.getAsk(2);
        Pair<Long,Long> bid3 = mb.getBid(2);
        assertEquals(ask1.getLeft(), 101L);
        assertEquals(ask1.getRight(), 10L);
        assertEquals(ask2.getLeft(), 102L);
        assertEquals(ask2.getRight(), 20L);
        assertEquals(ask3.getLeft(), 103L);
        assertEquals(ask3.getRight(), 30L);
        assertEquals(bid1.getLeft(), 100L);
        assertEquals(bid1.getRight(), 10L);
        assertEquals(bid2.getLeft(), 99L);
        assertEquals(bid2.getRight(), 20L);
        assertEquals(bid3.getLeft(), 98L);
        assertEquals(bid3.getRight(), 30L);



        Order buym = new Order(symbol,null, new Qty(symbol,0.2), 
            Side.BUY, new ClOrdID("id-buym") , new Timestamp(LocalDateTime.now()) , 
            OrdType.MARKET, Tif.IOC);
        List<Execution> elist7 = mb.newOrder(buym);
        Pair<Long,Long> askm = mb.getAsk(0);
        System.out.println(askm);
        assertEquals(askm.getLeft(), 102L);
        assertEquals(askm.getRight(), 10L);


        Order sell4 = new Order(symbol,new Px(symbol,102), new Qty(symbol,0.3), 
            Side.SELL, new ClOrdID("id-sell4") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        List<Execution> elist8 = mb.newOrder(sell4);
        Pair<Long,Long> ask4 = mb.getAsk(0);
        assertEquals(ask4.getLeft(), 102L);
        assertEquals(ask4.getRight(), 40L);

    }

    @Test
    void cancelOrderTest() {
        Symbol symbol = new Symbol("BTCJPY",1,100);
        MarketBoard mb = new MarketBoard(symbol);

        Order buy1 = new Order(symbol,new Px(symbol,100), new Qty(symbol,0.1), 
            Side.BUY, new ClOrdID("id-buy1") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order buy2 = new Order(symbol,new Px(symbol,100), new Qty(symbol,0.2), 
            Side.BUY, new ClOrdID("id-buy2") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        Order buy3 = new Order(symbol,new Px(symbol,100), new Qty(symbol,0.3), 
            Side.BUY, new ClOrdID("id-buy3") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        List<Execution> e1 = mb.newOrder(buy1);
        List<Execution> e2 = mb.newOrder(buy2);
        List<Execution> e3 = mb.newOrder(buy3);

        List<Execution> e4 = mb.cancelOrder(buy2);
        Pair<Long,Long> ask1 = mb.getAsk(0); 
        Pair<Long,Long> bid1 = mb.getBid(0); 

    }
}
