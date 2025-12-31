use anyhow::Result;
use bcrypt::{hash, verify, DEFAULT_COST};

pub struct PasswordService;

impl PasswordService {
    pub fn hash_password(password: &str) -> Result<String> {
        hash(password, DEFAULT_COST).map_err(|e| anyhow::anyhow!("Failed to hash password: {}", e))
    }

    pub fn verify_password(password: &str, hash: &str) -> Result<bool> {
        verify(password, hash).map_err(|e| anyhow::anyhow!("Failed to verify password: {}", e))
    }
}

