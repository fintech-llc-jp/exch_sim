use crate::config::Config;
use crate::models::MarketBoardSnapshot;
use anyhow::Result;
use sqlx::PgPool;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::interval;
use tracing::{error, info, warn};

pub struct PostgresWriter {
    config: Config,
    pool: PgPool,
    snapshot_rx: mpsc::Receiver<MarketBoardSnapshot>,
    queue: Vec<MarketBoardSnapshot>,
    queue_size_limit: usize,
}

impl PostgresWriter {
    pub async fn new(
        config: Config,
        pool: PgPool,
        snapshot_rx: mpsc::Receiver<MarketBoardSnapshot>,
    ) -> Result<Self> {
        Ok(Self {
            queue_size_limit: config.snapshot.queue_size_limit,
            config,
            pool,
            snapshot_rx,
            queue: Vec::new(),
        })
    }

    pub async fn start(&mut self) -> Result<()> {
        let batch_interval = Duration::from_secs(self.config.postgres_writer.batch_interval_seconds);
        let max_batch_size = self.config.postgres_writer.max_batch_size;
        let mut interval_timer = interval(batch_interval);

        info!(
            "PostgreSQL Writer started: batch_interval={}s, max_batch_size={}",
            batch_interval.as_secs(),
            max_batch_size
        );

        loop {
            tokio::select! {
                // Receive snapshots from collector
                result = self.snapshot_rx.recv() => {
                    match result {
                        Some(snapshot) => {
                            let symbol = snapshot.symbol.clone();
                            if self.queue.len() >= self.queue_size_limit {
                                // Drop oldest snapshot if queue is full
                                let dropped = self.queue.remove(0);
                                warn!(
                                    "Queue full ({}), dropped snapshot for symbol: {}",
                                    self.queue_size_limit,
                                    dropped.symbol
                                );
                            }
                            let queue_size = self.queue.len() + 1;
                            self.queue.push(snapshot);
                            tracing::debug!("Received snapshot for symbol: {} (queue size: {})", symbol, queue_size);
                        }
                        None => {
                            info!("Snapshot channel closed, flushing remaining snapshots");
                            break;
                        }
                    }
                }
                // Time-based batch write
                _ = interval_timer.tick() => {
                    if !self.queue.is_empty() {
                        let batch_size = self.queue.len().min(max_batch_size);
                        let batch: Vec<MarketBoardSnapshot> = self.queue.drain(..batch_size).collect();
                        
                        info!("Writing batch of {} snapshots to PostgreSQL (queue size: {})", batch.len(), self.queue.len());
                        match self.write_batch(batch.clone()).await {
                            Ok(_) => {
                                info!("Successfully wrote batch of {} snapshots", batch.len());
                            }
                            Err(e) => {
                                error!("Failed to write batch: {}", e);
                                // Re-queue failed snapshots (optional: could implement retry logic)
                                // For now, we'll just log the error
                            }
                        }
                    } else {
                        tracing::debug!("Batch write interval ticked, but queue is empty");
                    }
                }
            }
        }

        // Flush remaining snapshots
        if !self.queue.is_empty() {
            info!("Flushing {} remaining snapshots", self.queue.len());
            let _ = self.write_batch(self.queue.clone()).await;
        }

        Ok(())
    }

    async fn write_batch(&self, snapshots: Vec<MarketBoardSnapshot>) -> Result<()> {
        if snapshots.is_empty() {
            return Ok(());
        }

        let mut tx = self.pool.begin().await?;

        for snapshot in snapshots {
            // Insert snapshot
            let snapshot_id: i64 = sqlx::query_scalar(
                r#"
                INSERT INTO market_board_snapshots (symbol, timestamp)
                VALUES ($1, $2)
                RETURNING id
                "#,
            )
            .bind(&snapshot.symbol)
            .bind(snapshot.timestamp)
            .fetch_one(&mut *tx)
            .await?;

            // Insert price levels
            for bid in &snapshot.bids {
                sqlx::query(
                    r#"
                    INSERT INTO market_board_price_levels 
                    (snapshot_id, price, quantity, side, level_index)
                    VALUES ($1, $2, $3, $4, $5)
                    "#,
                )
                .bind(snapshot_id)
                .bind(bid.price)
                .bind(bid.quantity)
                .bind(&bid.side)
                .bind(bid.level_index)
                .execute(&mut *tx)
                .await?;
            }

            for ask in &snapshot.asks {
                sqlx::query(
                    r#"
                    INSERT INTO market_board_price_levels 
                    (snapshot_id, price, quantity, side, level_index)
                    VALUES ($1, $2, $3, $4, $5)
                    "#,
                )
                .bind(snapshot_id)
                .bind(ask.price)
                .bind(ask.quantity)
                .bind(&ask.side)
                .bind(ask.level_index)
                .execute(&mut *tx)
                .await?;
            }
        }

        tx.commit().await?;
        Ok(())
    }
}

