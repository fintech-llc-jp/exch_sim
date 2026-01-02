use crate::models::PriceLevel;
use std::collections::BTreeMap;
use uuid::Uuid;

// Market maker username for external market data
const MARKET_MAKER_USERNAME: &str = "MARKET_MAKER";

#[derive(Debug)]
pub struct MarketBoard {
    symbol: String,
    // Bids: price descending (highest first)
    bid_order_board: BTreeMap<i64, Vec<OrderEntry>>,
    // Asks: price ascending (lowest first) - use reverse order
    ask_order_board: BTreeMap<i64, Vec<OrderEntry>>,
    // Quantity aggregations
    bid_entry_board: BTreeMap<i64, i64>, // price -> total quantity
    ask_entry_board: BTreeMap<i64, i64>, // price -> total quantity
    // Order lookup
    order_map: std::collections::HashMap<String, OrderEntry>,
    last_order_time: std::sync::atomic::AtomicU64,
}

#[derive(Debug, Clone)]
pub struct OrderEntry {
    pub cl_ord_id: String,
    pub username: String,
    pub side: crate::models::Side,
    pub price: i64,
    pub quantity: i64,
    pub leaves_qty: i64,
    pub cum_qty: i64,
    pub ord_status: crate::models::OrdStatus,
}

impl MarketBoard {
    pub fn new(symbol: String) -> Self {
        Self {
            symbol,
            bid_order_board: BTreeMap::new(),
            ask_order_board: BTreeMap::new(),
            bid_entry_board: BTreeMap::new(),
            ask_entry_board: BTreeMap::new(),
            order_map: std::collections::HashMap::new(),
            last_order_time: std::sync::atomic::AtomicU64::new(0),
        }
    }

    pub fn get_symbol(&self) -> &str {
        &self.symbol
    }

    pub fn get_bid(&self, index: usize) -> Option<(i64, i64)> {
        self.bid_entry_board
            .iter()
            .rev() // Reverse to get highest price first
            .nth(index)
            .map(|(price, qty)| (*price, *qty))
    }

    pub fn get_ask(&self, index: usize) -> Option<(i64, i64)> {
        self.ask_entry_board
            .iter()
            .nth(index)
            .map(|(price, qty)| (*price, *qty))
    }

    pub fn get_best_bid(&self) -> Option<(i64, i64)> {
        self.bid_entry_board
            .iter()
            .rev()
            .next()
            .map(|(price, qty)| (*price, *qty))
    }

    pub fn get_best_ask(&self) -> Option<(i64, i64)> {
        self.ask_entry_board
            .iter()
            .next()
            .map(|(price, qty)| (*price, *qty))
    }

    /// Check if a sell order can match against bids (Java版のcheckMeetingBid()と同じロジック)
    pub fn check_meeting_bid(&self, limit_price: i64) -> bool {
        // Java版: for (Entry<Long, Long> ent : bidEntryBoard.entrySet()) {
        //   if (ent.getKey() < order.getOrderPx().getLongPx()) {
        //     return false;
        //   } else {
        //     return true;
        //   }
        // }
        // bidEntryBoardは降順（最高価格が最初）
        for (&bid_price, _) in self.bid_entry_board.iter().rev() {
            if bid_price < limit_price {
                return false;
            } else {
                return true;
            }
        }
        false
    }

    /// Check if a buy order can match against asks (Java版のcheckMeetingAsk()と同じロジック)
    pub fn check_meeting_ask(&self, limit_price: i64) -> bool {
        // Java版: for (Entry<Long, Long> ent : askEntryBoard.entrySet()) {
        //   if (ent.getKey() > order.getOrderPx().getLongPx()) {
        //     return false;
        //   } else {
        //     return true;
        //   }
        // }
        // askEntryBoardは昇順（最低価格が最初）
        for (&ask_price, _) in self.ask_entry_board.iter() {
            if ask_price > limit_price {
                return false;
            } else {
                return true;
            }
        }
        false
    }

