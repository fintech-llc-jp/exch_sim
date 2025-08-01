package com.ys.exch_sim.domain.message.field;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Value
@Slf4j
public class Px {
  private Symbol symbol;
  private long longPx;

  public Px(Symbol symbol, double dblPx) {
    this.symbol = symbol;
    log.debug(
        "Px calculation: dblPx={}, pxMultiplier={}, calculation={}",
        dblPx,
        symbol.getPxMultiplier(),
        dblPx * symbol.getPxMultiplier());
    this.longPx = (long) (dblPx * symbol.getPxMultiplier());
    log.debug("Px result: longPx={}", this.longPx);
  }

  public Px(Symbol symbol, Long longPx) {
    this.symbol = symbol;
    this.longPx = longPx;
  }
}
