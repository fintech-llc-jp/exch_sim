-- Add FIFO tracking columns to trade_history table
-- These columns enable FIFO-based profit/loss calculation for Close orders

-- Add open_close column to distinguish between OPEN and CLOSE trades
ALTER TABLE trade_history ADD COLUMN IF NOT EXISTS open_close VARCHAR(10);

-- Add profit_loss column to store the calculated P/L for CLOSE trades
ALTER TABLE trade_history ADD COLUMN IF NOT EXISTS profit_loss DOUBLE PRECISION;

-- Add matched_open_exec_ids column to store JSON array of matched open execution IDs
-- Format: ["exec-id-1", "exec-id-2", ...]
ALTER TABLE trade_history ADD COLUMN IF NOT EXISTS matched_open_exec_ids TEXT;

-- Add cl_ord_id column for tracking which order this trade belongs to
ALTER TABLE trade_history ADD COLUMN IF NOT EXISTS cl_ord_id VARCHAR(255);
