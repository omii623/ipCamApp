//! Felhasználói beállítások (téma, nyelv, kiemelőszín, keresési portok, nézet).

use serde_json::{Value, json};

#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    /// Android-kompatibilis értékek: -1 = rendszer, 1 = világos, 2 = sötét.
    pub night_mode: i32,
    /// Egyéni kiemelőszín ARGB-ben (Android Int); 0 = alapértelmezett.
    pub accent: i32,
    pub search_ports: String,
    /// "" = rendszer nyelve, "hu" / "en".
    pub language: String,
    /// Osztott nézet oszlopszáma; 0 = automatikus.
    pub columns: u32,
    /// Az utolsó nézet indításkor való visszaállításhoz: "" | "grid" | "cam:<id>" (csak asztali).
    pub last_view: String,
}

pub const NIGHT_SYSTEM: i32 = -1;
pub const NIGHT_LIGHT: i32 = 1;
pub const NIGHT_DARK: i32 = 2;

impl Default for Settings {
    fn default() -> Self {
        Settings {
            night_mode: NIGHT_DARK,
            accent: 0,
            search_ports: "554, 8554".into(),
            language: String::new(),
            columns: 0,
            last_view: String::new(),
        }
    }
}

impl Settings {
    pub fn to_json(&self) -> Value {
        json!({
            "night_mode": self.night_mode, "accent": self.accent,
            "search_ports": self.search_ports, "language": self.language,
            "columns": self.columns, "last_view": self.last_view,
        })
    }

    /// Tűrő beolvasás: hiányzó / hibás mező = alapérték.
    pub fn from_json(v: &Value) -> Settings {
        let d = Settings::default();
        let int = |k: &str| v.get(k).and_then(Value::as_i64);
        let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
        Settings {
            night_mode: int("night_mode").map(|x| x as i32).unwrap_or(d.night_mode),
            accent: int("accent").map(|x| x as i32).unwrap_or(d.accent),
            search_ports: s("search_ports").unwrap_or(d.search_ports),
            language: s("language").unwrap_or(d.language),
            columns: int("columns").map(|x| x.clamp(0, 8) as u32).unwrap_or(d.columns),
            last_view: s("last_view").unwrap_or(d.last_view),
        }
    }
}
