package com.ys.exch_sim.domain.market_board;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 板データのスナップショット（PostgreSQL用エンティティ）
 * 1秒ごとの板データを記録する
 */
@Entity
@Table(name = "market_board_snapshots", indexes = {
    @Index(name = "idx_market_board_snapshots_symbol_timestamp", columnList = "symbol,timestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketBoardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<PriceLevel> bids = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<PriceLevel> asks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * 価格レベル（Bid/Ask）を表すエンティティ
     */
    @Entity
    @Table(name = "market_board_price_levels")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceLevel {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "snapshot_id", nullable = false)
        private MarketBoardSnapshot snapshot;

        @Column(name = "price", nullable = false)
        private Double price;

        @Column(name = "quantity", nullable = false)
        private Double quantity;

        @Column(name = "side", nullable = false, length = 3)
        private String side; // "BID" or "ASK"

        @Column(name = "level_index", nullable = false)
        private Integer levelIndex; // 板の深さ（0が最良価格）
    }
}

