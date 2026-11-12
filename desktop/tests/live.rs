//! Élő tesztek a test-server kamu kameráival (Android projekt: ipCamApp/test-server).
//! Alapból kimaradnak; futtatás, ha a szerverek futnak:
//!   python fake_camera.py --user admin --password 1234     (HTTP 8080 + ONVIF)
//!   start-rtsp.ps1                                         (RTSP 8554 /stream1)
//!   cargo test --test live -- --ignored --test-threads=1

use ipcam_desktop::model::{Camera, StreamType};
use ipcam_desktop::net::tester::{self, TestResult};
use ipcam_desktop::stream::{Player, Status};
use std::time::{Duration, Instant};

fn cam(kind: StreamType, port: u16, path: &str, user: &str, pass: &str) -> Camera {
    // RTSP: IPv6 loopback (a 127.0.0.1:8554-et pl. az Android emulátor is foglalhatja)
    let host = if kind == StreamType::Rtsp { "::1" } else { "127.0.0.1" };
    let mut c = Camera::new("teszt", host, port, kind, path);
    c.username = user.into();
    c.password = pass.into();
    c
}

/// Lejátszás indítása, és várakozás [frames] képkockára; visszaadja az utolsó kép méretét.
fn play(c: &Camera, frames: usize) -> Result<[usize; 2], String> {
    let ctx = eframe::egui::Context::default();
    let p = Player::start(c, &ctx);
    let deadline = Instant::now() + Duration::from_secs(15);
    let mut got = 0;
    while Instant::now() < deadline {
        if let Some(img) = p.take_frame() {
            got += 1;
            if got >= frames {
                return Ok(img.size);
            }
        }
        if let Status::Error(e) = p.status() {
            return Err(e);
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    Err(format!("csak {got} képkocka érkezett"))
}

#[test]
#[ignore]
fn mjpeg_with_auth() {
    let size = play(&cam(StreamType::Mjpeg, 8080, "/video.mjpg", "admin", "1234"), 10).unwrap();
    assert!(size[0] > 0 && size[1] > 0);
}

#[test]
#[ignore]
fn snapshot_with_auth() {
    play(&cam(StreamType::Snapshot, 8080, "/snapshot.jpg", "admin", "1234"), 2).unwrap();
}

#[test]
#[ignore]
fn mjpeg_wrong_password_fails() {
    let err = play(&cam(StreamType::Mjpeg, 8080, "/video.mjpg", "admin", "rossz"), 1).unwrap_err();
    assert!(err.contains("401"), "{err}");
}

#[test]
#[ignore]
fn rtsp_h264() {
    let size = play(&cam(StreamType::Rtsp, 8554, "/stream1", "", ""), 25).unwrap();
    assert_eq!(size, [1280, 720]);
}

#[test]
#[ignore]
fn connection_tests() {
    assert_eq!(tester::test(&cam(StreamType::Mjpeg, 8080, "/video.mjpg", "admin", "1234")), TestResult::Ok);
    assert_eq!(
        tester::test(&cam(StreamType::Mjpeg, 8080, "/video.mjpg", "admin", "x")),
        TestResult::AuthFailed
    );
    assert_eq!(tester::test(&cam(StreamType::Rtsp, 8554, "/stream1", "", "")), TestResult::Ok);
    assert_eq!(tester::test(&cam(StreamType::Rtsp, 1, "/stream1", "", "")), TestResult::Unreachable);
}

#[test]
#[ignore]
fn onvif_discovery_and_streams() {
    let mut found = Vec::new();
    ipcam_desktop::net::onvif::discover(Duration::from_secs(3), || false, |d| found.push(d)).unwrap();
    println!("{found:#?}");
    let dev = found.first().expect("nincs ONVIF eszköz");
    assert!(matches!(
        ipcam_desktop::net::onvif::get_streams(dev, "admin", "rossz"),
        Err(ipcam_desktop::net::onvif::OnvifError::Auth)
    ));
    let streams =
        ipcam_desktop::net::onvif::get_streams(dev, "admin", "1234").map_err(|e| format!("{e:?}")).unwrap();
    println!("{streams:#?}");
    assert!(!streams.is_empty());
}
