use serde::{Deserialize, Serialize};
use std::fs;
use anyhow::{Context, Result};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub websocket: WebSocketConfig,
    pub snapshot: SnapshotConfig,
    pub postgres_writer: PostgresWriterConfig,
    pub symbol_mapping: SymbolMappingConfig,
    pub postgres: PostgresConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WebSocketConfig {
    pub bitflyer: BitflyerConfig,
    pub gmo: GmoConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BitflyerConfig {
    pub enabled: bool,
    pub url: String,
    pub reconnect_delay_ms: u64,
    pub max_reconnect_attempts: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GmoConfig {
    pub enabled: bool,
    pub url: String,
    pub reconnect_delay_ms: u64,
    pub max_reconnect_attempts: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotConfig {
    pub interval_seconds: u64,
    pub max_levels: usize,
    pub queue_size_limit: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostgresWriterConfig {
    pub batch_interval_seconds: u64,
    pub max_batch_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SymbolMappingConfig {
    pub bitflyer: BitflyerSymbolMapping,
    pub gmo: GmoSymbolMapping,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BitflyerSymbolMapping {
    pub btc_jpy: String,
    #[serde(rename = "FX_BTC_JPY")]
    pub fx_btc_jpy: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GmoSymbolMapping {
    pub btc_jpy: String,
    pub btc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostgresConfig {
    pub host: String,
    pub port: u16,
    pub database: String,
    pub user: String,
    pub password: String,
    pub max_connections: u32,
}

impl Config {
    pub fn load(path: &str) -> Result<Self> {
        let content = fs::read_to_string(path)
            .with_context(|| format!("Failed to read config file: {}", path))?;
        let config: Config = toml::from_str(&content)
            .with_context(|| format!("Failed to parse config file: {}", path))?;
        Ok(config)
    }

    pub fn get_database_url(&self) -> String {
        format!(
            "postgresql://{}:{}@{}:{}/{}",
            self.postgres.user,
            self.postgres.password,
            self.postgres.host,
            self.postgres.port,
            self.postgres.database
        )
    }
}

