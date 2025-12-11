package com.ys.exch_sim.domain.position;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Represents the result of matching a Close trade against an Open position in FIFO order.
 * One Close trade may match against multiple Open positions, creating multiple FifoMatch objects.
 */
@Data
@AllArgsConstructor
public class FifoMatch {
    /**
     * Execution ID of the matched open position
     */
    private String openExecId;

    /**
     * Quantity matched in this pairing
     * May be less than the full quantity of either the Open or Close trade
     */
    private double matchedQuantity;

    /**
     * Profit/Loss calculated for this match
     * Positive = profit, Negative = loss
     * Formula for long close: (closePrice - openPrice) × quantity
     * Formula for short close: (openPrice - closePrice) × quantity
     */
    private double profitLoss;
}
