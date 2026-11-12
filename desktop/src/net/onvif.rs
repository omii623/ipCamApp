//! Minimális ONVIF kliens: WS-Discovery keresés + stream URI lekérdezés.

use crate::model::{Camera, StreamType, uri_decode};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use regex::Regex;
use sha1::{Digest, Sha1};
use std::collections::HashSet;
use std::net::UdpSocket;
use std::sync::LazyLock;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, PartialEq)]
pub struct OnvifDevice {
    pub host: String,
    pub xaddr: String,
    pub name: String,
    pub hardware: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct OnvifStream {
    pub profile_name: String,
    pub uri: String,
}

#[derive(Debug)]
pub enum OnvifError {
    Auth,
    Other(String),
}

/// WS-Discovery Probe a helyi hálózatra. Blokkol [timeout]-ig, minden új eszközt azonnal
/// visszaad az [on_found]-ban.
pub fn discover(
    timeout: Duration,
    stop: impl Fn() -> bool,
    mut on_found: impl FnMut(OnvifDevice),
) -> std::io::Result<()> {
    let probe = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:{}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"#,
        crate::model::new_id()
    );
    let socket = UdpSocket::bind("0.0.0.0:0")?;
    socket.set_read_timeout(Some(Duration::from_millis(300)))?;
    let _ = socket.set_multicast_ttl_v4(2);
    // UDP elveszhet -> többször küldjük
    for _ in 0..3 {
        socket.send_to(probe.as_bytes(), "239.255.255.250:3702")?;
    }
    let deadline = Instant::now() + timeout;
    let mut seen = HashSet::new();
    let mut buf = vec![0u8; 64 * 1024];
    while Instant::now() < deadline && !stop() {
        let (n, from) = match socket.recv_from(&mut buf) {
            Ok(x) => x,
            Err(_) => continue, // időtúllépés vagy ICMP hiba
        };
        let xml = String::from_utf8_lossy(&buf[..n]);
        let Some(xaddrs) = tag(&xml, "XAddrs") else {
            continue;
        };
        let xaddrs: Vec<&str> = xaddrs.split_whitespace().filter(|x| x.starts_with("http")).collect();
        let host = from.ip().to_string();
        // több cím közül azt választjuk, amelyikben a válaszoló IP-je szerepel
        let Some(xaddr) = xaddrs.iter().find(|x| x.contains(&host)).or(xaddrs.first()) else {
            continue;
        };
        if !seen.insert(xaddr.to_string()) {
            continue;
        }
        let scopes = tag(&xml, "Scopes").unwrap_or_default();
        on_found(OnvifDevice {
            host: url::Url::parse(xaddr).ok().and_then(|u| u.host_str().map(str::to_string)).unwrap_or(host),
            xaddr: xaddr.to_string(),
            name: scope(&scopes, "name")
                .unwrap_or_else(|| crate::i18n::t("ONVIF kamera", "ONVIF camera").into()),
            hardware: scope(&scopes, "hardware").unwrap_or_default(),
        });
    }
    Ok(())
}

static MEDIA_XADDR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"Media>\s*<(?:\w+:)?XAddr>([^<]+)<").unwrap());
static PROFILES: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<(?:\w+:)?Profiles[^>]*token="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Profiles>"#).unwrap()
});
static NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<(?:\w+:)?Name>([^<]*)<").unwrap());

/// A kamera összes profiljának stream URI-ja (fő stream, alstream…).
pub fn get_streams(device: &OnvifDevice, user: &str, pass: &str) -> Result<Vec<OnvifStream>, OnvifError> {
    let caps = soap(
        &device.xaddr,
        user,
        pass,
        r#"<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>"#,
    )?;
    let media =
        MEDIA_XADDR.captures(&caps).map(|c| c[1].trim().to_string()).unwrap_or_else(|| device.xaddr.clone());
    let profiles_xml =
        soap(&media, user, pass, r#"<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"#)?;
    let mut out = Vec::new();
    for c in PROFILES.captures_iter(&profiles_xml) {
        let token = c[1].to_string();
        let name = NAME.captures(&c[2]).map(|n| n[1].to_string()).unwrap_or_else(|| token.clone());
        let body = format!(
            r#"<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
<StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup>
<ProfileToken>{}</ProfileToken></GetStreamUri>"#,
            xml_escape(&token)
        );
        let resp = soap(&media, user, pass, &body)?;
        if let Some(uri) = tag(&resp, "Uri") {
            out.push(OnvifStream { profile_name: name, uri: xml_unescape(uri.trim()) });
        }
    }
    Ok(out)
}

fn soap(url: &str, user: &str, pass: &str, body: &str) -> Result<String, OnvifError> {
    let header = if user.is_empty() { String::new() } else { security_header(user, pass) };
    let envelope = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
<s:Header>{header}</s:Header>
<s:Body>{body}</s:Body></s:Envelope>"#
    );
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(4))
        .timeout_read(Duration::from_secs(6))
        .build();
    let res =
        agent.post(url).set("Content-Type", "application/soap+xml; charset=utf-8").send_string(&envelope);
    let (code, text) = match res {
        Ok(r) => (r.status(), r.into_string().unwrap_or_default()),
        Err(ureq::Error::Status(c, r)) => (c, r.into_string().unwrap_or_default()),
        Err(e) => return Err(OnvifError::Other(e.to_string())),
    };
    if code == 401 || text.contains("NotAuthorized") || text.contains("FailedAuthentication") {
        return Err(OnvifError::Auth);
    }
    if code >= 400 {
        return Err(OnvifError::Other(format!("ONVIF HTTP {code}")));
    }
    Ok(text)
}

