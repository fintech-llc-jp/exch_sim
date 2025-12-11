package com.ys.exch_sim.domain.position;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * FIFO (First-In-First-Out) queue for managing open positions.
 * When a Close trade occurs, it is matched against the oldest Open positions first.
 */
@Slf4j
public class FifoPositionQueue {
    private final Queue<OpenPosition> openPositions = new LinkedList<>();

    /**
     * Add a new open position to the queue
     *
     * @param execId Execution ID
     * @param quantity Quantity opened
     * @param price Price at which position was opened
     * @param timestamp Timestamp of the open
     * @param side Side of the open position (BUY for long, SELL for short)
     */
    public void addOpen(String execId, double quantity, double price, LocalDateTime timestamp, String side) {
        openPositions.add(new OpenPosition(execId, quantity, price, timestamp, side));
        log.debug("Added open position: execId={}, qty={}, price={}, side={}", execId, quantity, price, side);
    }

    /**
     * Match a Close trade against open positions using FIFO logic
     *
     * @param closeQuantity Quantity to close
     * @param closePrice Price at which closing
     * @param closeSide Side of the close trade (SELL to close long, BUY to close short)
     * @return List of FifoMatch objects representing how the close was matched
     */
    public List<FifoMatch> matchClose(double closeQuantity, double closePrice, String closeSide) {
        List<FifoMatch> matches = new ArrayList<>();
        double remaining = closeQuantity;

        log.debug("Matching close: qty={}, price={}, side={}", closeQuantity, closePrice, closeSide);

        while (remaining > 0 && !openPositions.isEmpty()) {
            OpenPosition oldest = openPositions.peek();

            // Verify we're closing the correct side
            // SELL closes long positions (BUY), BUY closes short positions (SELL)
            boolean isValidClose = (closeSide.equals("SELL") && oldest.getSide().equals("BUY")) ||
                                   (closeSide.equals("BUY") && oldest.getSide().equals("SELL"));

            if (!isValidClose) {
                log.warn("Invalid close: trying to close {} position with {} order", oldest.getSide(), closeSide);
                break;
            }

            double matchQty = Math.min(remaining, oldest.getRemainingQuantity());

            // Calculate P/L
            double pnl;
            if (closeSide.equals("SELL")) {
                // Closing long position: (sell price - buy price) × quantity
                pnl = matchQty * (closePrice - oldest.getPrice());
            } else {
                // Closing short position: (sell price - buy price) × quantity
                // Since we're closing short, the open was a SELL, so: (openPrice - closePrice) × quantity
                pnl = matchQty * (oldest.getPrice() - closePrice);
            }

            matches.add(new FifoMatch(oldest.getExecId(), matchQty, pnl));
            log.debug("Matched: openExecId={}, matchQty={}, pnl={}", oldest.getExecId(), matchQty, pnl);

            oldest.setRemainingQuantity(oldest.getRemainingQuantity() - matchQty);
            if (oldest.getRemainingQuantity() <= 0.0001) { // Use small epsilon for floating point comparison
                openPositions.poll(); // Remove fully matched position
                log.debug("Fully closed position: {}", oldest.getExecId());
            }

            remaining -= matchQty;
        }

        if (remaining > 0.0001) {
            log.warn("Close quantity {} exceeds open positions, remaining unmatched: {}", closeQuantity, remaining);
        }

        return matches;
    }

    /**
     * Get the total remaining quantity across all open positions
     *
     * @return Total remaining quantity
     */
    public double getRemainingQuantity() {
        return openPositions.stream()
                .mapToDouble(OpenPosition::getRemainingQuantity)
                .sum();
    }

    /**
     * Get the number of open positions in the queue
     *
     * @return Number of open positions
     */
    public int size() {
        return openPositions.size();
    }

    /**
     * Check if the queue is empty
     *
     * @return true if no open positions exist
     */
    public boolean isEmpty() {
        return openPositions.isEmpty();
    }

    /**
     * Get a copy of all open positions (for debugging/testing)
     *
     * @return List of open positions
     */
    public List<OpenPosition> getOpenPositions() {
        return new ArrayList<>(openPositions);
    }
}
