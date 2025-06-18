package com.ys.exch_sim.domain.order_exec;

import com.ys.exch_sim.domain.message.field.ExecID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import java.util.UUID;
import lombok.Value;

@Value
public class Execution {
  private Order order;
  private ExecStatus execStatus;
  private Px lastPx;
  private Qty lastQty;
  private String counterPartyUsername;
  private ExecID execID;

  public Execution(Order order, ExecStatus execStatus, Px lastPx, Qty lastQty) {
    this.order = order;
    this.execStatus = execStatus;
    this.lastPx = lastPx;
    this.lastQty = lastQty;
    this.counterPartyUsername = null;
    this.execID = new ExecID(UUID.randomUUID().toString());
  }

  public Execution(
      Order order, ExecStatus execStatus, Px lastPx, Qty lastQty, String counterPartyUsername) {
    this.order = order;
    this.execStatus = execStatus;
    this.lastPx = lastPx;
    this.lastQty = lastQty;
    this.counterPartyUsername = counterPartyUsername;
    this.execID = new ExecID(UUID.randomUUID().toString());
  }
}
