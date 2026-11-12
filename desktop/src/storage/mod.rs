//! Tárolás: beállítások, kamerák/sablonok lemezre írása (DPAPI-titkosított jelszavakkal),
//! mentés/visszaállítás.

pub mod backup;
pub mod crypto;
mod settings;
mod store;

pub use settings::*;
pub use store::*;
