//! H.265 (HEVC) dekódolás az FFmpeg libavcodec-kel (statikusan linkelve, csak dekóder + swscale).
//!
//! A retina `FrameFormat::SIMPLE` beállítással Annex B access unitokat ad, a kulcskockák
//! előtt a VPS/SPS/PPS-sel – ezt a libavcodec közvetlenül, parser nélkül elfogadja.

use eframe::egui::ColorImage;
use ffmpeg_next as ff;
use ff::util::format::Pixel;
use ff::software::scaling;

pub(super) struct HevcDecoder {
    dec: ff::decoder::Video,
    /// A dekódolt (YUV) képkocka; újrahasznosítva.
    yuv: ff::frame::Video,
    /// YUV -> RGBA átalakító és a kimeneti kép; formátum- vagy méretváltáskor újraépül.
    scaler: Option<Scaler>,
}

struct Scaler {
    ctx: scaling::Context,
    src: (Pixel, u32, u32),
    out: ff::frame::Video,
}

impl HevcDecoder {
    pub(super) fn new() -> Result<Self, String> {
        static QUIET: std::sync::Once = std::sync::Once::new();
        // csomagvesztéskor a libavcodec a stderr-re panaszkodna; a hibát mi kezeljük
        QUIET.call_once(|| ff::log::set_level(ff::log::Level::Quiet));

        let codec = ff::decoder::find(ff::codec::Id::HEVC).ok_or("HEVC decoder not available")?;
        let mut ctx = ff::codec::Context::new_with_codec(codec);
        // élőképhez: alacsony késleltetés, slice-szálak (a frame-threading késleltetne)
        ctx.set_flags(ff::codec::Flags::LOW_DELAY);
        ctx.set_threading(ff::threading::Config {
            kind: ff::threading::Type::Slice,
            count: 0, // automatikus (CPU-magok száma)
            ..Default::default()
        });
        let dec = ctx.decoder().video().map_err(|e| e.to_string())?;
        Ok(Self { dec, yuv: ff::frame::Video::empty(), scaler: None })
    }

    /// Egy access unit dekódolása. Ha `want` igaz és elkészült egy képkocka, RGBA képként
    /// visszaadja; dekódolási hiba (pl. csomagvesztés) esetén `None` – a következő
    /// kulcskockánál helyreáll.
    pub(super) fn decode(&mut self, au: &[u8], want: bool) -> Option<ColorImage> {
        self.dec.send_packet(&ff::Packet::copy(au)).ok()?;
        let mut out = None;
        // LOW_DELAY mellett egy csomagból legfeljebb egy kép jön, de mindent kiolvasunk.
        // Az átalakítás a cikluson belül kell: a receive_frame minden hívás elején üríti
        // a képkockát (av_frame_unref), így a záró, sikertelen hívás után már nincs mit.
        while self.dec.receive_frame(&mut self.yuv).is_ok() {
            if want {
                out = self.to_rgba();
            }
        }
        out
    }

    fn to_rgba(&mut self) -> Option<ColorImage> {
        let src = (self.yuv.format(), self.yuv.width(), self.yuv.height());
        let (_, w, h) = src;
        if w == 0 || h == 0 {
            return None;
        }
        if self.scaler.as_ref().is_none_or(|s| s.src != src) {
            let ctx =
                scaling::Context::get(src.0, w, h, Pixel::RGBA, w, h, scaling::Flags::BILINEAR).ok()?;
            self.scaler = Some(Scaler { ctx, src, out: ff::frame::Video::empty() });
        }
        let s = self.scaler.as_mut()?;
        s.ctx.run(&self.yuv, &mut s.out).ok()?;

        // a sorhossz (stride) nagyobb lehet, mint w*4 -> soronként másolunk
        let (w, h) = (w as usize, h as usize);
        let stride = s.out.stride(0);
        let data = s.out.data(0);
        let mut rgba = Vec::with_capacity(w * h * 4);
        for row in data.chunks(stride).take(h) {
            rgba.extend_from_slice(&row[..w * 4]);
        }
        Some(ColorImage::from_rgba_unmultiplied([w, h], &rgba))
    }
}
