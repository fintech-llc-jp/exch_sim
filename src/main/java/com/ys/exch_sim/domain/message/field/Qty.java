package com.ys.exch_sim.domain.message.field;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Value
@Slf4j
public class Qty {
  private long longQty;
  private Symbol symbol;

  public Qty(Symbol symbol, double dblQty) {
    this.symbol = symbol;
    log.debug(
        "Qty calculation: dblQty={}, qtyMultiplier={}, calculation={}",
        dblQty,
        symbol.getQtyMultiplier(),
        dblQty * symbol.getQtyMultiplier());
    this.longQty = (long) (dblQty * symbol.getQtyMultiplier());
    log.debug("Qty result: longQty={}", this.longQty);
  }

  public Qty(Symbol symbol, long longQty) {
    this.symbol = symbol;
    this.longQty = longQty;
  }
}
