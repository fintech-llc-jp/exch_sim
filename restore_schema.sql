-- exch_sim PostgreSQL スキーマ復元スクリプト
-- Docker削除後にスキーマのみ復元する場合に使用
-- 実行方法: psql -h localhost -U postgres -d exch_sim -f restore_schema.sql

-- データベース作成（必要に応じて手動で実行）
-- CREATE DATABASE exch_sim;

-- 既存テーブルを削除してから再作成する場合（データは全て消えます）
-- DROP TABLE IF EXISTS market_board_price_levels CASCADE;
-- DROP TABLE IF EXISTS market_board_snapshots CASCADE;
-- DROP TABLE IF EXISTS user_roles CASCADE;
-- DROP TABLE IF EXISTS users CASCADE;
-- DROP TABLE IF EXISTS trade_history CASCADE;
-- DROP TABLE IF EXISTS positions CASCADE;
-- DROP TABLE IF EXISTS executions CASCADE;

-- ============================================
-- 1. executions テーブル（約定履歴）
-- ============================================
CREATE TABLE IF NOT EXISTS executions (
    exec_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255),
    username VARCHAR(255),
    symbol VARCHAR(255),
    exec_status VARCHAR(50),
    last_px BIGINT,
    last_qty BIGINT,
    counter_party_username VARCHAR(255),
    created_at TIMESTAMP,
    is_market_maker BOOLEAN,
    side VARCHAR(10)
);

-- executions 用インデックス（V2相当、テーブル名を executions に修正）
CREATE INDEX IF NOT EXISTS idx_execution_time_status_mm 
ON executions (created_at, exec_status, is_market_maker);

CREATE INDEX IF NOT EXISTS idx_execution_symbol_time_status_mm 
ON executions (symbol, created_at, exec_status, is_market_maker);

CREATE INDEX IF NOT EXISTS idx_execution_market_maker_status 
ON executions (is_market_maker, exec_status);

CREATE INDEX IF NOT EXISTS idx_execution_volume_calc 
ON executions (is_market_maker, exec_status, created_at, symbol, last_qty);

CREATE INDEX IF NOT EXISTS idx_execution_count_calc 
ON executions (is_market_maker, exec_status, created_at, symbol);

-- ============================================
-- 2. positions テーブル（ポジション情報）
-- ============================================
CREATE TABLE IF NOT EXISTS positions (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    symbol VARCHAR(255) NOT NULL,
    unit VARCHAR(50) NOT NULL,
    total_buy_qty BIGINT NOT NULL,
    total_buy_amount DOUBLE PRECISION NOT NULL,
    total_sell_qty BIGINT NOT NULL,
    total_sell_amount DOUBLE PRECISION NOT NULL,
    net_qty BIGINT NOT NULL,
    average_buy_price DOUBLE PRECISION NOT NULL,
    average_sell_price DOUBLE PRECISION NOT NULL,
    realized_pnl DOUBLE PRECISION NOT NULL,
    last_updated TIMESTAMP NOT NULL
);

-- ============================================
-- 3. trade_history テーブル（取引履歴）
-- ============================================
CREATE TABLE IF NOT EXISTS trade_history (
    exec_id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    symbol VARCHAR(255) NOT NULL,
    side VARCHAR(10) NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    counter_party_username VARCHAR(255),
    timestamp TIMESTAMP NOT NULL,
    cl_ord_id VARCHAR(255),
    is_market_maker BOOLEAN NOT NULL,
    -- FIFO tracking columns (V3)
    open_close VARCHAR(10),
    profit_loss DOUBLE PRECISION,
    matched_open_exec_ids TEXT
);

-- trade_history 用インデックス（V4）
CREATE INDEX IF NOT EXISTS idx_trade_history_cl_ord_id
ON trade_history(cl_ord_id);

CREATE INDEX IF NOT EXISTS idx_trade_history_open_close
ON trade_history(open_close);

CREATE INDEX IF NOT EXISTS idx_trade_history_user_symbol_open
ON trade_history(username, symbol, open_close, timestamp);

CREATE INDEX IF NOT EXISTS idx_trade_history_exec_id
ON trade_history(exec_id);

-- ============================================
-- 4. users テーブル（ユーザー認証）
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- ============================================
-- 5. user_roles テーブル（ユーザーロール）
-- ============================================
CREATE TABLE IF NOT EXISTS user_roles (
    username VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (username, role),
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

-- ============================================
-- 6. market_board_snapshots テーブル（板スナップショット）
-- ============================================
CREATE TABLE IF NOT EXISTS market_board_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_market_board_snapshots_symbol_timestamp
ON market_board_snapshots(symbol, timestamp);

-- ============================================
-- 7. market_board_price_levels テーブル（板価格レベル）
-- ============================================
CREATE TABLE IF NOT EXISTS market_board_price_levels (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    side VARCHAR(3) NOT NULL,
    level_index INTEGER NOT NULL,
    FOREIGN KEY (snapshot_id) REFERENCES market_board_snapshots(id) ON DELETE CASCADE
);

-- 完了メッセージ
SELECT 'Schema restored successfully.' AS status;
