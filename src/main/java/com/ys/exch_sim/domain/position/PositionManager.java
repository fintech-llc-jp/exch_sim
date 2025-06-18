package com.ys.exch_sim.domain.position;

import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.infra.Pair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PositionManager {
    
    // ユーザー別・銘柄別のポジション管理
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Position>> positions = new ConcurrentHashMap<>();
    
    // 取引履歴（全ユーザー）
    private final List<TradeHistory> tradeHistories = Collections.synchronizedList(new ArrayList<>());

    public void processExecution(Execution execution) {
        if (execution == null || execution.getOrder() == null) {
            log.warn("Invalid execution or order is null");
            return;
        }

        String username = execution.getOrder().getUsername();
        String symbol = execution.getOrder().getSymbol().getName();
        Side side = execution.getOrder().getSide();
        long quantity = execution.getLastQty().getLongQty();
        double price = (double) execution.getLastPx().getLongPx() / execution.getLastPx().getSymbol().getPxMultiplier();
        
        // 相手方のユーザー名を取得（約定相手）
        String counterPartyUsername = execution.getCounterPartyUsername();
        
        log.info("Processing execution for user: {}, symbol: {}, side: {}, qty: {}, price: {}", 
                username, symbol, side, quantity, price);

        try {
            // ポジション更新
            Position position = getOrCreatePosition(username, symbol);
            
            if (side == Side.BUY) {
                position.addBuyTrade(quantity, price);
            } else if (side == Side.SELL) {
                position.addSellTrade(quantity, price);
            }

            // 取引履歴を記録
            TradeHistory tradeHistory = new TradeHistory(
                execution.getExecID().getId(),
                username,
                symbol,
                side.toString(),
                quantity,
                price,
                counterPartyUsername,
                execution.getOrder().getClOrdID().getId()
            );
            tradeHistories.add(tradeHistory);

            log.info("Position updated for user: {}, symbol: {}, netQty: {}, realizedPnL: {}", 
                    username, symbol, position.getNetQty(), position.getRealizedPnL());

        } catch (Exception e) {
            log.error("Error processing execution for user: " + username, e);
        }
    }

    public Position getPosition(String username, String symbol) {
        ConcurrentHashMap<String, Position> userPositions = positions.get(username);
        if (userPositions == null) {
            return null;
        }
        return userPositions.get(symbol.toUpperCase());
    }

    public List<Position> getAllPositions(String username) {
        ConcurrentHashMap<String, Position> userPositions = positions.get(username);
        if (userPositions == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(userPositions.values());
    }

    public List<TradeHistory> getTradeHistory(String username) {
        return tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()))
                .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                .collect(Collectors.toList());
    }

    public List<TradeHistory> getTradeHistory(String username, String symbol) {
        return tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()) && 
                                 symbol.equalsIgnoreCase(history.getSymbol()))
                .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                .collect(Collectors.toList());
    }

    public List<TradeHistory> getTradeHistory(String username, int limit) {
        return tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()))
                .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
                .limit(limit)
                .collect(Collectors.toList());
    }

    public double getTotalRealizedPnL(String username) {
        ConcurrentHashMap<String, Position> userPositions = positions.get(username);
        if (userPositions == null) {
            return 0.0;
        }
        
        return userPositions.values().stream()
                .mapToDouble(Position::getRealizedPnL)
                .sum();
    }

    public double getTotalUnrealizedPnL(String username, Map<String, Double> currentPrices) {
        ConcurrentHashMap<String, Position> userPositions = positions.get(username);
        if (userPositions == null) {
            return 0.0;
        }
        
        return userPositions.values().stream()
                .mapToDouble(position -> {
                    Double currentPrice = currentPrices.get(position.getSymbol());
                    return currentPrice != null ? position.getUnrealizedPnL(currentPrice) : 0.0;
                })
                .sum();
    }

    public double getTotalPnL(String username, Map<String, Double> currentPrices) {
        return getTotalRealizedPnL(username) + getTotalUnrealizedPnL(username, currentPrices);
    }

    private Position getOrCreatePosition(String username, String symbol) {
        return positions.computeIfAbsent(username, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(symbol.toUpperCase(), k -> {
                    log.info("Creating new position for user: {}, symbol: {}", username, symbol);
                    return new Position(username, symbol.toUpperCase());
                });
    }

    // 統計情報取得用メソッド
    public int getTotalTradeCount(String username) {
        return (int) tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()))
                .count();
    }

    public double getTotalTradingVolume(String username) {
        return tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()))
                .mapToDouble(TradeHistory::getAmount)
                .sum();
    }

    public Map<String, Long> getSymbolTradeCounts(String username) {
        return tradeHistories.stream()
                .filter(history -> username.equals(history.getUsername()))
                .collect(Collectors.groupingBy(
                    TradeHistory::getSymbol,
                    Collectors.counting()
                ));
    }

    // テスト用メソッド
    public void clearAllData() {
        positions.clear();
        tradeHistories.clear();
        log.info("All position and trade history data cleared");
    }
}