//! HTTP GET Basic-, majd szükség esetén Digest-hitelesítéssel.

use crate::net::digest::DigestAuth;
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use std::time::Duration;

pub fn agent(read_timeout: Duration) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(5))
        .timeout_read(read_timeout)
        .redirects(4)
        .build()
}

pub enum HttpError {
    /// Nem sikerült csatlakozni (hálózat, port, DNS, időtúllépés).
    Transport(String),
    Status(u16),
}

impl std::fmt::Display for HttpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HttpError::Transport(s) => f.write_str(s),
            HttpError::Status(c) => write!(f, "HTTP {c}"),
        }
    }
}

/// A kérés URI-ja a Digesthez (útvonal + lekérdezés).
fn request_uri(url: &str) -> String {
    match url::Url::parse(url) {
        Ok(u) => match u.query() {
            Some(q) => format!("{}?{q}", u.path()),
            None => u.path().to_string(),
        },
        Err(_) => "/".into(),
    }
}

/// GET; ha a kamera Digest-et kér, a kihívást a [digest]-ben megjegyezzük, így a következő
/// kéréseknél rögtön azzal próbálkozunk.
#[allow(clippy::result_large_err)] // a ureq::Error mérete, nem a miénk
pub fn get(
    agent: &ureq::Agent,
    url: &str,
    user: &str,
    pass: &str,
    digest: &mut Option<DigestAuth>,
) -> Result<ureq::Response, HttpError> {
    let uri = request_uri(url);
    let auth = match (user.is_empty(), digest.as_mut()) {
        (true, _) => None,
        (false, Some(d)) => Some(d.header("GET", &uri, user, pass)),
        (false, None) => Some(format!("Basic {}", B64.encode(format!("{user}:{pass}")))),
    };
    let send = |auth: Option<String>| {
        let mut req = agent.get(url);
        if let Some(a) = auth {
            req = req.set("Authorization", &a);
        }
        req.call()
    };
    match send(auth) {
        Ok(r) => Ok(r),
        Err(ureq::Error::Status(401, resp)) if !user.is_empty() => {
            let challenge = resp.header("WWW-Authenticate").and_then(DigestAuth::parse);
            let Some(mut d) = challenge else {
                return Err(HttpError::Status(401));
            };
            let h = d.header("GET", &uri, user, pass);
            *digest = Some(d);
            match send(Some(h)) {
                Ok(r) => Ok(r),
                Err(ureq::Error::Status(c, _)) => Err(HttpError::Status(c)),
                Err(e) => Err(HttpError::Transport(e.to_string())),
            }
        }
        Err(ureq::Error::Status(c, _)) => Err(HttpError::Status(c)),
        Err(e) => Err(HttpError::Transport(e.to_string())),
    }
}
