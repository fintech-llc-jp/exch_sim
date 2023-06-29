package com.ys.exch_sim.domain.market_board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Order;

public class MarketBoardTest {


    @Test
    void testFindMatchingOrder() {

        Symbol symbol = new Symbol("BTCJPY",100,1);
        MarketBoard mb = new MarketBoard(symbol);
        mb.askEntryBoard.put(100L,100L);

        Order buy1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,1.0), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        boolean ret1 = mb.checkMeetingOrder(buy1);
        assertEquals(ret1,true);


        Order buy2 = new Order(symbol,new Px(symbol,0.9), new Qty(symbol,1.0), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret2 = mb.checkMeetingOrder(buy2);
        assertEquals(ret2,false);

        Order buy3 = new Order(symbol,new Px(symbol,1.1), new Qty(symbol,1.0), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret3 = mb.checkMeetingOrder(buy3);
        assertEquals(ret3,true);

        mb.askEntryBoard.remove(100L);
        mb.bidEntryBoard.put(100L,100L);

        Order sell1 = new Order(symbol,new Px(symbol,1.0), new Qty(symbol,1.0), 
            Side.SELL, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);

        boolean ret4 = mb.checkMeetingOrder(sell1);
        assertEquals(ret4,true);

         Order sell2 = new Order(symbol,new Px(symbol,0.9), new Qty(symbol,1.0), 
            Side.SELL, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret5 = mb.checkMeetingOrder(sell2);
        assertEquals(ret5,true);

        Order sell3 = new Order(symbol,new Px(symbol,1.1), new Qty(symbol,1.0), 
            Side.BUY, new ClOrdID("id") , new Timestamp(LocalDateTime.now()) , 
            OrdType.LIMIT, Tif.DAY);
        boolean ret6 = mb.checkMeetingOrder(sell3);
        assertEquals(ret6,false);
    }
}
