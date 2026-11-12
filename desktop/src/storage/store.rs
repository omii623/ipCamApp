//! Adatok tárolása a %APPDATA%\ipCamDesktop mappában (cameras.json, presets.json, settings.json).
//! A kamerajelszavak DPAPI-val titkosítva kerülnek a lemezre.

use super::Settings;
use super::crypto;
use crate::model::{Camera, Preset};
use serde_json::Value;
use std::collections::HashMap;
use std::path::PathBuf;

pub struct Store {
    dir: PathBuf,
    /// Kamerák, amelyek jelszava nem fejthető vissza (id -> eredeti titkosított érték).
    /// Mentéskor változatlanul visszaírjuk, amíg a felhasználó új jelszót nem ad meg.
    locked: HashMap<String, String>,
}

impl Store {
    /// Az adatmappa: `IPCAM_DATA_DIR` környezeti változó (hordozható mód / tesztelés),
    /// egyébként %APPDATA%\ipCamDesktop.
    pub fn open() -> Store {
        let dir = std::env::var_os("IPCAM_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| dirs::config_dir().unwrap_or_else(|| PathBuf::from(".")).join("ipCamDesktop"));
        let _ = std::fs::create_dir_all(&dir);
        Store { dir, locked: HashMap::new() }
    }

    #[cfg(test)]
    pub fn at(dir: PathBuf) -> Store {
        let _ = std::fs::create_dir_all(&dir);
        Store { dir, locked: HashMap::new() }
    }

    pub fn locked_count(&self) -> usize {
        self.locked.len()
    }

    fn read(&self, name: &str) -> Option<Value> {
        let text = std::fs::read_to_string(self.dir.join(name)).ok()?;
        serde_json::from_str(&text).ok()
    }

    /// Írás ideiglenes fájlba, majd átnevezés (áramszünetnél se maradjon félkész fájl).
    fn write(&self, name: &str, v: &Value) -> std::io::Result<()> {
        let tmp = self.dir.join(format!("{name}.tmp"));
        std::fs::write(&tmp, serde_json::to_string_pretty(v).unwrap_or_default())?;
        std::fs::rename(&tmp, self.dir.join(name))
    }

    pub fn load_cameras(&mut self) -> Vec<Camera> {
        self.locked.clear();
        let Some(Value::Array(arr)) = self.read("cameras.json") else {
            return Vec::new();
        };
        let mut out = Vec::new();
        for o in &arr {
            if let Some((cam, blob)) = decode_entry(o, crypto::decrypt) {
                if let Some(b) = blob {
                    self.locked.insert(cam.id.clone(), b);
                }
                out.push(cam);
            }
        }
        out
    }

    pub fn save_cameras(&mut self, list: &[Camera]) -> std::io::Result<()> {
        // a már nem létező vagy új jelszót kapott kamerák zárolt blobja felesleges
        self.locked.retain(|id, _| list.iter().any(|c| &c.id == id && c.password.is_empty()));
        let arr: Vec<Value> = list
            .iter()
            .map(|c| encode_entry(c, self.locked.get(&c.id).map(String::as_str), crypto::encrypt))
            .collect();
        self.write("cameras.json", &Value::Array(arr))
    }

    pub fn load_presets(&self) -> Vec<Preset> {
        let Some(Value::Array(arr)) = self.read("presets.json") else {
            return Vec::new();
        };
        arr.iter().filter_map(Preset::from_json).collect()
    }

    pub fn save_presets(&self, list: &[Preset]) -> std::io::Result<()> {
        self.write("presets.json", &Value::Array(list.iter().map(Preset::to_json).collect()))
    }

    pub fn load_settings(&self) -> Settings {
        self.read("settings.json").map(|v| Settings::from_json(&v)).unwrap_or_default()
    }

    pub fn save_settings(&self, s: &Settings) -> std::io::Result<()> {
        self.write("settings.json", &s.to_json())
    }
}

/// Tárolt bejegyzés -> kamera. Ha a jelszó titkosított, de nem fejthető vissza, a kamera üres
/// jelszót kap, a második érték pedig az eredeti titkosított adat.
pub fn decode_entry(o: &Value, decrypt: impl Fn(&str) -> Option<String>) -> Option<(Camera, Option<String>)> {
    let cam = Camera::from_json(o)?;
    let enc = o.get("pw_enc").and_then(Value::as_bool).unwrap_or(false);
    if !enc || cam.password.is_empty() {
        return Some((cam, None));
    }
    Some(match decrypt(&cam.password) {
        Some(plain) => (Camera { password: plain, ..cam }, None),
        None => {
            let blob = cam.password.clone();
            (Camera { password: String::new(), ..cam }, Some(blob))
        }
    })
}

/// Kamera -> tárolt bejegyzés. Sikertelen titkosításnál nyíltan (pw_enc=false) –
/// inkább ez, mint a jelszó elvesztése.
pub fn encode_entry(cam: &Camera, locked: Option<&str>, encrypt: impl Fn(&str) -> Option<String>) -> Value {
    let mut o = cam.to_json();
    let (pw, enc) = if cam.password.is_empty() {
        match locked {
            Some(b) => (b.to_string(), true),
            None => (String::new(), false),
        }
    } else {
        match encrypt(&cam.password) {
            Some(e) => (e, true),
            None => (cam.password.clone(), false),
        }
    };
    o["password"] = Value::String(pw);
    o["pw_enc"] = Value::Bool(enc);
    o
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::StreamType;

    fn cam(pw: &str) -> Camera {
        let mut c = Camera::new("a", "1.2.3.4", 554, StreamType::Rtsp, "/s");
        c.password = pw.into();
        c
    }

    #[test]
    fn locked_password_survives() {
        let rev = |s: &str| Some(s.chars().rev().collect::<String>());
        let e = encode_entry(&cam("abc"), None, rev);
        assert_eq!(e["password"], "cba");
        // visszafejtés nem sikerül -> üres jelszó + blob
        let (c, blob) = decode_entry(&e, |_| None).unwrap();
        assert_eq!(c.password, "");
        assert_eq!(blob.as_deref(), Some("cba"));
        // újramentés üres jelszóval: a blob marad
        let e2 = encode_entry(&c, blob.as_deref(), rev);
        assert_eq!(e2["password"], "cba");
        assert_eq!(e2["pw_enc"], true);
        // sikertelen titkosítás: nyíltan
        let e3 = encode_entry(&cam("x"), None, |_| None);
        assert_eq!(e3["password"], "x");
        assert_eq!(e3["pw_enc"], false);
    }

    #[test]
    fn store_roundtrip() {
        let dir = std::env::temp_dir().join(format!("ipcam-test-{}", crate::model::new_id()));
        let mut st = Store::at(dir.clone());
        let list = vec![cam("jelszó"), cam("")];
        st.save_cameras(&list).unwrap();
        assert_eq!(st.load_cameras(), list);
        let raw = std::fs::read_to_string(dir.join("cameras.json")).unwrap();
        #[cfg(windows)]
        assert!(!raw.contains("jelszó"));
        let s = Settings { columns: 3, language: "en".into(), ..Default::default() };
        st.save_settings(&s).unwrap();
        assert_eq!(st.load_settings(), s);
        let _ = std::fs::remove_dir_all(dir);
    }
}
