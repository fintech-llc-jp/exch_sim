package com.ys.exch_sim.domain.order_exec;

import java.util.ArrayList;
import java.util.List;

import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;

import lombok.Data;

@Data
public class Order {
    private final Symbol symbol;
    private final Px  orderPx;
    private final Qty orderQty;
    private final Side side;
    private final ClOrdID clOrdID;
    private final Timestamp ts;
    private final OrdType ordType;
    private final Tif tif;
    private OrdStatus ordStatus;
    private Qty leavesQty;
    //private Order next;
    private List<Execution> executions = new ArrayList<>(); 

    public Order(Symbol symbol, Px px, Qty qty, Side side, ClOrdID clOrdID, Timestamp ts, OrdType ordType, Tif tif) {
        this.symbol = symbol;
        this.orderPx = px;
        this.orderQty = qty;
        this.side = side;
        this.clOrdID = clOrdID;
        this.ts = ts;
        this.ordType = ordType;
        this.tif = tif;
        ordStatus = OrdStatus.NEW;
        leavesQty = new Qty(qty.getSymbol(), qty.getLongQty());
    }
}
