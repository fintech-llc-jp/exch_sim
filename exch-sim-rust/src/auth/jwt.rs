use crate::config::Config;
use anyhow::{Context, Result};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String, // username
    roles: Vec<String>,
    exp: usize, // expiration time
    iat: usize, // issued at
}

pub struct JwtService {
    encoding_key: EncodingKey,
    decoding_key: DecodingKey,
    expiration_seconds: u64,
}

impl JwtService {
    pub fn new(config: &Config) -> Result<Self> {
        let secret = config.jwt.secret.as_bytes();
        Ok(Self {
            encoding_key: EncodingKey::from_secret(secret),
            decoding_key: DecodingKey::from_secret(secret),
            expiration_seconds: config.jwt.expiration_seconds,
        })
    }

    pub fn generate_token(&self, username: &str, roles: &[String]) -> Result<String> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs() as usize;

        let claims = Claims {
            sub: username.to_string(),
            roles: roles.to_vec(),
            exp: now + self.expiration_seconds as usize,
            iat: now,
        };

        encode(&Header::default(), &claims, &self.encoding_key)
            .context("Failed to generate JWT token")
    }

    pub fn validate_token(&self, token: &str) -> Result<TokenInfo> {
        let validation = Validation::default();
        let token_data = decode::<Claims>(token, &self.decoding_key, &validation)
            .context("Failed to decode JWT token")?;

        Ok(TokenInfo {
            username: token_data.claims.sub,
            roles: token_data.claims.roles,
        })
    }
}

#[derive(Debug, Clone)]
pub struct TokenInfo {
    pub username: String,
    pub roles: Vec<String>,
}

