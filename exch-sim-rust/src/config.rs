use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub server: ServerConfig,
    pub postgres: PostgresConfig,
    pub websocket: WebSocketConfig,
    pub jwt: JwtConfig,
    pub instruments: InstrumentsConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub port: u16,
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WebSocketConfig {
    pub bitflyer: BitflyerWebSocketConfig,
    pub gmo: GmoWebSocketConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BitflyerWebSocketConfig {
    pub enabled: bool,
    pub url: String,
    pub reconnect_delay_ms: u64,
    pub max_reconnect_attempts: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GmoWebSocketConfig {
    pub enabled: bool,
    pub url: String,
    pub reconnect_delay_ms: u64,
    pub max_reconnect_attempts: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JwtConfig {
    pub secret: String,
    pub expiration_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstrumentsConfig {
    #[serde(flatten)]
    pub instruments: std::collections::HashMap<String, InstrumentDefinition>,
}

impl Default for InstrumentsConfig {
    fn default() -> Self {
        Self {
            instruments: std::collections::HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstrumentDefinition {
    pub name: String,
    pub price_multiplier: u64,
    pub qty_multiplier: u64,
    #[serde(rename = "type")]
    pub instrument_type: String, // "Cash" or "FX"
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

    pub fn is_valid_symbol(&self, symbol: &str) -> bool {
        self.instruments.instruments.contains_key(symbol)
    }

    pub fn get_instrument(&self, symbol: &str) -> Option<&InstrumentDefinition> {
        self.instruments.instruments.get(symbol)
    }

    pub fn get_all_symbols(&self) -> Vec<String> {
        self.instruments.instruments.keys().cloned().collect()
    }
}

