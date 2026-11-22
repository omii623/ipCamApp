//! HTTP/RTSP Digest hitelesítés (RFC 7616, MD5 / SHA-256, qop=auth vagy qop nélkül).

use md5::Md5;
use regex::Regex;
use sha2::{Digest, Sha256};
use std::sync::LazyLock;

#[derive(Clone, Debug)]
pub struct DigestAuth {
    realm: String,
    nonce: String,
    opaque: Option<String>,
    qop_auth: bool,
    /// A kihívásban kapott algoritmus; None, ha nem adták meg (ilyenkor MD5).
    algorithm: Option<String>,
    nc: u32,
}

static PARAM: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"(\w+)=(?:"([^"]*)"|([^,\s]+))"#).unwrap());

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn hash(sha256: bool, s: &str) -> String {
    if sha256 { hex(&Sha256::digest(s.as_bytes())) } else { hex(&Md5::digest(s.as_bytes())) }
}

impl DigestAuth {
    /// A WWW-Authenticate fejléc feldolgozása; None, ha nem Digest kihívás.
    pub fn parse(header: &str) -> Option<DigestAuth> {
        let h = header.trim_start();
        if h.len() < 6 || !h[..6].eq_ignore_ascii_case("digest") {
            return None;
        }
        let mut realm = None;
        let mut nonce = None;
        let mut opaque = None;
        let mut qop = String::new();
        let mut algorithm = None;
        for c in PARAM.captures_iter(h) {
            let v = c.get(2).or(c.get(3)).map(|m| m.as_str().to_string()).unwrap_or_default();
            match c[1].to_ascii_lowercase().as_str() {
                "realm" => realm = Some(v),
                "nonce" => nonce = Some(v),
                "opaque" => opaque = Some(v),
                "qop" => qop = v,
                "algorithm" => algorithm = Some(v),
                _ => {}
            }
        }
        Some(DigestAuth {
            realm: realm?,
            nonce: nonce?,
            opaque,
            qop_auth: qop.split(',').any(|q| q.trim() == "auth"),
            algorithm,
            nc: 0,
        })
    }

    pub fn header(&mut self, method: &str, uri: &str, user: &str, pass: &str) -> String {
        self.header_with_cnonce(method, uri, user, pass, &hex(&rand::random::<[u8; 8]>()))
    }

    fn header_with_cnonce(
        &mut self,
        method: &str,
        uri: &str,
        user: &str,
        pass: &str,
        cnonce: &str,
    ) -> String {
        let sha = self.algorithm.as_deref().is_some_and(|a| a.to_ascii_uppercase().starts_with("SHA-256"));
        let ha1 = hash(sha, &format!("{user}:{}:{pass}", self.realm));
        let ha2 = hash(sha, &format!("{method}:{uri}"));
        // Előbb az idézőjeles mezők (response közvetlenül az uri után), a végén az idézőjel
        // nélküliek (qop, nc, algorithm); az algorithm csak ha a kihívás is megadta. A régi
        // LIVE555 RTSP-szerverek (sok olcsó kamera) az "algorithm=MD5"-öt a response előtt
        // elrontva dolgozzák fel, és jó jelszóra is 401-et adnak.
        let mut s = format!(
            "Digest username=\"{user}\", realm=\"{}\", nonce=\"{}\", uri=\"{uri}\"",
            self.realm, self.nonce
        );
        let mut tail = String::new();
        if self.qop_auth {
            self.nc += 1;
            let nc = format!("{:08x}", self.nc);
            let r = hash(sha, &format!("{ha1}:{}:{nc}:{cnonce}:auth:{ha2}", self.nonce));
            s.push_str(&format!(", response=\"{r}\", cnonce=\"{cnonce}\""));
            tail.push_str(&format!(", qop=auth, nc={nc}"));
        } else {
            let r = hash(sha, &format!("{ha1}:{}:{ha2}", self.nonce));
            s.push_str(&format!(", response=\"{r}\""));
        }
        if let Some(o) = &self.opaque {
            s.push_str(&format!(", opaque=\"{o}\""));
        }
        s.push_str(&tail);
        if let Some(a) = &self.algorithm {
            s.push_str(&format!(", algorithm={a}"));
        }
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 2617 3.5 példája.
    #[test]
    fn rfc2617_example() {
        let mut d = DigestAuth::parse(
            r#"Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41""#,
        )
        .unwrap();
        let h = d.header_with_cnonce("GET", "/dir/index.html", "Mufasa", "Circle Of Life", "0a4f113b");
        assert!(h.contains("response=\"6629fae49393a05397450978507c4ef1\""), "{h}");
        assert!(h.contains("nc=00000001"));
        assert!(h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
    }

    /// Valódi LIVE555 (régi kamera) kihívás: nincs algorithm és qop – a fejlécben se legyen
    /// algorithm, és a response legyen az utolsó (különben jó jelszóra is 401).
    #[test]
    fn live555_compatible_order() {
        let mut d = DigestAuth::parse(
            r#"Digest realm="LIVE555 Streaming Media", nonce="7a6401ac69d1ee3914ad0f40b72ec884""#,
        )
        .unwrap();
        let url = "rtsp://192.168.1.183:554/mpeg4";
        let h = d.header("DESCRIBE", url, "admin", "123456");
        let ha1 = hash(false, "admin:LIVE555 Streaming Media:123456");
        let expected = hash(false, &format!("{ha1}:7a6401ac69d1ee3914ad0f40b72ec884:{}", hash(false, &format!("DESCRIBE:{url}"))));
        assert!(!h.contains("algorithm"), "{h}");
        assert!(h.ends_with(&format!("response=\"{expected}\"")), "{h}");
    }

    #[test]
    fn algorithm_kept_at_end_when_challenged() {
        let mut d = DigestAuth::parse(r#"Digest realm="r", nonce="n", algorithm=SHA-256, qop="auth""#).unwrap();
        let h = d.header("GET", "/", "u", "p");
        assert!(h.ends_with(", algorithm=SHA-256"), "{h}");
        assert!(h.find("response=").unwrap() < h.find("qop=auth").unwrap(), "{h}");
    }

    #[test]
    fn not_digest() {
        assert!(DigestAuth::parse("Basic realm=\"x\"").is_none());
        assert!(DigestAuth::parse("Digest realm=\"x\"").is_none()); // nonce nélkül
    }
}
