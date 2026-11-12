//! QR-kód készítése (kamera megosztása a telefonnak).

use eframe::egui::{Color32, ColorImage};

/// QR-kód képként: modulonként [scale] képpont, 4 modulnyi fehér margóval.
pub fn encode(text: &str, scale: usize) -> Option<ColorImage> {
    let code = qrcode::QrCode::new(text.as_bytes()).ok()?;
    let w = code.width();
    let colors = code.to_colors();
    let quiet = 4;
    let size = (w + 2 * quiet) * scale;
    let mut img = ColorImage::new([size, size], vec![Color32::WHITE; size * size]);
    for y in 0..w {
        for x in 0..w {
            if colors[y * w + x] == qrcode::Color::Dark {
                for dy in 0..scale {
                    let row = ((y + quiet) * scale + dy) * size;
                    for dx in 0..scale {
                        img.pixels[row + (x + quiet) * scale + dx] = Color32::BLACK;
                    }
                }
            }
        }
    }
    Some(img)
}

#[cfg(test)]
mod tests {
    #[test]
    fn encode_has_quiet_zone_and_modules() {
        let img = super::encode(r#"{"format":"ipcam-camera","version":1}"#, 4).unwrap();
        let [w, h] = img.size;
        assert_eq!(w, h);
        assert_eq!(img.pixels[0], eframe::egui::Color32::WHITE); // margó
        // a bal felső helymeghatározó minta sarka a 4 modulnyi margó után sötét
        assert_eq!(img.pixels[16 * w + 16], eframe::egui::Color32::BLACK);
    }
}
