package com.ys.exch_sim.domain.bigquery;

import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * BigQuery書き込みをキューイングするための統合エンティティ
 * 複数の異なるタイプのデータを1つのキューで管理し、
 * 別スレッドのバッチ処理で効率的にBigQueryに書き込む
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BigQueryEntity {
    /**
     * エンティティのタイプ
     */
    private EntityType type;

    /**
     * 約定データ（type == EXECUTION の場合に使用）
     */
    private Execution execution;

    /**
     * ポジションデータ（type == POSITION の場合に使用）
     */
    private Position position;

    /**
     * 取引履歴データ（type == TRADE_HISTORY の場合に使用）
     */
    private TradeHistory tradeHistory;

    /**
     * エンティティタイプの列挙型
     */
    public enum EntityType {
        EXECUTION("実行"),
        POSITION("ポジション"),
        TRADE_HISTORY("取引履歴");

        private final String description;

        EntityType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 約定データのエンティティを作成
     */
    public static BigQueryEntity execution(Execution execution) {
        return new BigQueryEntity(EntityType.EXECUTION, execution, null, null);
    }

    /**
     * ポジションデータのエンティティを作成
     */
    public static BigQueryEntity position(Position position) {
        return new BigQueryEntity(EntityType.POSITION, null, position, null);
    }

    /**
     * 取引履歴データのエンティティを作成
     */
    public static BigQueryEntity tradeHistory(TradeHistory tradeHistory) {
        return new BigQueryEntity(EntityType.TRADE_HISTORY, null, null, tradeHistory);
    }

    @Override
    public String toString() {
        return "BigQueryEntity{" +
                "type=" + type +
                ", execution=" + (execution != null ? execution.getExecID() : null) +
                ", position=" + (position != null ? position.getUsername() + "_" + position.getSymbol() : null) +
                ", tradeHistory=" + (tradeHistory != null ? tradeHistory.getExecID() : null) +
                '}';
    }
}
