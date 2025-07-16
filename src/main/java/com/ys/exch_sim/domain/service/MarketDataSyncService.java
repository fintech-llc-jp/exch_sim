package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.dto.RedisMarketMakeMessage;
import com.ys.exch_sim.domain.dto.RedisTradeInsertMessage;
import com.ys.exch_sim.domain.market_board.MarketBoard;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.domain.order_exec.Order;
import com.ys.exch_sim.infra.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataSyncService {

    private final OrderService orderService;
    private final ExecutionRepository executionRepository;
    private final InstrumentConfig instrumentConfig;

    public void updateMarketBoard(RedisMarketMakeMessage message) {
        try {
            String symbolName = message.symbol();
            
            if (!instrumentConfig.isValidSymbol(symbolName)) {
                log.warn("Invalid symbol received from Redis: {}", symbolName);
                return;
            }

            MarketBoard marketBoard = getOrCreateMarketBoard(symbolName);
            
            // Clear existing levels
            marketBoard.clearBids();
            marketBoard.clearAsks();
            
            InstrumentConfig.InstrumentDefinition instrumentDef = instrumentConfig.getInstrument(symbolName);
            Symbol symbol = new Symbol(symbolName.toUpperCase(), instrumentDef.getPriceMultiplier(), instrumentDef.getQtyMultiplier());
            
            // Update bid levels and create corresponding orders
            for (int i = 0; i < message.bidLevels().size() && i < 10; i++) {
                var bidLevel = message.bidLevels().get(i);
                if (bidLevel.price() != null && bidLevel.quantity() != null && bidLevel.quantity() > 0) {
                    long price = (long) (bidLevel.price() * instrumentDef.getPriceMultiplier());
                    long quantity = (long) (bidLevel.quantity() * instrumentDef.getQtyMultiplier());
                    marketBoard.setBid(i, new Pair<>(price, quantity));
                    
                    // Create a market maker order for this price level
                    Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.BUY);
                    marketBoard.addMarketMakerOrder(marketMakerOrder);
                }
            }
            
            // Update ask levels and create corresponding orders
            for (int i = 0; i < message.askLevels().size() && i < 10; i++) {
                var askLevel = message.askLevels().get(i);
                if (askLevel.price() != null && askLevel.quantity() != null && askLevel.quantity() > 0) {
                    long price = (long) (askLevel.price() * instrumentDef.getPriceMultiplier());
                    long quantity = (long) (askLevel.quantity() * instrumentDef.getQtyMultiplier());
                    marketBoard.setAsk(i, new Pair<>(price, quantity));
                    
                    // Create a market maker order for this price level
                    Order marketMakerOrder = createMarketMakerOrder(symbol, price, quantity, Side.SELL);
                    marketBoard.addMarketMakerOrder(marketMakerOrder);
                }
            }
            
            log.info("Updated market board for symbol: {} with {} bids, {} asks", 
                symbolName, message.bidLevels().size(), message.askLevels().size());
            
            // Debug: Show current board state
            log.debug("Current askEntryBoard size: {}, bidEntryBoard size: {}", 
                marketBoard.getAskEntryBoardSize(), marketBoard.getBidEntryBoardSize());
            
        } catch (Exception e) {
            log.error("Error updating market board for symbol: {}", message.symbol(), e);
        }
    }

    public void insertTrade(RedisTradeInsertMessage message) {
        try {
            String symbolName = message.symbol();
            
            if (!instrumentConfig.isValidSymbol(symbolName)) {
                log.warn("Invalid symbol received from Redis: {}", symbolName);
                return;
            }

            InstrumentConfig.InstrumentDefinition instrumentDef = instrumentConfig.getInstrument(symbolName);
            
            Side side = "BUY".equals(message.side()) ? Side.BUY : Side.SELL;
            
            // Create execution record
            Execution execution = new Execution(
                UUID.randomUUID().toString(), // execID
                UUID.randomUUID().toString(), // orderID (fake)
                "REDIS_FEED", // username
                symbolName,
                ExecStatus.FILLED,
                (long) (message.price() * instrumentDef.getPriceMultiplier()), // Convert to internal price
                (long) (message.quantity() * instrumentDef.getQtyMultiplier()), // Convert to internal quantity
                "MARKET", // counterPartyUsername
                LocalDateTime.now(ZoneOffset.UTC),
                false, // isMarketMaker
                side.toString()
            );

            // Save to database
            executionRepository.save(execution);
            
            log.debug("Inserted trade execution for symbol: {} - side: {}, price: {}, quantity: {}", 
                symbolName, message.side(), message.price(), message.quantity());
            
        } catch (Exception e) {
            log.error("Error inserting trade for symbol: {}", message.symbol(), e);
        }
    }

    private MarketBoard getOrCreateMarketBoard(String symbolName) {
        return orderService.getOrCreateMarketBoardForSync(symbolName);
    }

    private Order createMarketMakerOrder(Symbol symbol, long price, long quantity, Side side) {
        // Create unique order ID for market maker
        String orderId = "MM_" + symbol.getName() + "_" + side + "_" + price + "_" + System.currentTimeMillis();
        ClOrdID clOrdID = new ClOrdID(orderId);
        
        // Create price and quantity objects
        Px px = new Px(symbol, price);
        Qty qty = new Qty(symbol, quantity);
        
        // Create timestamp
        Timestamp timestamp = new Timestamp(LocalDateTime.now(ZoneOffset.UTC));
        
        // Create the order with market maker properties
        return new Order(
            symbol,
            px,
            qty,
            side,
            clOrdID,
            timestamp,
            OrdType.LIMIT,  // Market maker orders are typically limit orders
            Tif.GTC,        // Good Till Cancel
            "MARKET_MAKER"  // Special username for market maker
        );
    }
}