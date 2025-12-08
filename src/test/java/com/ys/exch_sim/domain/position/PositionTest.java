package com.ys.exch_sim.domain.position;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionTest {

    private Position position;

    @BeforeEach
    void setUp() {
        position = new Position("testuser", "BTCJPY");
    }

    @Test
    void testInitialPosition() {
        assertThat(position.getUsername()).isEqualTo("testuser");
        assertThat(position.getSymbol()).isEqualTo("BTCJPY");
        assertThat(position.getTotalBuyQty()).isEqualTo(0L);
        assertThat(position.getTotalSellQty()).isEqualTo(0L);
        assertThat(position.getNetQty()).isEqualTo(0L);
        assertThat(position.getRealizedPnL()).isEqualTo(0.0);
        assertThat(position.isFlat()).isTrue();
        assertThat(position.isLongPosition()).isFalse();
        assertThat(position.isShortPosition()).isFalse();
    }

    @Test
    void testAddBuyTrade() {
        position.addBuyTrade(10L, 100.0);

        assertThat(position.getTotalBuyQty()).isEqualTo(10L);
        assertThat(position.getTotalBuyAmount()).isEqualTo(1000.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(100.0);
        assertThat(position.getNetQty()).isEqualTo(10L);
        assertThat(position.isLongPosition()).isTrue();
    }

    @Test
    void testAddSellTrade() {
        position.addSellTrade(5L, 110.0);

        assertThat(position.getTotalSellQty()).isEqualTo(5L);
        assertThat(position.getTotalSellAmount()).isEqualTo(550.0);
        assertThat(position.getAverageSellPrice()).isEqualTo(110.0);
        assertThat(position.getNetQty()).isEqualTo(-5L);
        assertThat(position.isShortPosition()).isTrue();
    }

    @Test
    void testRealizedPnLCalculation() {
        // 100で10枚買い
        position.addBuyTrade(10L, 100.0);
        assertThat(position.getRealizedPnL()).isEqualTo(0.0);

        // 110で5枚売り（利益50）
        position.addSellTrade(5L, 110.0);
        assertThat(position.getRealizedPnL()).isEqualTo(50.0); // 5 * (110 - 100)
        assertThat(position.getNetQty()).isEqualTo(5L);
    }

    @Test
    void testMultipleBuyTrades() {
        position.addBuyTrade(10L, 100.0);  // 10 * 100 = 1000
        position.addBuyTrade(5L, 120.0);   // 5 * 120 = 600

        assertThat(position.getTotalBuyQty()).isEqualTo(15L);
        assertThat(position.getTotalBuyAmount()).isEqualTo(1600.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(1600.0 / 15.0);
    }

    @Test
    void testUnrealizedPnL() {
        position.addBuyTrade(10L, 100.0);

        // 現在価格110の場合、含み益は10 * (110 - 100) = 100
        assertThat(position.getUnrealizedPnL(110.0)).isEqualTo(100.0);

        // 現在価格90の場合、含み損は10 * (90 - 100) = -100
        assertThat(position.getUnrealizedPnL(90.0)).isEqualTo(-100.0);
    }

    @Test
    void testTotalPnL() {
        position.addBuyTrade(10L, 100.0);
        position.addSellTrade(5L, 110.0); // 実現損益 50

        // 残り5枚、現在価格105の場合
        // 実現損益50 + 含み損益25 = 75
        assertThat(position.getTotalPnL(105.0)).isEqualTo(75.0);
    }

    @Test
    void testInvalidTradeParameters() {
        assertThrows(IllegalArgumentException.class, () -> position.addBuyTrade(0L, 100.0));
        assertThrows(IllegalArgumentException.class, () -> position.addBuyTrade(10L, 0.0));
        assertThrows(IllegalArgumentException.class, () -> position.addBuyTrade(-5L, 100.0));
        assertThrows(IllegalArgumentException.class, () -> position.addSellTrade(0L, 100.0));
        assertThrows(IllegalArgumentException.class, () -> position.addSellTrade(10L, -100.0));
    }

    @Test
    void testShortPositionUnrealizedPnL() {
        position.addSellTrade(10L, 100.0); // ショートポジション

        // 現在価格が90に下がった場合、利益 = 10 * (100 - 90) = 100
        assertThat(position.getUnrealizedPnL(90.0)).isEqualTo(100.0);

        // 現在価格が110に上がった場合、損失 = 10 * (100 - 110) = -100
        assertThat(position.getUnrealizedPnL(110.0)).isEqualTo(-100.0);
    }

    @Test
    void testComplexTradingScenario() {
        // 複数回の売買を行うシナリオ
        position.addBuyTrade(10L, 100.0);   // 10枚@100で買い, 平均100.0
        position.addSellTrade(3L, 110.0);   // 3枚@110で売り（実現損益: 3 * (110 - 100) = 30）

        // この時点で: 買い10枚, 売り3枚, ネット7枚, 実現損益30
        assertThat(position.getNetQty()).isEqualTo(7L);
        assertThat(position.getRealizedPnL()).isEqualTo(30.0);

        position.addBuyTrade(5L, 105.0);    // 5枚@105で買い
        // 平均買値 = (10*100 + 5*105) / 15 = 1525/15 = 101.67
        double newAverageBuyPrice = (10.0 * 100.0 + 5.0 * 105.0) / 15.0;

        position.addSellTrade(2L, 115.0);   // 2枚@115で売り
        // 実現損益 += 2 * (115 - 101.67) = 2 * 13.33 = 26.67
        // 総実現損益 = 30 + 26.67 = 56.67

        assertThat(position.getTotalBuyQty()).isEqualTo(15L);
        assertThat(position.getTotalSellQty()).isEqualTo(5L);
        assertThat(position.getNetQty()).isEqualTo(10L);

        // 実現損益の確認
        double expectedRealizedPnL = 30.0 + 2.0 * (115.0 - newAverageBuyPrice);
        assertThat(position.getRealizedPnL()).isCloseTo(expectedRealizedPnL, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void testPositionResetAfterFlat() {
        // ポジションがフラットになった後、平均価格がリセットされることを確認

        // 1. 100円で1BTC買う
        position.addBuyTrade(1.0, 100.0);
        assertThat(position.getNetQty()).isEqualTo(1.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(100.0);
        assertThat(position.getRealizedPnL()).isEqualTo(0.0);

        // 2. 110円で1BTC売る（ポジションフラット）
        position.addSellTrade(1.0, 110.0);
        assertThat(position.getNetQty()).isEqualTo(0.0);
        assertThat(position.getRealizedPnL()).isEqualTo(10.0); // 1 * (110 - 100)

        // フラット後は累積値がリセットされる
        assertThat(position.getTotalBuyQty()).isEqualTo(0.0);
        assertThat(position.getTotalSellQty()).isEqualTo(0.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(0.0);
        assertThat(position.getAverageSellPrice()).isEqualTo(0.0);

        // 3. 120円で1BTC買う（新しいポジション開始）
        position.addBuyTrade(1.0, 120.0);
        assertThat(position.getNetQty()).isEqualTo(1.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(120.0); // 過去の100円は影響しない
        assertThat(position.getTotalBuyQty()).isEqualTo(1.0);

        // 4. 130円で1BTC売る
        position.addSellTrade(1.0, 130.0);
        assertThat(position.getNetQty()).isEqualTo(0.0);
        assertThat(position.getRealizedPnL()).isEqualTo(20.0); // 10 + 1 * (130 - 120)
    }

    @Test
    void testLongToShortReversal() {
        // ロングからショートへの反転時に買いの累積値がリセットされることを確認

        // 1. 100円で2BTC買う
        position.addBuyTrade(2.0, 100.0);
        assertThat(position.getNetQty()).isEqualTo(2.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(100.0);

        // 2. 110円で3BTC売る（ロング→ショートに反転）
        position.addSellTrade(3.0, 110.0);
        assertThat(position.getNetQty()).isEqualTo(-1.0); // ショートポジション
        assertThat(position.getRealizedPnL()).isEqualTo(20.0); // 2 * (110 - 100)

        // 反転後、買いの累積値はリセットされる
        assertThat(position.getTotalBuyQty()).isEqualTo(0.0);
        assertThat(position.getTotalBuyAmount()).isEqualTo(0.0);
        assertThat(position.getAverageBuyPrice()).isEqualTo(0.0);

        // 売りの累積値は新しいショートポジションのサイズに調整される
        assertThat(position.getTotalSellQty()).isEqualTo(1.0); // 反転後のnetQtyに合わせる
        assertThat(position.getAverageSellPrice()).isEqualTo(110.0); // 反転を引き起こしたトレードの価格

        // 3. 105円で1BTC買い戻す（ショートポジション決済）
        position.addBuyTrade(1.0, 105.0);
        assertThat(position.getNetQty()).isEqualTo(0.0);
        assertThat(position.getRealizedPnL()).isEqualTo(25.0); // 20 + 1 * (110 - 105)
    }

    @Test
    void testShortToLongReversal() {
        // ショートからロングへの反転時に売りの累積値がリセットされることを確認

        // 1. 100円で2BTC売る（ショートポジション）
        position.addSellTrade(2.0, 100.0);
        assertThat(position.getNetQty()).isEqualTo(-2.0);
        assertThat(position.getAverageSellPrice()).isEqualTo(100.0);

        // 2. 90円で3BTC買う（ショート→ロングに反転）
        position.addBuyTrade(3.0, 90.0);
        assertThat(position.getNetQty()).isEqualTo(1.0); // ロングポジション
        assertThat(position.getRealizedPnL()).isEqualTo(20.0); // 2 * (100 - 90)

        // 反転後、売りの累積値はリセットされる
        assertThat(position.getTotalSellQty()).isEqualTo(0.0);
        assertThat(position.getTotalSellAmount()).isEqualTo(0.0);
        assertThat(position.getAverageSellPrice()).isEqualTo(0.0);

        // 買いの累積値は新しいロングポジションのサイズに調整される
        assertThat(position.getTotalBuyQty()).isEqualTo(1.0); // 反転後のnetQtyに合わせる
        assertThat(position.getAverageBuyPrice()).isEqualTo(90.0); // 反転を引き起こしたトレードの価格

        // 3. 95円で1BTC売る（ロングポジション決済）
        position.addSellTrade(1.0, 95.0);
        assertThat(position.getNetQty()).isEqualTo(0.0);
        assertThat(position.getRealizedPnL()).isEqualTo(25.0); // 20 + 1 * (95 - 90)
    }
}