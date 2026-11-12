//! Adatmodell: kamerák és gyártói sablonok. A JSON alak megegyezik az Android app
//! (ipCamApp) formátumával, így a mentések és QR-kódok a két alkalmazás között átvihetők.

mod camera;
mod preset;

pub use camera::*;
pub use preset::*;
