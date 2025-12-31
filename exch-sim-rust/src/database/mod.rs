pub mod trait_;
pub mod impl_;

#[cfg(test)]
pub mod mock;

#[cfg(test)]
pub mod test_helpers;

pub use trait_::DatabaseTrait;
pub use impl_::DatabaseImpl;

// 後方互換性のため、Database型エイリアスを提供
pub type Database = DatabaseImpl;

