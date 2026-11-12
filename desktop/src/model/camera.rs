//! Kamera-modell. A JSON alak megegyezik az Android app (ipCamApp) formátumával,
//! így a mentésfájlok és QR-kódok a két alkalmazás között átvihetők.

use serde_json::{Value, json};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum StreamType {
    Rtsp,
    Mjpeg,
    Snapshot,
    Hls,
}

impl StreamType {
    pub const ALL: [StreamType; 4] = [Self::Rtsp, Self::Mjpeg, Self::Snapshot, Self::Hls];

    /// A JSON-ben tárolt név (az Android enum neve).
    pub fn name(self) -> &'static str {
        match self {
            Self::Rtsp => "RTSP",
            Self::Mjpeg => "MJPEG",
            Self::Snapshot => "SNAPSHOT",
            Self::Hls => "HLS",
        }
    }

    pub fn from_name(s: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|t| t.name() == s)
    }

    /// Megjelenített (technikai, nem fordított) felirat.
    pub fn label(self) -> &'static str {
        match self {
            Self::Rtsp => "RTSP",
            Self::Mjpeg => "HTTP MJPEG",
            Self::Snapshot => "HTTP JPEG",
            Self::Hls => "HLS (m3u8)",
        }
    }

    pub fn default_port(self) -> u16 {
        match self {
            Self::Rtsp => 554,
            _ => 80,
        }
    }

    pub fn default_path(self) -> &'static str {
        match self {
            Self::Rtsp => "/stream1",
            Self::Mjpeg => "/video.mjpg",
            Self::Snapshot => "/snapshot.jpg",
            Self::Hls => "/index.m3u8",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Camera {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub kind: StreamType,
    pub path: String,
    pub username: String,
    pub password: String,
    /// Csoport neve; üres = nincs csoportban.
    pub group: String,
}

pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

impl Camera {
    pub fn new(name: &str, host: &str, port: u16, kind: StreamType, path: &str) -> Self {
        Camera {
            id: new_id(),
            name: name.into(),
            host: host.into(),
            port,
            kind,
            path: path.into(),
            username: String::new(),
            password: String::new(),
            group: String::new(),
        }
    }

    /// Az URL hitelesítő adatok nélkül (megjelenítéshez).
    pub fn display_url(&self) -> String {
        self.build_url(false)
    }

    /// A host a séma (http://, https://), záró perjel és IPv6-zárójelek nélkül.
    pub fn bare_host(&self) -> &str {
        let h = match self.host.find("://") {
            Some(i) => &self.host[i + 3..],
            None => &self.host,
        };
        let h = h.trim().trim_end_matches('/');
        h.strip_prefix('[').and_then(|x| x.strip_suffix(']')).unwrap_or(h)
    }

    /// A host URL-be illeszthető alakja (IPv6 cím szögletes zárójelben).
    fn url_host(&self) -> String {
        let h = self.bare_host();
        if h.contains(':') { format!("[{h}]") } else { h.to_string() }
    }

    pub fn build_url(&self, include_credentials: bool) -> String {
        // HTTPS: ha "https://"-sel írták be, vagy a port 443
        let secure = self.host.to_ascii_lowercase().starts_with("https://") || self.port == 443;
        let scheme = match self.kind {
            StreamType::Rtsp => "rtsp",
            _ if secure => "https",
            _ => "http",
        };
        let path = if self.path.starts_with('/') { self.path.clone() } else { format!("/{}", self.path) };
        let auth = if include_credentials && !self.username.is_empty() {
            format!("{}:{}@", uri_encode(&self.username), uri_encode(&self.password))
        } else {
            String::new()
        };
        format!("{scheme}://{auth}{}:{}{path}", self.url_host(), self.port)
    }

    pub fn to_json(&self) -> Value {
        json!({
            "id": self.id, "name": self.name, "host": self.host, "port": self.port,
            "type": self.kind.name(), "path": self.path,
            "username": self.username, "password": self.password,
            "group": self.group,
        })
    }

    /// JSON -> kamera. A kötelező mezők: id, name, host, port; a többi alapértékkel pótolva.
    pub fn from_json(o: &Value) -> Option<Camera> {
        let s = |k: &str| o.get(k).and_then(Value::as_str).map(str::to_string);
        let port = match o.get("port")? {
            Value::Number(n) => n.as_u64()?,
            Value::String(t) => t.trim().parse().ok()?,
            _ => return None,
        };
        Some(Camera {
            id: s("id")?,
            name: s("name")?,
            host: s("host")?,
            port: u16::try_from(port).ok()?,
            kind: s("type").and_then(|t| StreamType::from_name(&t)).unwrap_or(StreamType::Rtsp),
            path: s("path").unwrap_or_default(),
            username: s("username").unwrap_or_default(),
            password: s("password").unwrap_or_default(),
            group: s("group").unwrap_or_default(),
        })
    }
}

/// Százalékos kódolás az Android `Uri.encode`-hoz hasonlóan (a nem foglalt karakterek maradnak).
pub fn uri_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"_-!.~'()*".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Százalékos dekódolás ('+' = szóköz, mint a Java URLDecoder).
pub fn uri_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                let hex = |b: u8| (b as char).to_digit(16);
                match (bytes.get(i + 1).and_then(|&b| hex(b)), bytes.get(i + 2).and_then(|&b| hex(b))) {
                    (Some(h), Some(l)) => {
                        out.push((h * 16 + l) as u8);
                        i += 3;
                        continue;
                    }
                    _ => out.push(b'%'),
                }
            }
            b'+' => out.push(b' '),
            b => out.push(b),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Összefésülés id szerint: azonos id frissül, új a végére kerül.
