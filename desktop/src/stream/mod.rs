//! Élőkép-lejátszó: minden kamera saját háttérszálon fut, a legutolsó képkocka egy
//! közös helyre kerül, ahonnan a felület textúraként tölti fel.
//!
//! - HTTP MJPEG: a folyamból kivágjuk a JPEG képkockákat (FFD8 … FFD9),
//! - HTTP JPEG: másodpercenként újratöltjük a képet,
//! - RTSP: retina (RTSP kliens) + openh264 (H.264 dekóder), tisztán Rustban,
//! - HLS: nem támogatott.
//!
//! Hiba esetén 3 mp múlva automatikusan újracsatlakozik.

mod mjpeg;
mod rtsp;

use crate::i18n::t;
use crate::model::{Camera, StreamType};
use eframe::egui::{self, ColorImage, TextureHandle, TextureOptions};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Várakozás újracsatlakozás előtt.
const RECONNECT_DELAY: Duration = Duration::from_secs(3);

/// Egy lejátszó állapota (a felület ezt jeleníti meg a kép fölött).
#[derive(Clone, Debug, PartialEq)]
pub enum Status {
    Connecting,
    Playing,
    Error(String),
}

/// A lejátszó szál és a felület közös adatai.
struct Shared {
    /// A legutóbbi, még fel nem töltött képkocka. Amíg a felület el nem vette, az újabbakat
    /// nem dekódoljuk (felesleges munka lenne).
    frame: Mutex<Option<ColorImage>>,
    status: Mutex<Status>,
    stop: AtomicBool,
    ctx: egui::Context,
}

impl Shared {
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }

    fn wants_frame(&self) -> bool {
        self.frame.lock().unwrap().is_none()
    }

    fn publish(&self, img: ColorImage) {
        *self.frame.lock().unwrap() = Some(img);
        let mut st = self.status.lock().unwrap();
        if *st != Status::Playing {
            *st = Status::Playing;
        }
        drop(st);
        self.ctx.request_repaint();
    }

    fn set_status(&self, s: Status) {
        *self.status.lock().unwrap() = s;
        self.ctx.request_repaint();
    }

    /// Alvás, amelyet a leállítás megszakít. Visszaad: true, ha közben leállították.
    fn sleep(&self, duration: Duration) -> bool {
        let step = Duration::from_millis(100);
        let mut left = duration;
        while !left.is_zero() {
            if self.stopped() {
                return true;
            }
            let d = left.min(step);
            std::thread::sleep(d);
            left -= d;
        }
        self.stopped()
    }
}

/// Egy kamera lejátszója; eldobáskor a háttérszál leáll.
pub struct Player {
    shared: Arc<Shared>,
    texture: Option<TextureHandle>,
    /// A kamera azon adatai, amelyek változásakor újra kell indítani (URL + belépés + típus).
    pub signature: String,
}

/// A kamera azon adatai, amelyek változásakor a lejátszót újra kell indítani.
pub fn signature(cam: &Camera) -> String {
    format!("{}|{}|{}|{}", cam.kind.name(), cam.build_url(false), cam.username, cam.password)
}

impl Player {
    pub fn start(cam: &Camera, ctx: &egui::Context) -> Player {
        let shared = Arc::new(Shared {
            frame: Mutex::new(None),
            status: Mutex::new(Status::Connecting),
            stop: AtomicBool::new(false),
            ctx: ctx.clone(),
        });
        let sh = shared.clone();
        let cam2 = cam.clone();
        std::thread::Builder::new()
            .name(format!("player-{}", cam.name))
            .spawn(move || run(cam2, sh))
            .expect("thread");
        Player { shared, texture: None, signature: signature(cam) }
    }

    pub fn status(&self) -> Status {
        self.shared.status.lock().unwrap().clone()
    }

    /// A legutóbbi, még el nem vett képkocka (a háttérszál addig nem dekódol újat).
    pub fn take_frame(&self) -> Option<ColorImage> {
        self.shared.frame.lock().unwrap().take()
    }

    /// Az új képkocka (ha van) feltöltése, és az aktuális textúra.
    pub fn texture(&mut self, ctx: &egui::Context, name: &str) -> Option<&TextureHandle> {
        if let Some(img) = self.take_frame() {
            match &mut self.texture {
                Some(t) => t.set(img, TextureOptions::LINEAR),
                None => self.texture = Some(ctx.load_texture(name, img, TextureOptions::LINEAR)),
            }
        }
        self.texture.as_ref()
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Relaxed);
    }
}

/// A lejátszó szál: csatlakozás, lejátszás, hiba esetén várakozás és újracsatlakozás.
fn run(cam: Camera, sh: Arc<Shared>) {
    if cam.kind == StreamType::Hls {
        sh.set_status(Status::Error(
            t(
                "A HLS (m3u8) streamet az asztali alkalmazás nem támogatja.",
                "HLS (m3u8) streams are not supported by the desktop app.",
            )
            .into(),
        ));
        return;
    }
    while !sh.stopped() {
        let result = match cam.kind {
            StreamType::Mjpeg => mjpeg::run_stream(&cam, &sh),
            StreamType::Snapshot => mjpeg::run_snapshot(&cam, &sh),
            StreamType::Rtsp => rtsp::run(&cam, &sh),
            StreamType::Hls => unreachable!(),
        };
        if sh.stopped() {
            return;
        }
        let msg = match result {
            Ok(()) => t("A kapcsolat megszakadt", "Connection lost").to_string(),
            Err(e) => e,
        };
        sh.set_status(Status::Error(msg));
        if sh.sleep(RECONNECT_DELAY) {
            return;
        }
        sh.set_status(Status::Connecting);
    }
}
