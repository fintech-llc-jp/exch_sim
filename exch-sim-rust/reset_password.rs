use bcrypt::{hash, DEFAULT_COST};

fn main() {
    let password = "testpass123";
    match hash(password, DEFAULT_COST) {
        Ok(hashed) => {
            println!("{}", hashed);
        }
        Err(e) => {
            eprintln!("Error: {:?}", e);
            std::process::exit(1);
        }
    }
}