pub fn merge_by_id(existing: &[Camera], incoming: &[Camera]) -> Vec<Camera> {
    let mut list = existing.to_vec();
    for cam in incoming {
        match list.iter().position(|c| c.id == cam.id) {
            Some(i) => list[i] = cam.clone(),
            None => list.push(cam.clone()),
        }
    }
    list
}

/// A létező csoportnevek az első előfordulás sorrendjében.
pub fn groups(cameras: &[Camera]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for c in cameras {
        if !c.group.is_empty() && !out.contains(&c.group) {
            out.push(c.group.clone());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_building() {
        let mut c = Camera::new("a", "192.168.1.5", 554, StreamType::Rtsp, "stream1");
        assert_eq!(c.display_url(), "rtsp://192.168.1.5:554/stream1");
        c.username = "ad min".into();
        c.password = "p@ss".into();
        assert_eq!(c.build_url(true), "rtsp://ad%20min:p%40ss@192.168.1.5:554/stream1");
        let h = Camera::new("b", "https://cam.example.com/", 8443, StreamType::Mjpeg, "/v");
        assert_eq!(h.display_url(), "https://cam.example.com:8443/v");
        let p = Camera::new("c", "10.0.0.2", 443, StreamType::Snapshot, "/s.jpg");
        assert_eq!(p.display_url(), "https://10.0.0.2:443/s.jpg");
        for host in ["::1", "[::1]"] {
            let v6 = Camera::new("d", host, 8554, StreamType::Rtsp, "/s");
            assert_eq!(v6.display_url(), "rtsp://[::1]:8554/s");
            assert_eq!(v6.bare_host(), "::1");
        }
    }

    #[test]
    fn json_roundtrip_and_tolerance() {
        let mut c = Camera::new("Bejárat", "10.0.0.1", 8080, StreamType::Mjpeg, "/video.mjpg");
        c.group = "Ház".into();
        assert_eq!(Camera::from_json(&c.to_json()), Some(c));
        let v = serde_json::json!({"id":"x","name":"n","host":"h","port":80,"type":"FOO"});
        let parsed = Camera::from_json(&v).unwrap();
        assert_eq!(parsed.kind, StreamType::Rtsp);
        assert_eq!(parsed.group, "");
        assert!(Camera::from_json(&serde_json::json!({"id":"x","name":"n","host":"h"})).is_none());
        assert!(
            Camera::from_json(&serde_json::json!({"id":"x","name":"n","host":"h","port":70000})).is_none()
        );
    }

    #[test]
    fn merge_by_id_updates_and_appends() {
        let a = Camera::new("a", "1", 1, StreamType::Rtsp, "/");
        let b = Camera::new("b", "2", 1, StreamType::Rtsp, "/");
        let mut a2 = a.clone();
        a2.name = "a2".into();
        let m = merge_by_id(&[a.clone(), b.clone()], &[a2.clone()]);
        assert_eq!(m, vec![a2, b]);
    }

    #[test]
    fn decode() {
        assert_eq!(uri_decode("Teszt%20Kamera"), "Teszt Kamera");
        assert_eq!(uri_decode("a+b%"), "a b%");
        assert_eq!(uri_decode("%C3%A1"), "á");
    }
}
