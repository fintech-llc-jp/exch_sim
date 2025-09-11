package com.ys.exch_sim.domain.bigquery;

import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BigQueryEntityTest {

    @Test
    void testBigQueryExecutionEntityCreation() {
        // BigQueryExecutionEntityの作成テスト
        BigQueryExecutionEntity execution = new BigQueryExecutionEntity();
        execution.setExecId("test-exec-123");
        execution.setOrderId("test-order-456");
        execution.setUsername("test-user");
        execution.setSymbol("BTCJPY");
        execution.setExecStatus(ExecStatus.FILLED.toString());
        execution.setLastPx(10000000L); // 100.0 * 100000
        execution.setLastQty(1000L); // 1.0 * 1000
        execution.setCounterPartyUsername("market-maker");
        execution.setCreatedAt(LocalDateTime.now().toString());
        execution.setIsMarketMaker(false);
        execution.setSide("BUY");

        // BigQueryRow変換のテスト
        Map<String, Object> row = execution.toBigQueryRow();
        
        assertThat(row.get("exec_id")).isEqualTo("test-exec-123");
        assertThat(row.get("order_id")).isEqualTo("test-order-456");
        assertThat(row.get("username")).isEqualTo("test-user");
        assertThat(row.get("symbol")).isEqualTo("BTCJPY");
        assertThat(row.get("exec_status")).isEqualTo("FILLED");
        assertThat(row.get("last_px")).isEqualTo(10000000L);
        assertThat(row.get("last_qty")).isEqualTo(1000L);
        assertThat(row.get("counter_party_username")).isEqualTo("market-maker");
        assertThat(row.get("is_market_maker")).isEqualTo(false);
        assertThat(row.get("side")).isEqualTo("BUY");
        
        System.out.println("✅ BigQueryExecutionEntity test passed");
        System.out.println("   Execution ID: " + execution.getExecId());
        System.out.println("   BigQuery row: " + row);
    }

    @Test
    void testBigQueryPositionEntityCreation() {
        // Positionドメインオブジェクトを作成
        Position position = new Position("test-user", "BTCJPY");
        position.addBuyTrade(1.0, 100.0); // 1.0 BTC at 100.0
        position.addSellTrade(0.5, 110.0);  // 0.5 BTC at 110.0
        
        // BigQueryPositionEntityに変換
        BigQueryPositionEntity positionEntity = new BigQueryPositionEntity(position);
        
        // BigQueryRow変換のテスト
        Map<String, Object> row = positionEntity.toBigQueryRow();
        
        // id field was removed from BigQuery positions table schema
        assertThat(row.get("username")).isEqualTo("test-user");
        assertThat(row.get("symbol")).isEqualTo("BTCJPY");
        assertThat(row.get("total_buy_qty")).isEqualTo(1000L); // 1.0 * 1000 multiplier
        assertThat(row.get("total_buy_amount")).isEqualTo(100.0); // 1.0 * 100.0 price
        assertThat(row.get("total_sell_qty")).isEqualTo(500L); // 0.5 * 1000 multiplier
        assertThat(row.get("total_sell_amount")).isEqualTo(55.0); // 0.5 * 110.0 price
        assertThat(row.get("net_qty")).isEqualTo(500L); // (1.0 - 0.5) * 1000 multiplier
        assertThat(row.get("realized_pnl")).isEqualTo(5.0); // (110-100) * 0.5
        
        System.out.println("✅ BigQueryPositionEntity test passed");
        System.out.println("   Position Username: " + positionEntity.getUsername());
        System.out.println("   Position Symbol: " + positionEntity.getSymbol());
        System.out.println("   Net Quantity: " + row.get("net_qty"));
        System.out.println("   Realized PnL: " + row.get("realized_pnl"));
    }

    @Test
    void testBigQueryTradeHistoryEntityCreation() {
        // TradeHistoryドメインオブジェクトを作成
        TradeHistory tradeHistory = new TradeHistory(
            "test-exec-789",
            "test-trader",
            "BTCJPY", 
            "BUY",
            1.5, // quantity (1.5 BTC)
            95.0,   // price
            "market-maker-001",
            "test-order-789"
        );
        
        // BigQueryTradeHistoryEntityに変換
        BigQueryTradeHistoryEntity tradeHistoryEntity = new BigQueryTradeHistoryEntity(tradeHistory);
        
        // BigQueryRow変換のテスト
        Map<String, Object> row = tradeHistoryEntity.toBigQueryRow();
        
        assertThat(row.get("exec_id")).isEqualTo("test-exec-789");
        assertThat(row.get("username")).isEqualTo("test-trader");
        assertThat(row.get("symbol")).isEqualTo("BTCJPY");
        assertThat(row.get("side")).isEqualTo("BUY");
        assertThat(row.get("quantity")).isEqualTo(1500.0); // 1.5 * 1000 multiplier for storage
        assertThat(row.get("price")).isEqualTo(95.0);
        assertThat(row.get("amount")).isEqualTo(142.5); // 1.5 * 95
        assertThat(row.get("counter_party_username")).isEqualTo("market-maker-001");
        assertThat(row.get("cl_ord_id")).isEqualTo("test-order-789");
        assertThat(row.get("is_market_maker")).isEqualTo(false);
        
        System.out.println("✅ BigQueryTradeHistoryEntity test passed");
        System.out.println("   Trade Exec ID: " + tradeHistoryEntity.getExecId());
        System.out.println("   Trade Amount: " + row.get("amount"));
    }

    @Test
    void testEntityRoundTripConversion() {
        // BigQueryエンティティとドメインオブジェクト間の変換テスト
        
        // 1. Position round trip
        Position originalPosition = new Position("round-trip-user", "ETHJPY");
        originalPosition.addBuyTrade(2.0, 200.0);
        originalPosition.addSellTrade(1.0, 220.0);
        
        BigQueryPositionEntity positionEntity = new BigQueryPositionEntity(originalPosition);
        Map<String, Object> positionRow = positionEntity.toBigQueryRow();
        
        // BigQuery row から復元
        BigQueryPositionEntity restoredPositionEntity = BigQueryPositionEntity.fromBigQueryRow(positionRow);
        Position restoredPosition = restoredPositionEntity.toPosition();
        
        assertThat(restoredPosition.getUsername()).isEqualTo(originalPosition.getUsername());
        assertThat(restoredPosition.getSymbol()).isEqualTo(originalPosition.getSymbol());
        assertThat(restoredPosition.getNetQty()).isEqualTo(originalPosition.getNetQty());
        assertThat(restoredPosition.getRealizedPnL()).isEqualTo(originalPosition.getRealizedPnL());
        
        System.out.println("✅ Position round-trip conversion test passed");
        
        // 2. TradeHistory round trip
        TradeHistory originalTrade = new TradeHistory(
            "round-trip-exec",
            "round-trip-user",
            "ETHJPY",
            "SELL", 
            0.8,
            230.0,
            "round-trip-mm",
            "round-trip-order"
        );
        
        BigQueryTradeHistoryEntity tradeEntity = new BigQueryTradeHistoryEntity(originalTrade);
        Map<String, Object> tradeRow = tradeEntity.toBigQueryRow();
        
        // BigQuery row から復元
        BigQueryTradeHistoryEntity restoredTradeEntity = BigQueryTradeHistoryEntity.fromBigQueryRow(tradeRow);
        TradeHistory restoredTrade = restoredTradeEntity.toTradeHistory();
        
        assertThat(restoredTrade.getExecID()).isEqualTo(originalTrade.getExecID());
        assertThat(restoredTrade.getQuantity()).isEqualTo(originalTrade.getQuantity());
        assertThat(restoredTrade.getPrice()).isEqualTo(originalTrade.getPrice());
        assertThat(restoredTrade.getAmount()).isEqualTo(originalTrade.getAmount());
        
        System.out.println("✅ TradeHistory round-trip conversion test passed");
    }

    @Test
    void testTableIdGeneration() {
        // BigQueryテーブルID生成のテスト
        String projectId = "test-project";
        String datasetId = "test-dataset";
        
        assertThat(BigQueryExecutionEntity.getTableId(projectId, datasetId).getProject()).isEqualTo(projectId);
        assertThat(BigQueryExecutionEntity.getTableId(projectId, datasetId).getDataset()).isEqualTo(datasetId);
        assertThat(BigQueryExecutionEntity.getTableId(projectId, datasetId).getTable()).isEqualTo("executions");
        
        assertThat(BigQueryPositionEntity.getTableId(projectId, datasetId).getTable()).isEqualTo("positions");
        assertThat(BigQueryTradeHistoryEntity.getTableId(projectId, datasetId).getTable()).isEqualTo("trade_history");
        
        System.out.println("✅ TableId generation test passed");
        System.out.println("   Executions table: " + BigQueryExecutionEntity.getTableId(projectId, datasetId));
        System.out.println("   Positions table: " + BigQueryPositionEntity.getTableId(projectId, datasetId));
        System.out.println("   Trade history table: " + BigQueryTradeHistoryEntity.getTableId(projectId, datasetId));
    }

    @Test
    void testBigQueryExecutionEntityFromExternalTrade() {
        // 外部取引データから作成されたExecutionのテスト（Order オブジェクトなし）
        Execution externalExecution = new Execution(
            "ext-exec-123",         // execID
            "ext-order-456",        // orderID (fake)
            "EXTERNAL_FEED",        // username
            "G_BTCJPY",            // symbol
            ExecStatus.FILLED,      // execStatus
            10500000L,             // lastPx (105.0 * 100000)
            1500L,                 // lastQty (1.5 * 1000)
            "MARKET",              // counterPartyUsername
            LocalDateTime.now(ZoneOffset.UTC), // createdAt
            false,                 // isMarketMaker
            "BUY"                  // side
        );
        
        // BigQueryExecutionEntityに変換（例外が発生しないことを確認）
        BigQueryExecutionEntity bigQueryEntity = new BigQueryExecutionEntity(externalExecution);
        
        // 基本フィールドの検証
        assertThat(bigQueryEntity.getExecId()).isEqualTo("ext-exec-123");
        assertThat(bigQueryEntity.getOrderId()).isEqualTo("ext-order-456");
        assertThat(bigQueryEntity.getUsername()).isEqualTo("EXTERNAL_FEED");
        assertThat(bigQueryEntity.getSymbol()).isEqualTo("G_BTCJPY");
        assertThat(bigQueryEntity.getExecStatus()).isEqualTo("FILLED");
        assertThat(bigQueryEntity.getLastPx()).isEqualTo(10500000L);
        assertThat(bigQueryEntity.getLastQty()).isEqualTo(1500L);
        assertThat(bigQueryEntity.getCounterPartyUsername()).isEqualTo("MARKET");
        assertThat(bigQueryEntity.getIsMarketMaker()).isEqualTo(false);
        assertThat(bigQueryEntity.getSide()).isEqualTo("BUY");
        
        // clOrdId はorderIDと同じ値になることを確認（Order オブジェクトがないため）
        assertThat(bigQueryEntity.getClOrdId()).isEqualTo("ext-order-456");
        
        // BigQueryRow変換のテスト
        Map<String, Object> row = bigQueryEntity.toBigQueryRow();
        assertThat(row.get("exec_id")).isEqualTo("ext-exec-123");
        assertThat(row.get("order_id")).isEqualTo("ext-order-456");
        assertThat(row.get("cl_ord_id")).isEqualTo("ext-order-456");
        assertThat(row.get("username")).isEqualTo("EXTERNAL_FEED");
        assertThat(row.get("symbol")).isEqualTo("G_BTCJPY");
        assertThat(row.get("side")).isEqualTo("BUY");
        
        System.out.println("✅ External trade BigQueryExecutionEntity test passed");
        System.out.println("   Exec ID: " + bigQueryEntity.getExecId());
        System.out.println("   Order ID: " + bigQueryEntity.getOrderId());
        System.out.println("   ClOrd ID: " + bigQueryEntity.getClOrdId());
        System.out.println("   Username: " + bigQueryEntity.getUsername());
    }
}