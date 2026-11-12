//! IP-tartomány + portok átvizsgálása nyitott portok után.

use crate::i18n::{Lang, lang};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

pub const RTSP_PORTS: [u16; 2] = [554, 8554];
pub const MAX_ADDRESSES: usize = 4096;
const MAX_PORTS: usize = 10;
const CONNECT_TIMEOUT: Duration = Duration::from_millis(800);
const THREADS: usize = 64;

#[derive(Debug, PartialEq)]
pub enum RangeError {
    BadMask(String),
    BadRangeEnd(String),
    Reversed(String),
    TooBig,
    Empty,
    BadPort(String),
    PortsEmpty,
    PortsTooMany,
    BadIp(String),
}

impl std::fmt::Display for RangeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        use RangeError::*;
        let hu = lang() == Lang::Hu;
        match self {
            BadMask(s) if hu => write!(f, "Hibás maszk: /{s} (16–32 lehet)"),
            BadMask(s) => write!(f, "Invalid mask: /{s} (must be 16–32)"),
            BadRangeEnd(s) if hu => write!(f, "Hibás tartomány vége: {s}"),
            BadRangeEnd(s) => write!(f, "Invalid range end: {s}"),
            Reversed(s) if hu => write!(f, "A tartomány vége kisebb, mint az eleje: {s}"),
            Reversed(s) => write!(f, "Range end is lower than its start: {s}"),
            TooBig if hu => write!(f, "Túl nagy tartomány (max. {MAX_ADDRESSES} cím)"),
            TooBig => write!(f, "Range too large (max. {MAX_ADDRESSES} addresses)"),
            Empty if hu => write!(f, "Adj meg egy IP tartományt"),
            Empty => write!(f, "Enter an IP range"),
            BadPort(s) if hu => write!(f, "Hibás port: {s}"),
            BadPort(s) => write!(f, "Invalid port: {s}"),
            PortsEmpty if hu => write!(f, "Adj meg legalább egy portot"),
            PortsEmpty => write!(f, "Enter at least one port"),
            PortsTooMany if hu => write!(f, "Legfeljebb {MAX_PORTS} port adható meg"),
            PortsTooMany => write!(f, "At most {MAX_PORTS} ports are allowed"),
            BadIp(s) if hu => write!(f, "Hibás IP cím: {s}"),
            BadIp(s) => write!(f, "Invalid IP address: {s}"),
        }
    }
}

/// A gép saját IPv4 címe a helyi hálózaton (az alapértelmezett útvonal felé néző interfész).
/// UDP "connect" nem küld csomagot, csak kiválasztja az interfészt.
pub fn local_ipv4() -> Option<Ipv4Addr> {
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?; // TEST-NET-1 cím, csak útvonal-választáshoz
    match s.local_addr().ok()? {
        SocketAddr::V4(a) if !a.ip().is_loopback() && !a.ip().is_unspecified() => Some(*a.ip()),
        _ => None,
    }
}

/// Alapértelmezett tartomány a saját IP alapján, pl. "192.168.0.1-254".
pub fn default_range(own: Ipv4Addr) -> String {
    let o = own.octets();
    format!("{}.{}.{}.1-254", o[0], o[1], o[2])
}

fn ip_to_u32(ip: &str) -> Result<u32, RangeError> {
    let parts: Vec<&str> = ip.trim().split('.').collect();
    if parts.len() != 4 {
        return Err(RangeError::BadIp(ip.into()));
    }
    parts.iter().try_fold(0u32, |acc, p| {
        let n: u8 = p.parse().map_err(|_| RangeError::BadIp(ip.into()))?;
        Ok((acc << 8) | n as u32)
    })
}

fn split_list(text: &str) -> impl Iterator<Item = &str> {
    text.split([',', ';', ' ']).map(str::trim).filter(|s| !s.is_empty())
}

/// Tartomány értelmezése. Elfogadott formák (vesszővel több is):
/// `192.168.0.50`, `192.168.0.10-50`, `192.168.0.10-192.168.1.20`, `192.168.0.0/24`.
pub fn parse_range(text: &str) -> Result<Vec<Ipv4Addr>, RangeError> {
    let mut result: Vec<u32> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for part in split_list(text) {
        let (from, to) = if let Some((ip, bits)) = part.split_once('/') {
            let b: u32 = bits
                .parse()
                .ok()
                .filter(|b| (16..=32).contains(b))
                .ok_or_else(|| RangeError::BadMask(bits.into()))?;
            let base = ip_to_u32(ip)?;
            let mask = if b == 32 { u32::MAX } else { !(u32::MAX >> b) };
            let net = base & mask;
            let bcast = net | !mask;
            // hálózati és broadcast cím kihagyása, ha van mit
            if b <= 30 { (net + 1, bcast - 1) } else { (net, bcast) }
        } else if let Some((a, b)) = part.split_once('-') {
            let start = ip_to_u32(a)?;
            let b = b.trim();
            let end = if b.contains('.') {
                ip_to_u32(b)?
            } else {
                let last: u8 = b.parse().map_err(|_| RangeError::BadRangeEnd(b.into()))?;
                (start & 0xFFFF_FF00) | last as u32
            };
            if end < start {
                return Err(RangeError::Reversed(part.into()));
            }
            (start, end)
        } else {
            let v = ip_to_u32(part)?;
            (v, v)
        };
        if (to - from) as usize + 1 + result.len() > MAX_ADDRESSES {
            return Err(RangeError::TooBig);
        }
        for v in from..=to {
            if seen.insert(v) {
                result.push(v);
            }
        }
    }
    if result.is_empty() {
        return Err(RangeError::Empty);
    }
    Ok(result.into_iter().map(Ipv4Addr::from).collect())
}

