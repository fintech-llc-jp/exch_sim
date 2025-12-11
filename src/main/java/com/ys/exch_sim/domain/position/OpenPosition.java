package com.ys.exch_sim.domain.position;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Represents an open position in the FIFO queue.
 * Tracks a single execution that opened a position, which can be partially closed.
 */
@Data
@AllArgsConstructor
public class OpenPosition {
    /**
     * Execution ID of the open trade
     */
    private String execId;

    /**
     * Remaining quantity that hasn't been closed yet
     * Can be partially reduced as Close trades are matched against this Open position
     */
    private double remainingQuantity;

    /**
     * The price at which this position was opened
     */
    private double price;

    /**
     * Timestamp when this position was opened
     */
    private LocalDateTime timestamp;

    /**
     * Side of the open position (BUY for long, SELL for short)
     */
    private String side;
}
