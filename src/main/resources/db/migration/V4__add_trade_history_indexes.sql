-- Add indexes for trade_history table to optimize FIFO queries
-- These indexes improve performance for order list queries with P/L information

-- Index for querying trade history by order ID
-- Used when aggregating P/L for a specific order in orders/list API
CREATE INDEX IF NOT EXISTS idx_trade_history_cl_ord_id
ON trade_history(cl_ord_id);

-- Index for filtering by open_close type
-- Used for efficiently finding OPEN/CLOSE trades
CREATE INDEX IF NOT EXISTS idx_trade_history_open_close
ON trade_history(open_close);

-- Composite index for user + symbol + open_close queries
-- Used for FIFO queue reconstruction on startup
CREATE INDEX IF NOT EXISTS idx_trade_history_user_symbol_open
ON trade_history(username, symbol, open_close, created_at);

-- Index for execution ID lookups
-- Used when matching Close trades with Open trades in FIFO queue
CREATE INDEX IF NOT EXISTS idx_trade_history_exec_id
ON trade_history(exec_id);