/// WS-Security UsernameToken (PasswordDigest = Base64(SHA1(nonce + created + jelszó))).
fn security_header(user: &str, pass: &str) -> String {
    let nonce: [u8; 16] = rand::random();
    let created = utc_now();
    let mut h = Sha1::new();
    h.update(nonce);
    h.update(created.as_bytes());
    h.update(pass.as_bytes());
    let digest = B64.encode(h.finalize());
    format!(
        concat!(
            r#"<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken>"#,
            "<Username>{}</Username>",
            r#"<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">{}</Password>"#,
            r#"<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">{}</Nonce>"#,
            r#"<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">{}</Created>"#,
            "</UsernameToken></Security>"
        ),
        xml_escape(user),
        digest,
        B64.encode(nonce),
        created
    )
}

/// Aktuális UTC idő "yyyy-MM-ddTHH:mm:ssZ" alakban.
fn utc_now() -> String {
    let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    format_utc(secs)
}

fn format_utc(secs: i64) -> String {
    let days = secs.div_euclid(86400);
    let rem = secs.rem_euclid(86400);
    // Howard Hinnant: civil_from_days
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn tag(xml: &str, name: &str) -> Option<String> {
    let re = Regex::new(&format!(r"(?s)<(?:\w+:)?{name}(?:\s[^>]*)?>(.*?)</(?:\w+:)?{name}>")).ok()?;
    re.captures(xml).map(|c| c[1].to_string())
}

fn scope(scopes: &str, key: &str) -> Option<String> {
    let needle = format!("onvif.org/{key}/");
    let s = scopes.split_whitespace().find(|s| s.contains(&needle))?;
    let v = &s[s.find(&format!("/{key}/"))? + key.len() + 2..];
    Some(uri_decode(v))
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

fn xml_unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// Stream URI -> előre kitöltött kamera (a szerkesztőhöz).
pub fn to_camera(name: &str, uri: &str, user: &str, pass: &str) -> Camera {
    let parsed = url::Url::parse(uri).ok();
    let scheme = parsed.as_ref().map(|u| u.scheme().to_ascii_lowercase()).unwrap_or_default();
    let path = parsed.as_ref().map(|u| u.path().to_string()).unwrap_or_else(|| "/".into());
    let lower = path.to_ascii_lowercase();
    let kind = if scheme == "rtsp" {
        StreamType::Rtsp
    } else if lower.ends_with(".m3u8") {
        StreamType::Hls
    } else if lower.ends_with(".jpg") || lower.ends_with(".jpeg") || lower.contains("snapshot") {
        StreamType::Snapshot
    } else {
        StreamType::Mjpeg
    };
    let port = parsed.as_ref().and_then(|u| u.port_or_known_default()).unwrap_or(kind.default_port());
    let query = parsed.as_ref().and_then(|u| u.query()).map(|q| format!("?{q}")).unwrap_or_default();
    let host = parsed.as_ref().and_then(|u| u.host_str()).unwrap_or("").to_string();
    let host = if scheme == "https" { format!("https://{host}") } else { host };
    let mut cam = Camera::new(name, &host, port, kind, &format!("{path}{query}"));
    cam.username = user.into();
    cam.password = pass.into();
    cam
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utc_format() {
        assert_eq!(format_utc(0), "1970-01-01T00:00:00Z");
        assert_eq!(format_utc(1_709_210_096), "2024-02-29T12:34:56Z");
    }

    #[test]
    fn parse_helpers() {
        let xml = r#"<d:ProbeMatch><d:Scopes>onvif://www.onvif.org/name/Teszt%20Kamera onvif://www.onvif.org/hardware/FC-1</d:Scopes><d:XAddrs>http://10.0.0.5/onvif/device_service</d:XAddrs></d:ProbeMatch>"#;
        let scopes = tag(xml, "Scopes").unwrap();
        assert_eq!(scope(&scopes, "name").as_deref(), Some("Teszt Kamera"));
        assert_eq!(scope(&scopes, "hardware").as_deref(), Some("FC-1"));
        assert_eq!(tag(xml, "XAddrs").as_deref(), Some("http://10.0.0.5/onvif/device_service"));
    }

    #[test]
    fn uri_to_camera() {
        let c = to_camera("A", "rtsp://192.168.1.2:8554/cam/realmonitor?channel=1&subtype=0", "u", "p");
        assert_eq!((c.kind, c.port, c.host.as_str()), (StreamType::Rtsp, 8554, "192.168.1.2"));
        assert_eq!(c.path, "/cam/realmonitor?channel=1&subtype=0");
        let s = to_camera("B", "http://10.0.0.3/snapshot.jpg", "", "");
        assert_eq!((s.kind, s.port), (StreamType::Snapshot, 80));
        let r = to_camera("C", "rtsp://10.0.0.3/live", "", "");
        assert_eq!(r.port, 554);
    }
}
