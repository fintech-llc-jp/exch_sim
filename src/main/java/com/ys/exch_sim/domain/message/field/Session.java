package com.ys.exch_sim.domain.message.field;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionCallback;

import lombok.Data;

@Data
public class Session implements ExecutionCallback{
    private final String id;

    private ExecutionCallback callback;
    @Override
    public void setExecutionCallback(ExecutionCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onExecutionReport(Execution execution) {
        callback.onExecutionReport(execution);
    }
}
