package com.ys.exch_sim.domain.order_exec;


public interface ExecutionCallback {
    void setExecutionCallback(ExecutionCallback callback);
    void onExecutionReport(Execution report);
}