/// "554, 8554 8080" -> [554, 8554, 8080]
pub fn parse_ports(text: &str) -> Result<Vec<u16>, RangeError> {
    let mut ports = Vec::new();
    for p in split_list(text) {
        let n: u16 = p.parse().ok().filter(|&n| n > 0).ok_or_else(|| RangeError::BadPort(p.into()))?;
        if !ports.contains(&n) {
            ports.push(n);
        }
    }
    if ports.is_empty() {
        return Err(RangeError::PortsEmpty);
    }
    if ports.len() > MAX_PORTS {
        return Err(RangeError::PortsTooMany);
    }
    Ok(ports)
}

/// Blokkoló keresés 64 szálon. [on_found] (ip, port) és [on_progress] (kész címek száma)
/// háttérszálakról hívódik.
pub fn scan(
    ips: Vec<Ipv4Addr>,
    ports: Vec<u16>,
    on_found: impl Fn(Ipv4Addr, u16) + Send + Sync + 'static,
    on_progress: impl Fn(usize) + Send + Sync + 'static,
    cancelled: impl Fn() -> bool + Send + Sync + 'static,
) {
    let ips = Arc::new(ips);
    let ports = Arc::new(ports);
    let next = Arc::new(AtomicUsize::new(0));
    let done = Arc::new(AtomicUsize::new(0));
    let cb = Arc::new((on_found, on_progress, cancelled));
    let handles: Vec<_> = (0..THREADS.min(ips.len()))
        .map(|_| {
            let (ips, ports, next, done, cb) =
                (ips.clone(), ports.clone(), next.clone(), done.clone(), cb.clone());
            std::thread::spawn(move || {
                loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= ips.len() {
                        break;
                    }
                    let ip = ips[i];
                    for &port in ports.iter() {
                        if (cb.2)() {
                            break;
                        }
                        if TcpStream::connect_timeout(&SocketAddr::from((ip, port)), CONNECT_TIMEOUT).is_ok()
                        {
                            (cb.0)(ip, port);
                        }
                    }
                    (cb.1)(done.fetch_add(1, Ordering::Relaxed) + 1);
                }
            })
        })
        .collect();
    for h in handles {
        let _ = h.join();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ips(v: &[&str]) -> Vec<Ipv4Addr> {
        v.iter().map(|s| s.parse().unwrap()).collect()
    }

    #[test]
    fn ranges() {
        assert_eq!(parse_range("192.168.0.50").unwrap(), ips(&["192.168.0.50"]));
        assert_eq!(parse_range("10.0.0.1-3").unwrap(), ips(&["10.0.0.1", "10.0.0.2", "10.0.0.3"]));
        assert_eq!(parse_range("10.0.0.254-10.0.1.1").unwrap().len(), 4);
        assert_eq!(parse_range("192.168.1.0/24").unwrap().len(), 254);
        assert_eq!(parse_range("192.168.1.0/31").unwrap().len(), 2);
        assert_eq!(parse_range("10.0.0.1, 10.0.0.1-2").unwrap().len(), 2);
        assert_eq!(parse_range("10.0.0.0/8"), Err(RangeError::BadMask("8".into())));
        assert_eq!(parse_range("10.0.0.0/16").unwrap_err(), RangeError::TooBig);
        assert_eq!(parse_range("10.0.0.5-2"), Err(RangeError::Reversed("10.0.0.5-2".into())));
        assert_eq!(parse_range("10.0.0.5-300"), Err(RangeError::BadRangeEnd("300".into())));
        assert_eq!(parse_range("10.0.0"), Err(RangeError::BadIp("10.0.0".into())));
        assert_eq!(parse_range("  "), Err(RangeError::Empty));
    }

    #[test]
    fn ports() {
        assert_eq!(parse_ports("554, 8554 8080;554").unwrap(), vec![554, 8554, 8080]);
        assert_eq!(parse_ports("0"), Err(RangeError::BadPort("0".into())));
        assert_eq!(parse_ports("70000"), Err(RangeError::BadPort("70000".into())));
        assert_eq!(parse_ports(""), Err(RangeError::PortsEmpty));
        assert_eq!(parse_ports("1 2 3 4 5 6 7 8 9 10 11"), Err(RangeError::PortsTooMany));
    }

    #[test]
    fn finds_local_listener() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let found = Arc::new(std::sync::Mutex::new(Vec::new()));
        let f = found.clone();
        scan(ips(&["127.0.0.1"]), vec![port], move |ip, p| f.lock().unwrap().push((ip, p)), |_| {}, || false);
        assert_eq!(*found.lock().unwrap(), vec![("127.0.0.1".parse().unwrap(), port)]);
    }
}
