//! Mentés/visszaállítás és QR-megosztás – az Android app formátumával kompatibilisen.
//! A jelszavak a fájlban / QR-ben NYÍLTAN szerepelnek (a DPAPI-kulcs gép- és fiókfüggő).

use super::Settings;
use crate::model::{Camera, Preset};
use serde_json::{Value, json};

const FORMAT: &str = "ipcam-backup";
const VERSION: i64 = 1;
const QR_FORMAT: &str = "ipcam-camera";

pub fn build(cameras: &[Camera], presets: &[Preset], settings: &Settings) -> Value {
    json!({
        "format": FORMAT,
        "version": VERSION,
        "cameras": cameras.iter().map(Camera::to_json).collect::<Vec<_>>(),
        "presets": presets.iter().map(Preset::to_json).collect::<Vec<_>>(),
        "settings": {
            "night_mode": settings.night_mode,
            "accent": settings.accent,
            "search_ports": settings.search_ports,
            "language": settings.language,
        },
    })
}

/// Csak a formátumot nézzük, a verziót nem (a mezők additívak).
pub fn is_valid(root: &Value) -> bool {
    root.get("format").and_then(Value::as_str) == Some(FORMAT)
}

/// Egy hibás bejegyzés ne döntse el az egész importot.
pub fn parse_cameras(root: &Value) -> Vec<Camera> {
    root.get("cameras")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Camera::from_json).collect())
        .unwrap_or_default()
}

pub fn parse_presets(root: &Value) -> Vec<Preset> {
    root.get("presets")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Preset::from_json).collect())
        .unwrap_or_default()
}

/// A mentés beállításai; None = nincs a mentésben, azt nem módosítjuk.
#[derive(Debug, Default, PartialEq)]
pub struct ImportedSettings {
    pub night_mode: Option<i32>,
    pub accent: Option<i32>,
    pub search_ports: Option<String>,
    pub language: Option<String>,
}

/// Beolvasás MIELŐTT bármit írnánk: hibás típusú érték esetén hiba, és az import semmit nem módosít.
pub fn parse_settings(root: &Value) -> Result<ImportedSettings, String> {
    let Some(s) = root.get("settings").and_then(Value::as_object) else {
        return Ok(ImportedSettings::default());
    };
    let int = |k: &str| -> Result<Option<i32>, String> {
        match s.get(k) {
            None => Ok(None),
            Some(v) => v.as_i64().map(|x| Some(x as i32)).ok_or_else(|| format!("{k}: {v}")),
        }
    };
    let string = |k: &str| -> Result<Option<String>, String> {
        match s.get(k) {
            None => Ok(None),
            Some(v) => v.as_str().map(|x| Some(x.to_string())).ok_or_else(|| format!("{k}: {v}")),
        }
    };
    Ok(ImportedSettings {
        night_mode: int("night_mode")?,
        accent: int("accent")?,
        search_ports: string("search_ports")?,
        language: string("language")?,
    })
}

impl ImportedSettings {
    pub fn apply(&self, s: &mut Settings) {
        if let Some(v) = self.night_mode {
            s.night_mode = v;
        }
        if let Some(v) = self.accent {
            s.accent = v;
        }
        if let Some(v) = &self.search_ports {
            s.search_ports = v.clone();
        }
        if let Some(v) = &self.language {
            s.language = v.clone();
        }
    }
}

/// Egy kamera QR-tartalma.
pub fn qr_build(camera: &Camera) -> String {
    json!({ "format": QR_FORMAT, "version": 1, "camera": camera.to_json() }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::StreamType;

    #[test]
    fn roundtrip() {
        let mut c = Camera::new("Udvar", "192.168.0.9", 554, StreamType::Rtsp, "/s");
        c.password = "pw".into();
        let p = Preset::user("Saját", StreamType::Mjpeg, "/v", "admin");
        let s = Settings { night_mode: 1, accent: -16776961, language: "en".into(), ..Default::default() };
        let root = build(&[c.clone()], std::slice::from_ref(&p), &s);
        let root: Value = serde_json::from_str(&root.to_string()).unwrap();
        assert!(is_valid(&root));
        assert_eq!(parse_cameras(&root), vec![c.clone()]);
        assert_eq!(parse_presets(&root), vec![p]);
        let imp = parse_settings(&root).unwrap();
        let mut s2 = Settings::default();
        imp.apply(&mut s2);
        assert_eq!((s2.night_mode, s2.accent, s2.language.as_str()), (1, -16776961, "en"));

        // QR: az Android app ezt a formátumot olvassa be
        let q: Value = serde_json::from_str(&qr_build(&c)).unwrap();
        assert_eq!(q["format"], "ipcam-camera");
        assert_eq!(Camera::from_json(&q["camera"]), Some(c));
    }

    #[test]
    fn tolerant_and_strict() {
        // jövőbeli verzió, ismeretlen mezők, egy hibás kamera
        let root = serde_json::json!({
            "format": "ipcam-backup", "version": 99, "extra": 1,
            "cameras": [{"id":"1","name":"a","host":"h","port":554}, {"name":"rossz"}],
        });
        assert!(is_valid(&root));
        assert_eq!(parse_cameras(&root).len(), 1);
        assert_eq!(parse_settings(&root).unwrap(), ImportedSettings::default());
        let bad = serde_json::json!({"format":"ipcam-backup","settings":{"accent":"piros"}});
        assert!(parse_settings(&bad).is_err());
        assert!(!is_valid(&serde_json::json!({"format":"más"})));
    }
}
