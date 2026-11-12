//! Egy kamera elérhetőségének tesztelése a megadott (egyetlen) belépéssel.
//! NEM próbálgat több jelszót – csak azt ellenőrzi, amit a felhasználó beírt.

use crate::model::{Camera, StreamType};
use crate::net::digest::DigestAuth;
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Clone, Debug, PartialEq)]
pub enum TestResult {
    /// Elérhető, a belépés jó (vagy nem kell).
    Ok,
    /// Elérhető, de a felhasználónév/jelszó hibás.
    AuthFailed,
    /// Nem lehet csatlakozni (hálózat/port).
    Unreachable,
    Other(String),
}

pub fn test(cam: &Camera) -> TestResult {
    match cam.kind {
        StreamType::Rtsp => test_rtsp(cam),
        _ => test_http(cam),
    }
}

fn test_http(cam: &Camera) -> TestResult {
    let agent = crate::net::http::agent(Duration::from_secs(5));
    let mut digest = None;
    match crate::net::http::get(&agent, &cam.display_url(), &cam.username, &cam.password, &mut digest) {
        Ok(_) => TestResult::Ok, // a választ eldobjuk (MJPEG-nél végtelen lenne)
        Err(crate::net::http::HttpError::Status(401 | 403)) => TestResult::AuthFailed,
        Err(crate::net::http::HttpError::Status(c)) => TestResult::Other(format!("HTTP {c}")),
        Err(crate::net::http::HttpError::Transport(_)) => TestResult::Unreachable,
    }
}

/// RTSP: DESCRIBE kérés kézzel; 200 -> ok, 401 -> hibás belépés (Basic és Digest is).
fn test_rtsp(cam: &Camera) -> TestResult {
    let addr = match (cam.bare_host(), cam.port).to_socket_addrs().ok().and_then(|mut a| a.next()) {
        Some(a) => a,
        None => return TestResult::Unreachable,
    };
    let Ok(mut sock) = TcpStream::connect_timeout(&addr, Duration::from_secs(5)) else {
        return TestResult::Unreachable;
    };
    let _ = sock.set_read_timeout(Some(Duration::from_secs(5)));
    let url = cam.display_url();

    let mut describe = |cseq: u32, auth: Option<String>| -> String {
        let mut req = format!("DESCRIBE {url} RTSP/1.0\r\nCSeq: {cseq}\r\nUser-Agent: ipCamDesktop\r\n");
        if let Some(a) = auth {
            req.push_str(&format!("Authorization: {a}\r\n"));
        }
        req.push_str("Accept: application/sdp\r\n\r\n");
        if sock.write_all(req.as_bytes()).is_err() {
            return String::new();
        }
        let mut buf = vec![0u8; 8192];
        match sock.read(&mut buf) {
            Ok(n) => String::from_utf8_lossy(&buf[..n]).into_owned(),
            Err(_) => String::new(),
        }
    };

    let mut resp = describe(1, None);
    if status_code(&resp) == Some(401) && !cam.username.is_empty() {
        let challenge = resp
            .lines()
            .filter_map(|l| l.split_once(':'))
            .filter(|(k, _)| k.trim().eq_ignore_ascii_case("WWW-Authenticate"))
            .find_map(|(_, v)| DigestAuth::parse(v.trim()));
        let auth = match challenge {
            Some(mut d) => d.header("DESCRIBE", &url, &cam.username, &cam.password),
            None => format!("Basic {}", B64.encode(format!("{}:{}", cam.username, cam.password))),
        };
        resp = describe(2, Some(auth));
    }
    match status_code(&resp) {
        Some(401) => TestResult::AuthFailed,
        Some(_) => TestResult::Ok, // RTSP-vel válaszol: a szerver él (pl. 404 = rossz útvonal, de elérhető)
        None if resp.is_empty() => TestResult::Unreachable,
        None => TestResult::Other(resp.lines().next().unwrap_or("?").trim().to_string()),
    }
}

fn status_code(resp: &str) -> Option<u16> {
    let line = resp.lines().next()?;
    let mut it = line.split_whitespace();
    if !it.next()?.starts_with("RTSP/") {
        return None;
    }
    it.next()?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_parsing() {
        assert_eq!(status_code("RTSP/1.0 200 OK\r\nCSeq: 1\r\n"), Some(200));
        assert_eq!(status_code("RTSP/1.0 401 Unauthorized\r\n"), Some(401));
        assert_eq!(status_code("HTTP/1.1 400 Bad\r\n"), None);
        assert_eq!(status_code(""), None);
    }

    #[test]
    fn unreachable_port() {
        // egy szabad, de nem figyelő port
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let cam = Camera::new("x", "127.0.0.1", port, StreamType::Rtsp, "/s");
        assert_eq!(test(&cam), TestResult::Unreachable);
    }
}
