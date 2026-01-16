use crate::config::Config;
use crate::market_board::MarketBoardManager;
use crate::models::MarketBoardSnapshot;
use chrono::Utc;
use tokio::sync::mpsc;
use tokio::time::{interval, Duration};
use tracing::{error, info, warn};

pub struct SnapshotCollector {
    config: Config,
    board_manager: MarketBoardManager,
    snapshot_tx: mpsc::Sender<MarketBoardSnapshot>,
}

impl SnapshotCollector {
    pub fn new(
        config: Config,
        board_manager: MarketBoardManager,
        snapshot_tx: mpsc::Sender<MarketBoardSnapshot>,
    ) -> Self {
        Self {
            config,
            board_manager,
            snapshot_tx,
        }
    }

    pub async fn start(&self) {
        let interval_seconds = self.config.snapshot.interval_seconds;
        let max_levels = self.config.snapshot.max_levels;
        let mut interval_timer = interval(Duration::from_secs(interval_seconds));
        let snapshot_tx = self.snapshot_tx.clone();
        let board_manager = self.board_manager.clone();

        tokio::spawn(async move {
            loop {
                interval_timer.tick().await;

                match Self::collect_snapshots(&board_manager, max_levels).await {
                    Ok(snapshots) => {
                        info!("Collected {} snapshots, sending to queue", snapshots.len());
                        for snapshot in snapshots {
                            if let Err(e) = snapshot_tx.send(snapshot).await {
                                error!("Failed to send snapshot to queue: {}", e);
                                break;
                            }
                        }
                    }
                    Err(e) => {
                        error!("Error collecting snapshots: {}", e);
                    }
                }
            }
        });
    }

    async fn collect_snapshots(
        board_manager: &MarketBoardManager,
        max_levels: usize,
    ) -> anyhow::Result<Vec<MarketBoardSnapshot>> {
        let symbols = board_manager.get_available_symbols().await;
        info!("Collecting snapshots for {} symbols", symbols.len());
        let mut snapshots = Vec::new();
        let now = Utc::now();

        for symbol in symbols {
            if let Some((bids, asks)) = board_manager.get_snapshot(&symbol, max_levels).await {
                if !bids.is_empty() || !asks.is_empty() {
                    let snapshot = MarketBoardSnapshot {
                        symbol: symbol.clone(),
                        timestamp: now,
                        bids,
                        asks,
                    };
                    snapshots.push(snapshot);
                    info!("Collected snapshot for symbol: {}", symbol);
                } else {
                    warn!("Empty board for symbol: {}", symbol);
                }
            }
        }

        Ok(snapshots)
    }
}

