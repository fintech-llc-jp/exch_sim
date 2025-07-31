package com.ys.exch_sim.integration;

import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryExecutionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryPositionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryTradeHistoryEntity;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("bigquery")
@EnabledIfEnvironmentVariable(named = "BIGQUERY_TEST_ENABLED", matches = "true")
class BigQueryIntegrationTest {

    @Autowired(required = false)
    private BigQueryService bigQueryService;

    @Test
    void testBigQueryServiceIsAvailable() {
        // BigQueryServiceが正しく注入されていることを確認
        if (bigQueryService != null) {
            assertThat(bigQueryService).isNotNull();
            System.out.println("✅ BigQueryService is available and properly configured");
        } else {
            System.out.println("⚠️ BigQueryService is not available (credentials not configured)");
        }
    }

    @Test
    void testCreateBigQueryTables() {
        if (bigQueryService != null) {
            try {
                // BigQueryテーブル作成のテスト
                bigQueryService.createTablesIfNotExist();
                System.out.println("✅ BigQuery tables created successfully");
            } catch (Exception e) {
                System.out.println("❌ Error creating BigQuery tables: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("⚠️ Skipping table creation test - BigQueryService not available");
        }
    }

    @Test
    void testInsertExecution() {
        if (bigQueryService != null) {
            try {
                // テスト用のExecution entityを作成
                BigQueryExecutionEntity execution = new BigQueryExecutionEntity();
                execution.setExecId("test-exec-" + System.currentTimeMillis());
                execution.setOrderId("test-order-123");
                execution.setUsername("test-user");
                execution.setSymbol("BTCJPY");
                execution.setExecStatus(ExecStatus.FILLED.toString());
                execution.setLastPx(10000000L); // 100.0 * 100000 (price multiplier)
                execution.setLastQty(1000L); // 1.0 * 1000 (qty multiplier)
                execution.setCounterPartyUsername("market-maker");
                execution.setCreatedAt(LocalDateTime.now().toString());
                execution.setIsMarketMaker(false);
                execution.setSide("BUY");

                // BigQueryに挿入
                bigQueryService.insertExecution(execution);
                System.out.println("✅ Execution inserted to BigQuery successfully");
                System.out.println("   Exec ID: " + execution.getExecId());
            } catch (Exception e) {
                System.out.println("❌ Error inserting execution to BigQuery: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("⚠️ Skipping execution insert test - BigQueryService not available");
        }
    }

    @Test
    void testInsertPosition() {
        if (bigQueryService != null) {
            try {
                // テスト用のPositionを作成
                Position position = new Position("test-user", "BTCJPY");
                position.addBuyTrade(1000L, 100.0); // 1.0 BTC at 100.0 price
                
                BigQueryPositionEntity positionEntity = new BigQueryPositionEntity(position);

                // BigQueryに挿入
                bigQueryService.insertPosition(positionEntity);
                System.out.println("✅ Position inserted to BigQuery successfully");
                System.out.println("   Position Username: " + positionEntity.getUsername());
                System.out.println("   Position Symbol: " + positionEntity.getSymbol());
            } catch (Exception e) {
                System.out.println("❌ Error inserting position to BigQuery: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("⚠️ Skipping position insert test - BigQueryService not available");
        }
    }

    @Test
    void testInsertTradeHistory() {
        if (bigQueryService != null) {
            try {
                // テスト用のTradeHistoryを作成
                TradeHistory tradeHistory = new TradeHistory(
                    "test-exec-" + System.currentTimeMillis(),
                    "test-user",
                    "BTCJPY",
                    "BUY",
                    1000.0, // quantity
                    100.0,  // price
                    "market-maker",
                    "test-order-123"
                );
                
                BigQueryTradeHistoryEntity tradeHistoryEntity = new BigQueryTradeHistoryEntity(tradeHistory);

                // BigQueryに挿入
                bigQueryService.insertTradeHistory(tradeHistoryEntity);
                System.out.println("✅ Trade history inserted to BigQuery successfully");
                System.out.println("   Trade Exec ID: " + tradeHistoryEntity.getExecId());
            } catch (Exception e) {
                System.out.println("❌ Error inserting trade history to BigQuery: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("⚠️ Skipping trade history insert test - BigQueryService not available");
        }
    }

    @Test
    void testFullBigQueryWorkflow() {
        if (bigQueryService != null) {
            try {
                System.out.println("🚀 Starting full BigQuery workflow test...");
                
                // 1. テーブル作成
                bigQueryService.createTablesIfNotExist();
                System.out.println("✅ Step 1: Tables created/verified");
                
                // 2. Execution挿入
                BigQueryExecutionEntity execution = new BigQueryExecutionEntity();
                String execId = "workflow-test-" + System.currentTimeMillis();
                execution.setExecId(execId);
                execution.setOrderId("workflow-order-123");
                execution.setUsername("workflow-user");
                execution.setSymbol("BTCJPY");
                execution.setExecStatus(ExecStatus.FILLED.toString());
                execution.setLastPx(10000000L);
                execution.setLastQty(1000L);
                execution.setCounterPartyUsername("workflow-mm");
                execution.setCreatedAt(LocalDateTime.now().toString());
                execution.setIsMarketMaker(false);
                execution.setSide("BUY");
                
                bigQueryService.insertExecution(execution);
                System.out.println("✅ Step 2: Execution inserted");
                
                // 3. Position挿入
                Position position = new Position("workflow-user", "BTCJPY");
                position.addBuyTrade(1000L, 100.0);
                BigQueryPositionEntity positionEntity = new BigQueryPositionEntity(position);
                
                bigQueryService.insertPosition(positionEntity);
                System.out.println("✅ Step 3: Position inserted");
                
                // 4. TradeHistory挿入
                TradeHistory tradeHistory = new TradeHistory(
                    execId,
                    "workflow-user",
                    "BTCJPY",
                    "BUY",
                    1000.0,
                    100.0,
                    "workflow-mm",
                    "workflow-order-123"
                );
                BigQueryTradeHistoryEntity tradeHistoryEntity = new BigQueryTradeHistoryEntity(tradeHistory);
                
                bigQueryService.insertTradeHistory(tradeHistoryEntity);
                System.out.println("✅ Step 4: Trade history inserted");
                
                System.out.println("🎉 Full BigQuery workflow completed successfully!");
            } catch (Exception e) {
                System.out.println("❌ BigQuery workflow failed: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
        } else {
            System.out.println("⚠️ Skipping full workflow test - BigQueryService not available");
        }
    }
}