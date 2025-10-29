-- Optimize volume calculation performance
-- Create composite indexes for fast volume queries

-- Index for time-based queries (most important for 24-hour volume)
CREATE INDEX IF NOT EXISTS idx_execution_time_status_mm 
ON execution (created_at, exec_status, is_market_maker);

-- Index for symbol-specific time-based queries
CREATE INDEX IF NOT EXISTS idx_execution_symbol_time_status_mm 
ON execution (symbol, created_at, exec_status, is_market_maker);

-- Index for market maker filtering
CREATE INDEX IF NOT EXISTS idx_execution_market_maker_status 
ON execution (is_market_maker, exec_status);

-- Composite index for volume calculation (covers most common queries)
CREATE INDEX IF NOT EXISTS idx_execution_volume_calc 
ON execution (is_market_maker, exec_status, created_at, symbol, last_qty);

-- Index for execution count queries
CREATE INDEX IF NOT EXISTS idx_execution_count_calc 
ON execution (is_market_maker, exec_status, created_at, symbol);