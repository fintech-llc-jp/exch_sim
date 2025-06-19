package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.MarketMakeOrderRequest;
import com.ys.exch_sim.domain.dto.MarketMakeOrderResponse;
import com.ys.exch_sim.domain.service.MarketMakeService;
import com.ys.exch_sim.security.annotation.RequireMarketMaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/market-make")
@RequiredArgsConstructor
public class MarketMakeController {

    private final MarketMakeService marketMakeService;

    @PostMapping("/orders")
    @RequireMarketMaker
    public ResponseEntity<?> submitMarketMakeOrders(
            @Valid @RequestBody MarketMakeOrderRequest request,
            Authentication authentication) {
        
        try {
            String username = authentication.getName();
            log.info("Received market make orders request from user: {} for symbol: {}", 
                    username, request.getSymbol());

            MarketMakeOrderResponse response = marketMakeService.processMarketMakeOrders(username, request);
            
            log.info("Successfully processed market make orders for user: {} - Cancelled: {}, New Bids: {}, New Asks: {}", 
                    username, response.getCancelledOrdersCount(), 
                    response.getNewBidOrdersCount(), response.getNewAskOrdersCount());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing market make orders for user: " + authentication.getName(), e);
            return ResponseEntity.internalServerError()
                    .body(new MarketMakeOrderResponse(authentication.getName(), 
                            request != null ? request.getSymbol() : "UNKNOWN", 
                            "ERROR", "Error processing market make orders: " + e.getMessage()));
        }
    }

    @DeleteMapping("/orders/{symbol}")
    @RequireMarketMaker
    public ResponseEntity<?> cancelAllMarketMakeOrders(
            @PathVariable String symbol,
            Authentication authentication) {
        
        try {
            String username = authentication.getName();
            log.info("Received cancel all market make orders request from user: {} for symbol: {}", 
                    username, symbol);

            int cancelledCount = marketMakeService.cancelAllMarketMakeOrders(username, symbol);
            
            MarketMakeOrderResponse response = new MarketMakeOrderResponse(username, symbol);
            response.setCancelledOrdersCount(cancelledCount);
            response.setNewBidOrdersCount(0);
            response.setNewAskOrdersCount(0);
            response.setMessage("All market make orders cancelled");

            log.info("Successfully cancelled {} market make orders for user: {} symbol: {}", 
                    cancelledCount, username, symbol);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error cancelling market make orders for user: " + authentication.getName() + 
                     " symbol: " + symbol, e);
            return ResponseEntity.internalServerError()
                    .body(new MarketMakeOrderResponse(authentication.getName(), symbol, 
                            "ERROR", "Error cancelling market make orders: " + e.getMessage()));
        }
    }

    @GetMapping("/orders/{symbol}/status")
    @RequireMarketMaker
    public ResponseEntity<?> getMarketMakeOrderStatus(
            @PathVariable String symbol,
            Authentication authentication) {
        
        try {
            String username = authentication.getName();
            log.info("Received market make order status request from user: {} for symbol: {}", 
                    username, symbol);

            MarketMakeOrderResponse status = marketMakeService.getMarketMakeOrderStatus(username, symbol);
            
            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("Error getting market make order status for user: " + authentication.getName() + 
                     " symbol: " + symbol, e);
            return ResponseEntity.internalServerError()
                    .body(new MarketMakeOrderResponse(authentication.getName(), symbol, 
                            "ERROR", "Error getting market make order status: " + e.getMessage()));
        }
    }
}