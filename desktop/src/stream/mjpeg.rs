//! HTTP MJPEG (folyamatos) és HTTP JPEG (másodpercenkénti újratöltés) lejátszás.

use super::Shared;
use crate::i18n::t;
use crate::model::Camera;
use eframe::egui::ColorImage;
use std::io::Read;
use std::time::Duration;

/// A JPEG állókép frissítési ideje.
const SNAPSHOT_INTERVAL: Duration = Duration::from_secs(1);

fn decode_jpeg(data: &[u8]) -> Option<ColorImage> {
    let img = image::load_from_memory_with_format(data, image::ImageFormat::Jpeg).ok()?.to_rgba8();
    let size = [img.width() as usize, img.height() as usize];
    Some(ColorImage::from_rgba_unmultiplied(size, img.as_raw()))
}

pub(super) fn run_stream(cam: &Camera, sh: &Shared) -> Result<(), String> {
    let agent = crate::net::http::agent(Duration::from_secs(10));
    let mut digest = None;
    let resp = crate::net::http::get(&agent, &cam.display_url(), &cam.username, &cam.password, &mut digest)
        .map_err(|e| e.to_string())?;
    let mut reader = resp.into_reader();
    read_mjpeg(
        &mut reader,
        || sh.stopped(),
        |frame| {
            if sh.wants_frame()
                && let Some(img) = decode_jpeg(frame)
            {
                sh.publish(img);
            }
        },
    )
}

const MAX_FRAME: usize = 16 * 1024 * 1024;

/// Az MJPEG folyam képkockáinak kivágása blokkos olvasással.
/// Ok(()) = leállították; Err = olvasási hiba / a folyam véget ért.
pub fn read_mjpeg(
    input: &mut dyn Read,
    stopped: impl Fn() -> bool,
    mut on_frame: impl FnMut(&[u8]),
) -> Result<(), String> {
    let mut buf: Vec<u8> = Vec::with_capacity(512 * 1024);
    let mut chunk = vec![0u8; 64 * 1024];
    let mut start: Option<usize> = None; // az aktuális képkocka eleje (FFD8)
    let mut scan = 1; // innen folytatjuk a keresést
    loop {
        if stopped() {
            return Ok(());
        }
        let n = input.read(&mut chunk).map_err(|e| e.to_string())?;
        if n == 0 {
            return Err(t("A kapcsolat megszakadt", "Connection lost").into());
        }
        buf.extend_from_slice(&chunk[..n]);
        let mut i = scan.max(1);
        while i < buf.len() {
            if buf[i - 1] == 0xFF {
                let b = buf[i];
                if start.is_none() && b == 0xD8 {
                    start = Some(i - 1);
                } else if let (Some(s), 0xD9) = (start, b) {
                    on_frame(&buf[s..=i]);
                    buf.drain(..=i);
                    start = None;
                    i = 0;
                }
            }
            i += 1;
        }
        match start {
            // képkockán kívüli bájtok: csak az utolsó kell (egy félbevágott FFD8 miatt)
            None => {
                if let Some(&last) = buf.last() {
                    buf.clear();
                    buf.push(last);
                }
            }
            // a félkész képkocka a puffer elejére
            Some(s) if s > 0 => {
                buf.drain(..s);
                start = Some(0);
            }
            _ => {}
        }
        if buf.len() > MAX_FRAME {
            buf.clear(); // hibás folyam
            start = None;
        }
        scan = buf.len().max(1);
    }
}

pub(super) fn run_snapshot(cam: &Camera, sh: &Shared) -> Result<(), String> {
    let agent = crate::net::http::agent(Duration::from_secs(10));
    let mut digest = None;
    while !sh.stopped() {
        let resp =
            crate::net::http::get(&agent, &cam.display_url(), &cam.username, &cam.password, &mut digest)
                .map_err(|e| e.to_string())?;
        let mut data = Vec::new();
        resp.into_reader().take(MAX_FRAME as u64).read_to_end(&mut data).map_err(|e| e.to_string())?;
        let img =
            decode_jpeg(&data).ok_or_else(|| t("Érvénytelen JPEG kép", "Invalid JPEG image").to_string())?;
        sh.publish(img);
        sh.sleep(SNAPSHOT_INTERVAL);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::read_mjpeg;

    /// Apró darabokban érkező folyam: a képkockák határai darabolás közben is megtalálhatók.
    #[test]
    fn splits_frames_across_chunks() {
        let mut stream = Vec::new();
        stream.extend_from_slice(b"--boundary\r\nContent-Type: image/jpeg\r\n\r\n");
        stream.extend_from_slice(&[0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9]);
        stream.extend_from_slice(b"\r\n--boundary\r\n\r\n");
        stream.extend_from_slice(&[0xFF, 0xD8, 9, 0xFF, 0x00, 8, 0xFF, 0xD9]);

        struct Slow(Vec<u8>, usize);
        impl std::io::Read for Slow {
            fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                let n = 3.min(self.0.len() - self.1).min(buf.len());
                buf[..n].copy_from_slice(&self.0[self.1..self.1 + n]);
                self.1 += n;
                Ok(n)
            }
        }
        let mut frames = Vec::new();
        let r = read_mjpeg(&mut Slow(stream, 0), || false, |f| frames.push(f.to_vec()));
        assert!(r.is_err()); // a folyam végén "megszakadt"
        assert_eq!(
            frames,
            vec![vec![0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9], vec![0xFF, 0xD8, 9, 0xFF, 0x00, 8, 0xFF, 0xD9]]
        );
    }
}
