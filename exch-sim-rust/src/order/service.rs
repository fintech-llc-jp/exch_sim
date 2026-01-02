use crate::config::Config;
use crate::database::DatabaseTrait;
use crate::market_board::manager::MarketBoardManager;
use crate::models::{ExecStatus, Execution, NewOrderRequest, OrdStatus, OrdType, Side, TimeInForce};
use crate::order::order::Order;
use crate::position::manager::PositionManager;
use anyhow::Result;
use chrono::Utc;
use dashmap::DashMap;
use std::sync::Arc;
use tracing::{error, info};
use uuid::Uuid;

pub struct OrderService {
    database: Arc<dyn DatabaseTrait>,
    market_board_manager: MarketBoardManager,
    position_manager: Arc<PositionManager>,
    config: Config,
    order_map: Arc<DashMap<String, Order>>, // For cancellation lookup
}

impl OrderService {
    pub async fn new<D: DatabaseTrait + 'static>(
        database: D,
        market_board_manager: Arc<MarketBoardManager>,
        position_manager: Arc<PositionManager>,
        config: Config,
    ) -> Result<Self> {
        Ok(Self {
            database: Arc::new(database),
            market_board_manager: (*market_board_manager).clone(),
            position_manager,
            config,
            order_map: Arc::new(DashMap::new()),
        })
    }

    pub async fn process_new_order(
        &self,
        username: &str,
        request: &NewOrderRequest,
    ) -> Result<crate::models::OrderResponse> {
        info!("Processing new order for user: {} with request: {:?}", username, request);

        // Validate symbol
        if !self.config.is_valid_symbol(&request.symbol) {
            return Err(anyhow::anyhow!("Invalid symbol: {}", request.symbol));
        }

        let instrument = self
            .config
            .get_instrument(&request.symbol)
            .ok_or_else(|| anyhow::anyhow!("Instrument not found: {}", request.symbol))?;

        // Cash商品の空売りチェック
        if !request.is_market_make {
            if instrument.instrument_type == "Cash" && request.side == "SELL" {
                let position = self.position_manager.get_position(username, &request.symbol).await?;
                let available_qty = position.as_ref().map(|p| p.net_qty).unwrap_or(0.0);
                if available_qty < request.quantity {
                    return Err(anyhow::anyhow!(
                        "Insufficient position for cash sale. Available: {}, Requested: {}",
                        available_qty,
                        request.quantity
                    ));
                }
            }
        }

        // 資金チェック（買い注文の場合）
        if request.side == "BUY" {
            let required_amount = if request.ord_type == "MARKET" {
                // 成り行き注文の場合、最良Ask価格を使用
                let board = self
                    .market_board_manager
                    .get_or_create_board(request.symbol.clone())
                    .await;
                let board_guard = board.read().await;
                if let Some((_, best_ask_price)) = board_guard.get_best_ask() {
                    let instrument = self
                        .config
                        .get_instrument(&request.symbol)
                        .ok_or_else(|| anyhow::anyhow!("Instrument not found: {}", request.symbol))?;
                    let price_multiplier = instrument.price_multiplier as f64;
                    (best_ask_price as f64 / price_multiplier) * request.quantity
                } else {
                    return Err(anyhow::anyhow!(
                        "No ask price available for market order on symbol: {}",
                        request.symbol
                    ));
                }
            } else {
                // 指値注文の場合、指定価格を使用
                request.price.unwrap_or(0.0) * request.quantity
            };
            
            let available_funds = self.position_manager.get_cash_balance(username).await?;
            if available_funds < required_amount {
                return Err(anyhow::anyhow!(
                    "Insufficient funds. Required: {}, Available: {}",
                    required_amount,
                    available_funds
                ));
            }
        }

        // Create order
        let side = match request.side.as_str() {
            "BUY" => Side::Buy,
            "SELL" => Side::Sell,
            _ => return Err(anyhow::anyhow!("Invalid side: {}", request.side)),
        };

        let ord_type = match request.ord_type.as_str() {
            "LIMIT" => OrdType::Limit,
            "MARKET" => OrdType::Market,
            _ => return Err(anyhow::anyhow!("Invalid ord_type: {}", request.ord_type)),
        };

        let tif = match request.tif.as_str() {
            "GTC" => TimeInForce::Gtc,
            "IOC" => TimeInForce::Ioc,
            "FOK" => TimeInForce::Fok,
            _ => return Err(anyhow::anyhow!("Invalid tif: {}", request.tif)),
        };

        let mut order = Order::new(
            username.to_string(),
            request.symbol.clone(),
            side,
            ord_type,
            request.price,
            request.quantity,
            tif,
            request.is_market_make,
            instrument.price_multiplier,
            instrument.qty_multiplier,
        );

        // Get or create market board
        let board = self
            .market_board_manager
            .get_or_create_board(request.symbol.clone())
            .await;

        // Process order matching
        let mut executions = Vec::new();
        let mut board_lock = board.write().await;

        // Check if order can match
        let can_match = match side {
            Side::Buy => {
                if let Some((best_ask_price, _)) = board_lock.get_best_ask() {
                    if let Some(limit_price) = order.raw_price {
                        best_ask_price <= limit_price
                    } else {
                        true // Market order
                    }
                } else {
                    false
                }
            }
            Side::Sell => {
                if let Some((best_bid_price, _)) = board_lock.get_best_bid() {
                    if let Some(limit_price) = order.raw_price {
                        best_bid_price >= limit_price
                    } else {
                        true // Market order
                    }
                } else {
                    false
                }
            }
        };

        if can_match {
            // Process matching
            let matching_orders = board_lock.get_matching_orders(
                side,
                order.raw_price,
                order.raw_leaves_qty,
            );

            for (counter_order_entry, exec_qty) in matching_orders {
                // Create executions for both parties
                let exec_id = Uuid::new_v4().to_string();
                let exec_status = if order.raw_leaves_qty == exec_qty {
                    ExecStatus::Filled
                } else {
                    ExecStatus::PartiallyFilled
                };

                let exec_price = counter_order_entry.price;

                // Execution for the new order
                let exec1 = Execution {
                    exec_id: exec_id.clone(),
                    order_id: order.cl_ord_id.clone(),
                    cl_ord_id: order.cl_ord_id.clone(),
                    username: username.to_string(),
                    symbol: request.symbol.clone(),
                    exec_status,
                    last_px: exec_price,
                    last_qty: exec_qty,
                    counter_party_username: counter_order_entry.username.clone(),
                    created_at: Utc::now(),
                    is_market_maker: order.is_market_make,
                    side: side.to_string(),
                };

                // Execution for the counter order
                let exec2 = Execution {
                    exec_id: Uuid::new_v4().to_string(),
                    order_id: counter_order_entry.cl_ord_id.clone(),
                    cl_ord_id: counter_order_entry.cl_ord_id.clone(),
                    username: counter_order_entry.username.clone(),
                    symbol: request.symbol.clone(),
                    exec_status: if counter_order_entry.leaves_qty == 0 {
                        ExecStatus::Filled
                    } else {
                        ExecStatus::PartiallyFilled
                    },
                    last_px: exec_price,
                    last_qty: exec_qty,
                    counter_party_username: username.to_string(),
                    created_at: Utc::now(),
                    is_market_maker: counter_order_entry.ord_status == OrdStatus::New, // Simplified
                    side: match counter_order_entry.side {
                        Side::Buy => "BUY".to_string(),
                        Side::Sell => "SELL".to_string(),
                    },
                };

                order.update_execution(exec_qty, exec_price);
                executions.push(exec1);
                executions.push(exec2);
            }

            // Update order status
            if order.raw_leaves_qty == 0 {
                order.ord_status = OrdStatus::Filled;
            } else {
                order.ord_status = OrdStatus::PartiallyFilled;
            }

            // Java版の444-445行目に相当：残りの数量があれば板に追加
            if order.raw_leaves_qty > 0 && ord_type == OrdType::Limit {
                board_lock.add_order(&order);
            }
        } else {
            // No match - add to board if limit order
            if ord_type == OrdType::Limit {
                if tif == TimeInForce::Fok {
                    // FOK order that can't be filled immediately is rejected
                    let exec = Execution {
                        exec_id: Uuid::new_v4().to_string(),
                        order_id: order.cl_ord_id.clone(),
                        cl_ord_id: order.cl_ord_id.clone(),
                        username: username.to_string(),
                        symbol: request.symbol.clone(),
                        exec_status: ExecStatus::Rejected,
                        last_px: 0,
                        last_qty: 0,
                        counter_party_username: String::new(),
                        created_at: Utc::now(),
                        is_market_maker: order.is_market_make,
                        side: side.to_string(),
                    };
                    executions.push(exec);
                } else {
                    // Add to board
                    board_lock.add_order(&order);
                    let exec = Execution {
                        exec_id: Uuid::new_v4().to_string(),
                        order_id: order.cl_ord_id.clone(),
                        cl_ord_id: order.cl_ord_id.clone(),
                        username: username.to_string(),
                        symbol: request.symbol.clone(),
                        exec_status: ExecStatus::New,
                        last_px: order.raw_price.unwrap_or(0),
                        last_qty: order.raw_quantity,
                        counter_party_username: String::new(),
                        created_at: Utc::now(),
                        is_market_maker: order.is_market_make,
                        side: side.to_string(),
                    };
                    executions.push(exec);
                }
            } else {
                // Market order with no match is rejected
                let exec = Execution {
                    exec_id: Uuid::new_v4().to_string(),
                    order_id: order.cl_ord_id.clone(),
                    cl_ord_id: order.cl_ord_id.clone(),
                    username: username.to_string(),
                    symbol: request.symbol.clone(),
                    exec_status: ExecStatus::Rejected,
                    last_px: 0,
                    last_qty: 0,
                    counter_party_username: String::new(),
                    created_at: Utc::now(),
                    is_market_maker: order.is_market_make,
                    side: side.to_string(),
                };
                executions.push(exec);
            }
        }

        drop(board_lock);

        // Save executions to database
        for exec in &executions {
            if exec.exec_status == ExecStatus::Filled || exec.exec_status == ExecStatus::PartiallyFilled {
                if let Err(e) = self.database.insert_execution(exec).await {
                    error!(
                        "Failed to save execution to database: exec_id={}, order_id={}, cl_ord_id={}, username={}, symbol={}, exec_status={:?}, last_px={}, last_qty={}, counter_party={}, is_market_maker={}, side={}, error={:?}",
                        exec.exec_id,
                        exec.order_id,
                        exec.cl_ord_id,
                        exec.username,
                        exec.symbol,
                        exec.exec_status,
                        exec.last_px,
                        exec.last_qty,
                        exec.counter_party_username,
                        exec.is_market_maker,
                        exec.side,
                        e
                    );
                }

                // Process position update
                if let Err(e) = self.position_manager.process_execution(exec).await {
                    error!(
                        "Failed to process execution for position: exec_id={}, username={}, symbol={}, last_px={}, last_qty={}, error={:?}",
                        exec.exec_id,
                        exec.username,
                        exec.symbol,
                        exec.last_px,
                        exec.last_qty,
                        e
                    );
                }
            }
        }

        // Save order to map for cancellation
        if order.ord_status != OrdStatus::Filled && order.ord_status != OrdStatus::Rejected {
            self.order_map.insert(order.cl_ord_id.clone(), order.clone());
        }

        // Create response
        let exec_responses: Vec<crate::models::ExecutionResponse> = executions
            .iter()
            .filter(|e| e.username == username)
            .map(|e| crate::models::ExecutionResponse {
                exec_id: e.exec_id.clone(),
                exec_status: format!("{:?}", e.exec_status),
                last_px: e.last_px as f64 / instrument.price_multiplier as f64,
                last_qty: e.last_qty as f64 / instrument.qty_multiplier as f64,
                side: e.side.clone(),
            })
            .collect();

        let status = if exec_responses.is_empty() {
            "NEW".to_string()
        } else {
            exec_responses.last().unwrap().exec_status.clone()
        };

        Ok(crate::models::OrderResponse {
            cl_ord_id: order.cl_ord_id,
            status,
            executions: exec_responses,
        })
    }

    pub async fn cancel_order(
        &self,
        username: &str,
        cl_ord_id: &str,
        symbol: &str,
    ) -> Result<crate::models::OrderResponse> {
        info!("Processing cancel order for user: {} cl_ord_id: {}", username, cl_ord_id);

        // Validate symbol
        if !self.config.is_valid_symbol(symbol) {
            return Err(anyhow::anyhow!("Invalid symbol: {}", symbol));
        }

        // Find order in market board
        let board = self
            .market_board_manager
            .get_or_create_board(symbol.to_string())
            .await;

        let mut board_lock = board.write().await;
        let order_entry = board_lock.remove_order(cl_ord_id);

        if let Some(order_entry) = order_entry {
            // Verify user ownership
            if order_entry.username != username {
                return Err(anyhow::anyhow!("Not authorized to cancel this order"));
            }

            // Create cancel execution
            let exec = Execution {
                exec_id: Uuid::new_v4().to_string(),
                order_id: cl_ord_id.to_string(),
                cl_ord_id: cl_ord_id.to_string(),
                username: username.to_string(),
                symbol: symbol.to_string(),
                exec_status: ExecStatus::Canceled,
                last_px: order_entry.price,
                last_qty: order_entry.leaves_qty,
                counter_party_username: String::new(),
                created_at: Utc::now(),
                is_market_maker: false, // Simplified
                side: match order_entry.side {
                    Side::Buy => "BUY".to_string(),
                    Side::Sell => "SELL".to_string(),
                },
            };

            drop(board_lock);

            // Save execution
            if let Err(e) = self.database.insert_execution(&exec).await {
                error!("Failed to save cancel execution: {}", e);
            }

            // Remove from order map
            self.order_map.remove(cl_ord_id);

            let instrument = self
                .config
                .get_instrument(symbol)
                .ok_or_else(|| anyhow::anyhow!("Instrument not found: {}", symbol))?;

            Ok(crate::models::OrderResponse {
                cl_ord_id: cl_ord_id.to_string(),
                status: "CANCELED".to_string(),
                executions: vec![crate::models::ExecutionResponse {
                    exec_id: exec.exec_id,
                    exec_status: "CANCELED".to_string(),
                    last_px: exec.last_px as f64 / instrument.price_multiplier as f64,
                    last_qty: exec.last_qty as f64 / instrument.qty_multiplier as f64,
                    side: exec.side,
                }],
            })
        } else {
            Err(anyhow::anyhow!("Order not found: {}", cl_ord_id))
        }
    }

    /// Process a market maker order (from external market data)
    /// This method skips fund checks and position checks, and returns executions directly
    pub async fn process_market_maker_order(
        &self,
        symbol: &str,
        side: Side,
        price: f64,
        quantity: f64,
    ) -> Result<Vec<Execution>> {
        tracing::debug!(
            "Processing market maker order: symbol={}, side={:?}, price={}, quantity={}",
            symbol,
            side,
            price,
            quantity
        );

        // Validate symbol
        if !self.config.is_valid_symbol(symbol) {
            return Err(anyhow::anyhow!("Invalid symbol: {}", symbol));
        }

        let instrument = self
            .config
            .get_instrument(symbol)
            .ok_or_else(|| anyhow::anyhow!("Instrument not found: {}", symbol))?;

        // Create market maker order
        let mut order = Order::new(
            "MARKET_MAKER".to_string(),
            symbol.to_string(),
            side,
            OrdType::Limit,
            Some(price),
            quantity,
            TimeInForce::Gtc,
            true, // is_market_make = true
            instrument.price_multiplier,
            instrument.qty_multiplier,
        );

        // Generate cl_ord_id in MM_ format
        order.cl_ord_id = format!("MM_{}", Uuid::new_v4());

        // Get or create market board
        let board = self
            .market_board_manager
            .get_or_create_board(symbol.to_string())
            .await;

        // Process order matching
        let mut executions = Vec::new();
        let mut board_lock = board.write().await;

        // Check if order can match - Java版のcheckMeetingBid()/checkMeetingAsk()と同じロジック
        let can_match = match side {
            Side::Buy => {
                // Java版のcheckMeetingAsk()と同じロジックを使用
                if let Some(limit_price) = order.raw_price {
                    let matches = board_lock.check_meeting_ask(limit_price);
                    tracing::debug!(
                        "Market maker BUY order matching check: symbol={}, limit_price={}, matches={}",
                        symbol,
                        limit_price,
                        matches
                    );
                    matches
                } else {
                    false // Market maker orders are always limit orders
                }
            }
            Side::Sell => {
                // Java版のcheckMeetingBid()と同じロジックを使用
                if let Some(limit_price) = order.raw_price {
                    let matches = board_lock.check_meeting_bid(limit_price);
                    tracing::debug!(
                        "Market maker SELL order matching check: symbol={}, limit_price={}, matches={}",
                        symbol,
                        limit_price,
                        matches
                    );
                    matches
                } else {
                    false // Market maker orders are always limit orders
                }
            }
        };

        if can_match {
            // Process matching
            let matching_orders = board_lock.get_matching_orders(
                side,
                order.raw_price,
                order.raw_leaves_qty,
            );

            for (counter_order_entry, exec_qty) in matching_orders {
                // Create executions for both parties
                let exec_id = Uuid::new_v4().to_string();
                let exec_status = if order.raw_leaves_qty == exec_qty {
                    ExecStatus::Filled
                } else {
                    ExecStatus::PartiallyFilled
                };

                let exec_price = counter_order_entry.price;

                // Execution for the market maker order
                let exec1 = Execution {
                    exec_id: exec_id.clone(),
                    order_id: order.cl_ord_id.clone(),
                    cl_ord_id: order.cl_ord_id.clone(),
                    username: "MARKET_MAKER".to_string(),
                    symbol: symbol.to_string(),
                    exec_status,
                    last_px: exec_price,
                    last_qty: exec_qty,
                    counter_party_username: counter_order_entry.username.clone(),
                    created_at: Utc::now(),
                    is_market_maker: true,
                    side: side.to_string(),
                };

                // Execution for the counter order (user order)
                let exec2 = Execution {
                    exec_id: Uuid::new_v4().to_string(),
                    order_id: counter_order_entry.cl_ord_id.clone(),
                    cl_ord_id: counter_order_entry.cl_ord_id.clone(),
                    username: counter_order_entry.username.clone(),
                    symbol: symbol.to_string(),
                    exec_status: if counter_order_entry.leaves_qty == 0 {
                        ExecStatus::Filled
                    } else {
                        ExecStatus::PartiallyFilled
                    },
                    last_px: exec_price,
                    last_qty: exec_qty,
                    counter_party_username: "MARKET_MAKER".to_string(),
                    created_at: Utc::now(),
                    is_market_maker: false, // User order is not market maker
                    side: match counter_order_entry.side {
                        Side::Buy => "BUY".to_string(),
                        Side::Sell => "SELL".to_string(),
                    },
                };

                order.update_execution(exec_qty, exec_price);
                executions.push(exec1);
                executions.push(exec2);
            }

            // Update order status
            if order.raw_leaves_qty == 0 {
                order.ord_status = OrdStatus::Filled;
            } else {
                order.ord_status = OrdStatus::PartiallyFilled;
            }
        }

        // If order is not fully filled, add to board
        if order.raw_leaves_qty > 0 {
            board_lock.add_order(&order);
            tracing::debug!(
                "Market maker order added to board: symbol={}, side={:?}, price={}, leaves_qty={}, ask_entry_board updated",
                symbol,
                side,
                order.raw_price.unwrap_or(0),
                order.raw_leaves_qty
            );
            
            // Verify that entry_board was updated correctly
            if let Some(limit_price) = order.raw_price {
                match side {
                    Side::Buy => {
                        let qty = board_lock.get_bid_entry_qty(limit_price);
                        if qty.is_some() {
                            tracing::debug!(
                                "Market maker BUY order board update verified: price={}, qty={}",
                                limit_price,
                                qty.unwrap()
                            );
                        }
                    }
                    Side::Sell => {
                        let qty = board_lock.get_ask_entry_qty(limit_price);
                        if qty.is_some() {
                            tracing::debug!(
                                "Market maker SELL order board update verified: price={}, qty={}",
                                limit_price,
                                qty.unwrap()
                            );
                        }
                    }
                }
            }
        }

        drop(board_lock);

        // Save executions to database and process position updates
        for exec in &executions {
            if exec.exec_status == ExecStatus::Filled || exec.exec_status == ExecStatus::PartiallyFilled {
                if let Err(e) = self.database.insert_execution(exec).await {
                    error!(
                        "Failed to save execution to database (market maker order): exec_id={}, order_id={}, cl_ord_id={}, username={}, symbol={}, exec_status={:?}, last_px={}, last_qty={}, counter_party={}, is_market_maker={}, side={}, error={:?}",
                        exec.exec_id,
                        exec.order_id,
                        exec.cl_ord_id,
                        exec.username,
                        exec.symbol,
                        exec.exec_status,
                        exec.last_px,
                        exec.last_qty,
                        exec.counter_party_username,
                        exec.is_market_maker,
                        exec.side,
                        e
                    );
                }

                // Process position update
                if let Err(e) = self.position_manager.process_execution(exec).await {
                    error!(
                        "Failed to process execution for position (market maker order): exec_id={}, username={}, symbol={}, last_px={}, last_qty={}, error={:?}",
                        exec.exec_id,
                        exec.username,
                        exec.symbol,
                        exec.last_px,
                        exec.last_qty,
                        e
                    );
                }
            }
        }

        // Save order to map for cancellation (if not fully filled)
        if order.ord_status != OrdStatus::Filled {
            self.order_map.insert(order.cl_ord_id.clone(), order);
        }

        tracing::debug!(
            "Market maker order processed: {} executions created",
            executions.len()
        );

        Ok(executions)
    }

    pub async fn get_order_list(
        &self,
        username: &str,
        symbol_filter: Option<&str>,
        status_filter: Option<&str>,
    ) -> Result<crate::models::OrderListResponse> {
        info!(
            "Getting order list for user: {}, symbol filter: {:?}, status filter: {:?}",
            username, symbol_filter, status_filter
        );

        let mut order_dtos = Vec::new();

        // Default status filter is NEW
        let effective_status_filter = status_filter.unwrap_or("NEW");
        let allowed_statuses: Vec<&str> = effective_status_filter
            .split(',')
            .map(|s| s.trim())
            .collect();

        // Get all available symbols from config
        let symbols: Vec<String> = self.config.get_all_symbols();

        for symbol in symbols {
            // Apply symbol filter
            if let Some(filter) = symbol_filter {
                if !symbol.eq_ignore_ascii_case(filter) {
                    continue;
                }
            }

            // Get board for this symbol
            let board = self.market_board_manager.get_or_create_board(symbol.clone()).await;
            let board_guard = board.read().await;

            // Get user orders from this board
            let user_orders = board_guard.get_user_orders(username);

            // Get instrument config for multipliers
            let instrument = match self.config.get_instrument(&symbol) {
                Some(inst) => inst,
                None => continue,
            };

            let price_multiplier = instrument.price_multiplier as f64;
            let qty_multiplier = instrument.qty_multiplier as f64;

            for order_entry in user_orders {
                // Apply status filter
                // Convert OrdStatus to string matching Java format (NEW, PARTIALLY_FILLED, etc.)
                let status_str = match order_entry.ord_status {
                    OrdStatus::New => "NEW",
                    OrdStatus::PartiallyFilled => "PARTIALLY_FILLED",
                    OrdStatus::Filled => "FILLED",
                    OrdStatus::Canceled => "CANCELED",
                    OrdStatus::Rejected => "REJECTED",
                };
                let status_matches = allowed_statuses.iter().any(|&allowed| {
                    status_str.eq_ignore_ascii_case(allowed)
                });

                if !status_matches {
                    continue;
                }

                // Get full Order object for timestamp
                let order = self.order_map.get(&order_entry.cl_ord_id);
                let timestamp = order
                    .as_ref()
                    .map(|o| o.created_at)
                    .unwrap_or_else(|| Utc::now());

                // Convert raw values to actual values
                let order_px = if order_entry.price > 0 {
                    Some(order_entry.price as f64 / price_multiplier)
                } else {
                    None
                };
                let order_qty = order_entry.quantity as f64 / qty_multiplier;
                let leaves_qty = order_entry.leaves_qty as f64 / qty_multiplier;
                let filled_qty = order_qty - leaves_qty;

                // Determine ord_type from Order object or default to LIMIT
                let ord_type = order
                    .as_ref()
                    .map(|o| match o.ord_type {
                        OrdType::Limit => "LIMIT",
                        OrdType::Market => "MARKET",
                    })
                    .unwrap_or("LIMIT")
                    .to_string();

                // Determine tif from Order object or default to GTC
                let tif = order
                    .as_ref()
                    .map(|o| match o.tif {
                        TimeInForce::Gtc => "GTC",
                        TimeInForce::Ioc => "IOC",
                        TimeInForce::Fok => "FOK",
                    })
                    .unwrap_or("GTC")
                    .to_string();

                let dto = crate::models::OrderDto {
                    cl_ord_id: order_entry.cl_ord_id.clone(),
                    symbol: symbol.clone(),
                    side: order_entry.side.to_string(),
                    ord_type,
                    ord_status: status_str.to_string(),
                    order_px,
                    order_qty,
                    leaves_qty,
                    filled_qty,
                    tif,
                    timestamp,
                };

                order_dtos.push(dto);
            }
        }

        // Sort by timestamp descending (newest first)
        order_dtos.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));

        info!("Retrieved {} orders for user: {}", order_dtos.len(), username);

        Ok(crate::models::OrderListResponse {
            username: username.to_string(),
            total_orders: order_dtos.len(),
            orders: order_dtos,
        })
    }
}

impl Side {
    pub fn to_string(&self) -> String {
        match self {
            Side::Buy => "BUY".to_string(),
            Side::Sell => "SELL".to_string(),
        }
    }
}

