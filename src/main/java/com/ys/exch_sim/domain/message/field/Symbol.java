package com.ys.exch_sim.domain.message.field;

import lombok.Value;

@Value
public class Symbol {
   private String name;
   private long pxMultiplier;
   private long qtyMultiplier;
}
