package com.ys.exch_sim.domain.order_exec;

import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.ExecStatus;

import lombok.Value;

@Value
public class Execution {
    public Execution(Order order, ExecStatus execStatus, Px lastPx, Qty lastQty) {
        this.order = order;
        this.execStatus = execStatus;
        this.lastPx = lastPx;
        this.lastQty = lastQty;
    }
    private Order order;
    private ExecStatus execStatus;
    private Px lastPx;
    private Qty lastQty;
    
}
