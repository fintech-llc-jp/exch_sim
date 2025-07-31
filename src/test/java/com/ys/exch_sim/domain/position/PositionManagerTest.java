package com.ys.exch_sim.domain.position;

import com.ys.exch_sim.domain.message.field.*;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PositionManagerTest {

    private PositionManager positionManager;
    
    @Mock
    private PositionRepository positionRepository;
    
    @Mock
    private TradeHistoryRepository tradeHistoryRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Enable memory cache, disable database persistence for tests
        positionManager = new PositionManager(positionRepository, tradeHistoryRepository, true, false);
    }

    @Test
    void testProcessBuyExecution() {
        // テスト用のExecutionを作成
        Symbol symbol = new Symbol("BTCJPY", 100, 1);
        Order order = createTestOrder("user1", symbol, Side.BUY, 10L, 100.0);
        Px lastPx = new Px(symbol, 100.0);
        Qty lastQty = new Qty(symbol, 10L);
        
        Execution execution = new Execution(order, ExecStatus.FILLED, lastPx, lastQty, "user2");
        
        // Execution処理
        positionManager.processExecution(execution);
        
        // ポジション確認
        Position position = positionManager.getPosition("user1", "BTCJPY");
        assertThat(position).isNotNull();
        assertThat(position.getTotalBuyQty()).isEqualTo(10L);
        assertThat(position.getTotalBuyAmount()).isEqualTo(1000.0);
        assertThat(position.getNetQty()).isEqualTo(10L);
        
        // 取引履歴確認
        List<TradeHistory> history = positionManager.getTradeHistory("user1");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getSide()).isEqualTo("BUY");
        assertThat(history.get(0).getQuantity()).isEqualTo(10L);
        assertThat(history.get(0).getPrice()).isEqualTo(100.0);
    }

    @Test
    void testProcessSellExecution() {
        // 先に買いポジションを作成
        Symbol symbol = new Symbol("BTCJPY", 100, 1);
        Order buyOrder = createTestOrder("user1", symbol, Side.BUY, 10L, 100.0);
        Px buyPx = new Px(symbol, 100.0);
        Qty buyQty = new Qty(symbol, 10L);
        Execution buyExecution = new Execution(buyOrder, ExecStatus.FILLED, buyPx, buyQty, "user2");
        positionManager.processExecution(buyExecution);
        
        // 売り注文を処理
        Order sellOrder = createTestOrder("user1", symbol, Side.SELL, 5L, 110.0);
        Px sellPx = new Px(symbol, 110.0);
        Qty sellQty = new Qty(symbol, 5L);
        Execution sellExecution = new Execution(sellOrder, ExecStatus.FILLED, sellPx, sellQty, "user3");
        positionManager.processExecution(sellExecution);
        
        // ポジション確認
        Position position = positionManager.getPosition("user1", "BTCJPY");
        assertThat(position.getTotalSellQty()).isEqualTo(5L);
        assertThat(position.getNetQty()).isEqualTo(5L);
        assertThat(position.getRealizedPnL()).isEqualTo(50.0); // 5 * (110 - 100)
        
        // 取引履歴確認
        List<TradeHistory> history = positionManager.getTradeHistory("user1");
        assertThat(history).hasSize(2);
    }

    @Test
    void testGetAllPositions() {
        // 複数銘柄でポジション作成
        Symbol btcSymbol = new Symbol("BTCJPY", 100, 1);
        Symbol ethSymbol = new Symbol("ETHJPY", 100, 1);
        
        // BTCJPY買い
        Order btcOrder = createTestOrder("user1", btcSymbol, Side.BUY, 10L, 100.0);
        Execution btcExecution = new Execution(btcOrder, ExecStatus.FILLED, 
                                             new Px(btcSymbol, 100.0), new Qty(btcSymbol, 10L), "user2");
        positionManager.processExecution(btcExecution);
        
        // ETHJPY買い
        Order ethOrder = createTestOrder("user1", ethSymbol, Side.BUY, 5L, 200.0);
        Execution ethExecution = new Execution(ethOrder, ExecStatus.FILLED,
                                             new Px(ethSymbol, 200.0), new Qty(ethSymbol, 5L), "user3");
        positionManager.processExecution(ethExecution);
        
        // 全ポジション取得
        List<Position> positions = positionManager.getAllPositions("user1");
        assertThat(positions).hasSize(2);
        
        // 銘柄別確認
        Position btcPosition = positionManager.getPosition("user1", "BTCJPY");
        Position ethPosition = positionManager.getPosition("user1", "ETHJPY");
        assertThat(btcPosition.getNetQty()).isEqualTo(10L);
        assertThat(ethPosition.getNetQty()).isEqualTo(5L);
    }

    @Test
    void testTotalPnLCalculation() {
        // ポジション作成
        Symbol symbol = new Symbol("BTCJPY", 100, 1);
        Order buyOrder = createTestOrder("user1", symbol, Side.BUY, 10L, 100.0);
        Execution buyExecution = new Execution(buyOrder, ExecStatus.FILLED,
                                             new Px(symbol, 100.0), new Qty(symbol, 10L), "user2");
        positionManager.processExecution(buyExecution);
        
        Order sellOrder = createTestOrder("user1", symbol, Side.SELL, 5L, 110.0);
        Execution sellExecution = new Execution(sellOrder, ExecStatus.FILLED,
                                              new Px(symbol, 110.0), new Qty(symbol, 5L), "user3");
        positionManager.processExecution(sellExecution);
        
        // 現在価格設定
        Map<String, Double> currentPrices = new HashMap<>();
        currentPrices.put("BTCJPY", 105.0);
        
        // 損益計算
        double totalRealizedPnL = positionManager.getTotalRealizedPnL("user1");
        double totalUnrealizedPnL = positionManager.getTotalUnrealizedPnL("user1", currentPrices);
        double totalPnL = positionManager.getTotalPnL("user1", currentPrices);
        
        assertThat(totalRealizedPnL).isEqualTo(50.0); // 5 * (110 - 100)
        assertThat(totalUnrealizedPnL).isEqualTo(25.0); // 5 * (105 - 100)
        assertThat(totalPnL).isEqualTo(75.0); // 50 + 25
    }

    @Test
    void testTradeHistoryFiltering() {
        // 複数の取引を作成
        Symbol btcSymbol = new Symbol("BTCJPY", 100, 1);
        Symbol ethSymbol = new Symbol("ETHJPY", 100, 1);
        
        // BTCJPY取引
        Order btcOrder1 = createTestOrder("user1", btcSymbol, Side.BUY, 10L, 100.0);
        positionManager.processExecution(new Execution(btcOrder1, ExecStatus.FILLED,
                new Px(btcSymbol, 100.0), new Qty(btcSymbol, 10L), "user2"));
        
        Order btcOrder2 = createTestOrder("user1", btcSymbol, Side.SELL, 5L, 110.0);
        positionManager.processExecution(new Execution(btcOrder2, ExecStatus.FILLED,
                new Px(btcSymbol, 110.0), new Qty(btcSymbol, 5L), "user3"));
        
        // ETHJPY取引
        Order ethOrder = createTestOrder("user1", ethSymbol, Side.BUY, 3L, 200.0);
        positionManager.processExecution(new Execution(ethOrder, ExecStatus.FILLED,
                new Px(ethSymbol, 200.0), new Qty(ethSymbol, 3L), "user4"));
        
        // 全取引履歴
        List<TradeHistory> allHistory = positionManager.getTradeHistory("user1");
        assertThat(allHistory).hasSize(3);
        
        // BTCJPY取引履歴のみ
        List<TradeHistory> btcHistory = positionManager.getTradeHistory("user1", "BTCJPY");
        assertThat(btcHistory).hasSize(2);
        assertThat(btcHistory.stream().allMatch(h -> "BTCJPY".equals(h.getSymbol()))).isTrue();
        
        // 制限付き取引履歴
        List<TradeHistory> limitedHistory = positionManager.getTradeHistory("user1", 2);
        assertThat(limitedHistory).hasSize(2);
    }

    @Test
    void testStatistics() {
        // テストデータ作成
        Symbol symbol = new Symbol("BTCJPY", 100, 1);
        Order order1 = createTestOrder("user1", symbol, Side.BUY, 10L, 100.0);
        positionManager.processExecution(new Execution(order1, ExecStatus.FILLED,
                new Px(symbol, 100.0), new Qty(symbol, 10L), "user2"));
        
        Order order2 = createTestOrder("user1", symbol, Side.SELL, 5L, 110.0);
        positionManager.processExecution(new Execution(order2, ExecStatus.FILLED,
                new Px(symbol, 110.0), new Qty(symbol, 5L), "user3"));
        
        // 統計確認
        int tradeCount = positionManager.getTotalTradeCount("user1");
        double tradingVolume = positionManager.getTotalTradingVolume("user1");
        Map<String, Long> symbolCounts = positionManager.getSymbolTradeCounts("user1");
        
        assertThat(tradeCount).isEqualTo(2);
        assertThat(tradingVolume).isEqualTo(1550.0); // 1000 + 550
        assertThat(symbolCounts.get("BTCJPY")).isEqualTo(2);
    }

    private Order createTestOrder(String username, Symbol symbol, Side side, long quantity, double price) {
        ClOrdID clOrdID = new ClOrdID("test-order-" + System.nanoTime());
        Timestamp timestamp = new Timestamp(LocalDateTime.now());
        Px px = new Px(symbol, price);
        Qty qty = new Qty(symbol, quantity);
        OrdType ordType = OrdType.LIMIT;
        Tif tif = Tif.GTC;
        
        return new Order(symbol, px, qty, side, clOrdID, timestamp, ordType, tif, username);
    }
}