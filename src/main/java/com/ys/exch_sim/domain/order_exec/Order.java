package com.ys.exch_sim.domain.order_exec;

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
    private Order next;
    private Execution execution; 
}
