use bcrypt::{hash, verify, DEFAULT_COST};

fn main() {
    let password = "testpass123";
    let hash_in_db = "$2a$10$EEI8HF/nHbK.EpQP/8yDFOKaxkELPIwC9RGCGp4ftyVDGBNfv1iwy";
    
    println!("Testing password verification...");
    println!("Password: {}", password);
    println!("Hash in DB: {}", hash_in_db);
    
    match verify(password, hash_in_db) {
        Ok(is_valid) => {
            println!("Verification result: {}", is_valid);
            if !is_valid {
                println!("Password does not match!");
            }
        }
        Err(e) => {
            println!("Error: {:?}", e);
        }
    }
    
    // Try to hash the password to see what we get
    match hash(password, DEFAULT_COST) {
        Ok(new_hash) => {
            println!("New hash: {}", new_hash);
            match verify(password, &new_hash) {
                Ok(is_valid) => println!("New hash verification: {}", is_valid),
                Err(e) => println!("New hash verification error: {:?}", e),
            }
        }
        Err(e) => {
            println!("Hash error: {:?}", e);
        }
    }
}
