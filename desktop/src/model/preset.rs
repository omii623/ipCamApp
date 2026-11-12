//! Gyártói sablonok (beépített + a felhasználó sajátjai).

use super::StreamType;
use serde_json::{Value, json};

/// Gyártói sablon: stream-típus, megszokott útvonal, gyári felhasználónév (jelszó nincs).
#[derive(Clone, Debug, PartialEq)]
pub struct Preset {
    /// Felirat; a generikus „Egyéni” sablonnál üres (fordított felirat jön helyette).
    pub label: String,
    pub kind: StreamType,
    pub path: String,
    pub default_user: String,
    pub builtin: bool,
}

impl Preset {
    fn builtin(label: &str, kind: StreamType, path: &str, user: &str) -> Self {
        Preset { label: label.into(), kind, path: path.into(), default_user: user.into(), builtin: true }
    }

    pub fn user(label: &str, kind: StreamType, path: &str, user: &str) -> Self {
        Preset { label: label.into(), kind, path: path.into(), default_user: user.into(), builtin: false }
    }

    pub fn display(&self) -> String {
        if self.label.is_empty() {
            crate::i18n::t("Egyéni / általános", "Custom / generic").to_string()
        } else {
            self.label.clone()
        }
    }

    pub fn to_json(&self) -> Value {
        json!({ "name": self.label, "type": self.kind.name(), "path": self.path, "user": self.default_user })
    }

    pub fn from_json(o: &Value) -> Option<Preset> {
        let s = |k: &str| o.get(k).and_then(Value::as_str).map(str::to_string);
        Some(Preset::user(
            &s("name")?,
            s("type").and_then(|t| StreamType::from_name(&t)).unwrap_or(StreamType::Rtsp),
            &s("path").unwrap_or_default(),
            &s("user").unwrap_or_default(),
        ))
    }
}

/// Beépített gyártói sablonok; az első a generikus „Egyéni”.
pub fn builtin_presets() -> Vec<Preset> {
    use StreamType::*;
    vec![
        Preset::builtin("", Rtsp, "/stream1", ""),
        Preset::builtin("Hikvision", Rtsp, "/Streaming/Channels/101", "admin"),
        Preset::builtin("Hikvision (sub)", Rtsp, "/Streaming/Channels/102", "admin"),
        Preset::builtin("Dahua", Rtsp, "/cam/realmonitor?channel=1&subtype=0", "admin"),
        Preset::builtin("Dahua (sub)", Rtsp, "/cam/realmonitor?channel=1&subtype=1", "admin"),
        Preset::builtin("Reolink", Rtsp, "/h264Preview_01_main", "admin"),
        Preset::builtin("Reolink (sub)", Rtsp, "/h264Preview_01_sub", "admin"),
        Preset::builtin("TP-Link Tapo", Rtsp, "/stream1", "admin"),
        Preset::builtin("TP-Link Tapo (sub)", Rtsp, "/stream2", "admin"),
        Preset::builtin("Axis", Rtsp, "/axis-media/media.amp", "root"),
        Preset::builtin("Foscam", Rtsp, "/videoMain", "admin"),
        Preset::builtin("Amcrest", Rtsp, "/cam/realmonitor?channel=1&subtype=0", "admin"),
        Preset::builtin("Ubiquiti UniFi", Rtsp, "/s0", ""),
        Preset::builtin("ONVIF", Rtsp, "/onvif1", "admin"),
        Preset::builtin("ESP32-CAM / MJPEG", Mjpeg, "/stream", ""),
        Preset::builtin("Android IP Webcam", Mjpeg, "/video", ""),
    ]
}

/// Sablonok összefésülése név szerint: az azonos nevű régi kiesik, az új a végére kerül.
pub fn merge_presets_by_name(existing: &[Preset], incoming: &[Preset]) -> Vec<Preset> {
    let mut list = existing.to_vec();
    for p in incoming {
        list.retain(|x| x.label != p.label);
        list.push(p.clone());
    }
    list
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_replaces_same_name() {
        let p1 = Preset::user("X", StreamType::Rtsp, "/1", "");
        let p2 = Preset::user("X", StreamType::Mjpeg, "/2", "");
        assert_eq!(merge_presets_by_name(&[p1], std::slice::from_ref(&p2)), vec![p2]);
    }

    #[test]
    fn builtin_first_is_generic() {
        let b = builtin_presets();
        assert!(b[0].label.is_empty() && b.iter().all(|p| p.builtin));
    }
}
