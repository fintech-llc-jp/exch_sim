package com.ys.exch_sim.domain.message.field;

import lombok.Value;

@Value
public class Px {
   private Symbol symbol;
   private long longPx;

   public Px(Symbol symbol, double dblPx) {
    this.symbol = symbol;
    this.longPx = (long) (dblPx * symbol.getPxMultiplier());
   }

   public Px(Symbol symbol, Long longPx) {
    this.symbol = symbol;
    this.longPx = longPx;

   }
}
