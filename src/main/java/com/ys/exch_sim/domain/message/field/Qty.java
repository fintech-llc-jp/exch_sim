package com.ys.exch_sim.domain.message.field;

import lombok.Value;

@Value
public class Qty {
    private long longQty;
    private Symbol symbol;

    public Qty(Symbol symbol, double dblQty) {
        this.symbol = symbol;
        this.longQty = (long) (dblQty * symbol.getQtyMultiplier());
   }

    public Qty(Symbol symbol, long longQty) {
        this.symbol = symbol;
        this.longQty = longQty;
   }
}
