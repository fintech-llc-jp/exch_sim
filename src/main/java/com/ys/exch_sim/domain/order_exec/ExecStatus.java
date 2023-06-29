package com.ys.exch_sim.domain.order_exec;

public enum ExecStatus {
    NEW,
    PARTIAL_FILL,
    FILLED,
    CANCELED,
    REJECTED
}