    pub fn add_order(&mut self, order: &crate::order::Order) {
        let price = order.get_raw_price();
        let qty = order.get_raw_quantity();
        let entry = OrderEntry {
            cl_ord_id: order.cl_ord_id.clone(),
            username: order.username.clone(),
            side: order.side,
            price,
            quantity: qty,
            leaves_qty: order.leaves_qty as i64,
            cum_qty: order.cum_qty as i64,
            ord_status: order.ord_status,
        };

        self.order_map.insert(order.cl_ord_id.clone(), entry.clone());

        match order.side {
            crate::models::Side::Buy => {
                self.bid_order_board
                    .entry(price)
                    .or_insert_with(Vec::new)
                    .push(entry);
                // Use leaves_qty instead of qty to match clear_market_maker_orders() logic
                *self.bid_entry_board.entry(price).or_insert(0) += order.leaves_qty as i64;
            }
            crate::models::Side::Sell => {
                self.ask_order_board
                    .entry(price)
                    .or_insert_with(Vec::new)
                    .push(entry);
                // Use leaves_qty instead of qty to match clear_market_maker_orders() logic
                *self.ask_entry_board.entry(price).or_insert(0) += order.leaves_qty as i64;
            }
        }

        self.last_order_time.store(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
    }

    pub fn remove_order(&mut self, cl_ord_id: &str) -> Option<OrderEntry> {
        if let Some(entry) = self.order_map.remove(cl_ord_id) {
            match entry.side {
                crate::models::Side::Buy => {
                    if let Some(orders) = self.bid_order_board.get_mut(&entry.price) {
                        orders.retain(|o| o.cl_ord_id != cl_ord_id);
                        if orders.is_empty() {
                            self.bid_order_board.remove(&entry.price);
                        }
                    }
                    if let Some(qty) = self.bid_entry_board.get_mut(&entry.price) {
                        *qty -= entry.leaves_qty;
                        if *qty <= 0 {
                            self.bid_entry_board.remove(&entry.price);
                        }
                    }
                }
                crate::models::Side::Sell => {
                    if let Some(orders) = self.ask_order_board.get_mut(&entry.price) {
                        orders.retain(|o| o.cl_ord_id != cl_ord_id);
                        if orders.is_empty() {
                            self.ask_order_board.remove(&entry.price);
                        }
                    }
                    if let Some(qty) = self.ask_entry_board.get_mut(&entry.price) {
                        *qty -= entry.leaves_qty;
                        if *qty <= 0 {
                            self.ask_entry_board.remove(&entry.price);
                        }
                    }
                }
            }
            Some(entry)
        } else {
            None
        }
    }

    pub fn get_order(&self, cl_ord_id: &str) -> Option<&OrderEntry> {
        self.order_map.get(cl_ord_id)
    }

    pub fn get_matching_orders(
        &mut self,
        side: crate::models::Side,
        price: Option<i64>,
        quantity: i64,
    ) -> Vec<(OrderEntry, i64)> {
        // Returns (order_entry, execution_quantity)
        let mut results = Vec::new();
        let mut remaining_qty = quantity;

        match side {
            crate::models::Side::Buy => {
                // Buy order matches against asks (ascending price)
                let prices: Vec<i64> = self.ask_entry_board.keys().cloned().collect();
                for ask_price in prices {
                    if let Some(limit_price) = price {
                        if ask_price > limit_price {
                            break; // Price too high
                        }
                    }

                    if remaining_qty <= 0 {
                        break;
                    }

                    if let Some(orders) = self.ask_order_board.get_mut(&ask_price) {
                        for order in orders.iter_mut() {
                            if remaining_qty <= 0 {
                                break;
                            }

                            let exec_qty = std::cmp::min(remaining_qty, order.leaves_qty);
                            results.push((order.clone(), exec_qty));
                            order.leaves_qty -= exec_qty;
                            order.cum_qty += exec_qty;
                            remaining_qty -= exec_qty;

                            if order.leaves_qty == 0 {
                                order.ord_status = crate::models::OrdStatus::Filled;
                            } else {
                                order.ord_status = crate::models::OrdStatus::PartiallyFilled;
                            }
                        }

                        // Remove filled orders
                        orders.retain(|o| o.leaves_qty > 0);
                        if orders.is_empty() {
                            self.ask_order_board.remove(&ask_price);
                            self.ask_entry_board.remove(&ask_price);
                        } else {
                            // Update total quantity
                            let total_qty: i64 = orders.iter().map(|o| o.leaves_qty).sum();
                            self.ask_entry_board.insert(ask_price, total_qty);
                        }
                    }
                }
            }
            crate::models::Side::Sell => {
                // Sell order matches against bids (descending price)
                let prices: Vec<i64> = self.bid_entry_board.keys().rev().cloned().collect();
                for bid_price in prices {
                    if let Some(limit_price) = price {
                        if bid_price < limit_price {
                            break; // Price too low
                        }
                    }

                    if remaining_qty <= 0 {
                        break;
                    }

                    if let Some(orders) = self.bid_order_board.get_mut(&bid_price) {
                        for order in orders.iter_mut() {
                            if remaining_qty <= 0 {
                                break;
                            }

                            let exec_qty = std::cmp::min(remaining_qty, order.leaves_qty);
                            results.push((order.clone(), exec_qty));
                            order.leaves_qty -= exec_qty;
                            order.cum_qty += exec_qty;
                            remaining_qty -= exec_qty;

                            if order.leaves_qty == 0 {
                                order.ord_status = crate::models::OrdStatus::Filled;
                            } else {
                                order.ord_status = crate::models::OrdStatus::PartiallyFilled;
                            }
                        }

                        // Remove filled orders
                        orders.retain(|o| o.leaves_qty > 0);
                        if orders.is_empty() {
                            self.bid_order_board.remove(&bid_price);
                            self.bid_entry_board.remove(&bid_price);
                        } else {
                            // Update total quantity
                            let total_qty: i64 = orders.iter().map(|o| o.leaves_qty).sum();
                            self.bid_entry_board.insert(bid_price, total_qty);
                        }
                    }
                }
            }
        }

        results
    }

    pub fn get_snapshot(&self, max_levels: usize) -> (Vec<PriceLevel>, Vec<PriceLevel>) {
        let bids: Vec<PriceLevel> = self
            .bid_entry_board
            .iter()
            .rev()
            .take(max_levels)
            .map(|(price, qty)| PriceLevel {
                price: *price as f64,
                quantity: *qty as f64,
            })
            .collect();

        let asks: Vec<PriceLevel> = self
            .ask_entry_board
            .iter()
            .take(max_levels)
            .map(|(price, qty)| PriceLevel {
                price: *price as f64,
                quantity: *qty as f64,
            })
            .collect();

        // Log first bid and ask for debugging
        if !bids.is_empty() && !asks.is_empty() {
            tracing::debug!(
                "MarketBoard {} get_snapshot: first bid={}, first ask={}, spread={}",
                self.symbol,
                bids[0].price,
                asks[0].price,
                asks[0].price - bids[0].price
            );
        }

        (bids, asks)
    }

    pub fn is_empty(&self) -> bool {
        self.bid_entry_board.is_empty() && self.ask_entry_board.is_empty()
    }

    /// Get the number of price levels in bid_order_board (for debugging)
    pub fn get_bid_order_board_levels(&self) -> usize {
        self.bid_order_board.len()
    }

    /// Get the number of price levels in ask_order_board (for debugging)
    pub fn get_ask_order_board_levels(&self) -> usize {
        self.ask_order_board.len()
    }

    /// Get the number of price levels in bid_entry_board (for debugging)
    pub fn get_bid_entry_board_levels(&self) -> usize {
        self.bid_entry_board.len()
    }

    /// Get the number of price levels in ask_entry_board (for debugging)
    pub fn get_ask_entry_board_levels(&self) -> usize {
        self.ask_entry_board.len()
    }

    /// Clear market maker orders (external market data) while preserving user orders
    /// This is equivalent to Java's clearBids() and clearAsks()
    pub fn clear_market_maker_orders(&mut self) {
        // Clear market maker orders from bid_order_board
        for orders in self.bid_order_board.values_mut() {
            orders.retain(|o| o.username != MARKET_MAKER_USERNAME);
        }
        // Remove empty price levels
        self.bid_order_board.retain(|_, orders| !orders.is_empty());

        // Clear market maker orders from ask_order_board
        for orders in self.ask_order_board.values_mut() {
            orders.retain(|o| o.username != MARKET_MAKER_USERNAME);
        }
        // Remove empty price levels
        self.ask_order_board.retain(|_, orders| !orders.is_empty());

        // Remove market maker orders from order_map
        self.order_map.retain(|_, entry| entry.username != MARKET_MAKER_USERNAME);

        // Rebuild bid_entry_board from remaining user orders
        self.bid_entry_board.clear();
        for (price, orders) in &self.bid_order_board {
            let total_qty: i64 = orders.iter().map(|o| o.leaves_qty).sum();
            if total_qty > 0 {
                self.bid_entry_board.insert(*price, total_qty);
            }
        }

        // Rebuild ask_entry_board from remaining user orders
        self.ask_entry_board.clear();
        for (price, orders) in &self.ask_order_board {
            let total_qty: i64 = orders.iter().map(|o| o.leaves_qty).sum();
            if total_qty > 0 {
                self.ask_entry_board.insert(*price, total_qty);
            }
        }

        tracing::debug!(
            "MarketBoard {}: Cleared market maker orders - bid_order_board: {} levels, ask_order_board: {} levels",
            self.symbol,
            self.bid_order_board.len(),
            self.ask_order_board.len()
        );
        
        // Debug: Log ask_entry_board contents after clearing
        if !self.ask_entry_board.is_empty() {
            let ask_prices: Vec<String> = self.ask_entry_board
                .iter()
                .take(5)
                .map(|(price, qty)| format!("{}:{}", price, qty))
                .collect();
            tracing::debug!(
                "MarketBoard {}: ask_entry_board after clear (top 5): {:?}",
                self.symbol,
                ask_prices
            );
        } else {
            tracing::debug!(
                "MarketBoard {}: ask_entry_board is empty after clear",
                self.symbol
            );
        }
        
        // Debug: Log bid_entry_board contents after clearing
        if !self.bid_entry_board.is_empty() {
            let bid_prices: Vec<String> = self.bid_entry_board
                .iter()
                .rev()
                .take(5)
                .map(|(price, qty)| format!("{}:{}", price, qty))
                .collect();
            tracing::debug!(
                "MarketBoard {}: bid_entry_board after clear (top 5): {:?}",
                self.symbol,
                bid_prices
            );
        } else {
            tracing::debug!(
                "MarketBoard {}: bid_entry_board is empty after clear",
                self.symbol
            );
        }
    }

    /// Update external market data (from WebSocket)
    /// This creates OrderEntry objects and adds them to both order_board and entry_board
    /// This matches Java's addMarketMakerOrder() behavior
    pub fn update_external_market_data(
        &mut self,
        bids: Vec<(f64, f64)>,
        asks: Vec<(f64, f64)>,
        price_multiplier: f64,
        qty_multiplier: f64,
    ) {
        let bid_count_before = self.bid_entry_board.len();
        let ask_count_before = self.ask_entry_board.len();

        // Clear existing market maker orders (but keep user orders)
        self.clear_market_maker_orders();
        
        // Update bids - create OrderEntry and add to both bid_order_board and bid_entry_board
        for (price, qty) in bids {
            let raw_price = (price * price_multiplier) as i64;
            let raw_qty = (qty * qty_multiplier) as i64;
            if raw_qty > 0 {
                // Create OrderEntry for market maker
                let cl_ord_id = format!("MARKET_MAKER_{}_{}", self.symbol, Uuid::new_v4());
                let entry = OrderEntry {
                    cl_ord_id: cl_ord_id.clone(),
                    username: MARKET_MAKER_USERNAME.to_string(),
                    side: crate::models::Side::Buy,
                    price: raw_price,
                    quantity: raw_qty,
                    leaves_qty: raw_qty,
                    cum_qty: 0,
                    ord_status: crate::models::OrdStatus::New,
                };

                // Add to bid_order_board
                self.bid_order_board
                    .entry(raw_price)
                    .or_insert_with(Vec::new)
                    .push(entry.clone());

                // Add to order_map
                self.order_map.insert(cl_ord_id, entry);

                // Update bid_entry_board
                *self.bid_entry_board.entry(raw_price).or_insert(0) += raw_qty;
            }
        }

        // Update asks - create OrderEntry and add to both ask_order_board and ask_entry_board
        for (price, qty) in asks {
            let raw_price = (price * price_multiplier) as i64;
            let raw_qty = (qty * qty_multiplier) as i64;
            if raw_qty > 0 {
                // Create OrderEntry for market maker
                let cl_ord_id = format!("MARKET_MAKER_{}_{}", self.symbol, Uuid::new_v4());
                let entry = OrderEntry {
                    cl_ord_id: cl_ord_id.clone(),
                    username: MARKET_MAKER_USERNAME.to_string(),
                    side: crate::models::Side::Sell,
                    price: raw_price,
                    quantity: raw_qty,
                    leaves_qty: raw_qty,
                    cum_qty: 0,
                    ord_status: crate::models::OrdStatus::New,
                };

                // Add to ask_order_board
                self.ask_order_board
                    .entry(raw_price)
                    .or_insert_with(Vec::new)
                    .push(entry.clone());

                // Add to order_map
                self.order_map.insert(cl_ord_id, entry);

                // Update ask_entry_board
                *self.ask_entry_board.entry(raw_price).or_insert(0) += raw_qty;
            }
        }

        let bid_count_after = self.bid_entry_board.len();
        let ask_count_after = self.ask_entry_board.len();

        tracing::debug!(
            "MarketBoard {}: Updated external market data - bids: {} -> {} ({} order entries), asks: {} -> {} ({} order entries)",
            self.symbol,
            bid_count_before,
            bid_count_after,
            self.bid_order_board.len(),
            ask_count_before,
            ask_count_after,
            self.ask_order_board.len()
        );

        self.last_order_time.store(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
    }

    /// Update external market data with delta updates
    /// This creates/updates/removes OrderEntry objects based on delta updates
    pub fn update_external_market_data_delta(
        &mut self,
        bids: Vec<(f64, f64)>,
        asks: Vec<(f64, f64)>,
        price_multiplier: f64,
        qty_multiplier: f64,
    ) {
        // Apply delta updates for bids: if qty is 0, remove the price level; otherwise update/add
        for (price, qty) in bids {
            let raw_price = (price * price_multiplier) as i64;
            let raw_qty = (qty * qty_multiplier) as i64;
            
            // Remove existing market maker orders at this price level
            if let Some(orders) = self.bid_order_board.get_mut(&raw_price) {
                orders.retain(|o| o.username != MARKET_MAKER_USERNAME);
                if orders.is_empty() {
                    self.bid_order_board.remove(&raw_price);
                }
            }
            
            // Remove from order_map
            let market_maker_cl_ord_ids: Vec<String> = self
                .order_map
                .iter()
                .filter(|(_, entry)| {
                    entry.username == MARKET_MAKER_USERNAME
                        && entry.side == crate::models::Side::Buy
                        && entry.price == raw_price
                })
                .map(|(cl_ord_id, _)| cl_ord_id.clone())
                .collect();
            for cl_ord_id in market_maker_cl_ord_ids {
                self.order_map.remove(&cl_ord_id);
            }
            
            if raw_qty == 0 {
                // Remove from bid_entry_board
                self.bid_entry_board.remove(&raw_price);
            } else {
                // Create new OrderEntry for market maker
                let cl_ord_id = format!("MARKET_MAKER_{}_{}", self.symbol, Uuid::new_v4());
                let entry = OrderEntry {
                    cl_ord_id: cl_ord_id.clone(),
                    username: MARKET_MAKER_USERNAME.to_string(),
                    side: crate::models::Side::Buy,
                    price: raw_price,
                    quantity: raw_qty,
                    leaves_qty: raw_qty,
                    cum_qty: 0,
                    ord_status: crate::models::OrdStatus::New,
                };

                // Add to bid_order_board
                self.bid_order_board
                    .entry(raw_price)
                    .or_insert_with(Vec::new)
                    .push(entry.clone());

                // Add to order_map
                self.order_map.insert(cl_ord_id, entry);

                // Update bid_entry_board (recalculate from all orders at this price)
                let total_qty: i64 = self
                    .bid_order_board
                    .get(&raw_price)
                    .map(|orders| orders.iter().map(|o| o.leaves_qty).sum())
                    .unwrap_or(0);
                if total_qty > 0 {
                    self.bid_entry_board.insert(raw_price, total_qty);
                }
            }
        }

        // Apply delta updates for asks: if qty is 0, remove the price level; otherwise update/add
        for (price, qty) in asks {
            let raw_price = (price * price_multiplier) as i64;
            let raw_qty = (qty * qty_multiplier) as i64;
            
            // Remove existing market maker orders at this price level
            if let Some(orders) = self.ask_order_board.get_mut(&raw_price) {
                orders.retain(|o| o.username != MARKET_MAKER_USERNAME);
                if orders.is_empty() {
                    self.ask_order_board.remove(&raw_price);
                }
            }
            
            // Remove from order_map
            let market_maker_cl_ord_ids: Vec<String> = self
                .order_map
                .iter()
                .filter(|(_, entry)| {
                    entry.username == MARKET_MAKER_USERNAME
                        && entry.side == crate::models::Side::Sell
                        && entry.price == raw_price
                })
                .map(|(cl_ord_id, _)| cl_ord_id.clone())
                .collect();
            for cl_ord_id in market_maker_cl_ord_ids {
                self.order_map.remove(&cl_ord_id);
            }
            
            if raw_qty == 0 {
                // Remove from ask_entry_board
                self.ask_entry_board.remove(&raw_price);
            } else {
                // Create new OrderEntry for market maker
                let cl_ord_id = format!("MARKET_MAKER_{}_{}", self.symbol, Uuid::new_v4());
                let entry = OrderEntry {
                    cl_ord_id: cl_ord_id.clone(),
                    username: MARKET_MAKER_USERNAME.to_string(),
                    side: crate::models::Side::Sell,
                    price: raw_price,
                    quantity: raw_qty,
                    leaves_qty: raw_qty,
                    cum_qty: 0,
                    ord_status: crate::models::OrdStatus::New,
                };

                // Add to ask_order_board
                self.ask_order_board
                    .entry(raw_price)
                    .or_insert_with(Vec::new)
                    .push(entry.clone());

                // Add to order_map
                self.order_map.insert(cl_ord_id, entry);

                // Update ask_entry_board (recalculate from all orders at this price)
                let total_qty: i64 = self
                    .ask_order_board
                    .get(&raw_price)
                    .map(|orders| orders.iter().map(|o| o.leaves_qty).sum())
                    .unwrap_or(0);
                if total_qty > 0 {
                    self.ask_entry_board.insert(raw_price, total_qty);
                }
            }
        }

        self.last_order_time.store(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
    }

    /// Get the last order time in nanoseconds since epoch
    pub fn get_last_order_time(&self) -> i64 {
        self.last_order_time.load(std::sync::atomic::Ordering::Relaxed) as i64
    }

    /// Get all orders for a specific user
    pub fn get_user_orders(&self, username: &str) -> Vec<&OrderEntry> {
        self.order_map
            .values()
            .filter(|entry| entry.username == username)
            .collect()
    }
}

