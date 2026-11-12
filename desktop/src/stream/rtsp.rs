//! RTSP lejátszás tisztán Rustban: retina (RTSP kliens) + openh264 (H.264 dekóder).

use super::Shared;
use crate::i18n::t;
use crate::model::Camera;
use eframe::egui::ColorImage;
use std::time::Duration;

pub(super) fn run(cam: &Camera, sh: &Shared) -> Result<(), String> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(rtsp_session(cam, sh))
}

async fn rtsp_session(cam: &Camera, sh: &Shared) -> Result<(), String> {
    use futures::StreamExt;
    use openh264::formats::YUVSource;
    use retina::client::{Credentials, PlayOptions, Session, SessionOptions, SetupOptions};
    use retina::codec::{CodecItem, FrameFormat};

    let url = url::Url::parse(&cam.display_url()).map_err(|e| e.to_string())?;
    let creds = (!cam.username.is_empty())
        .then(|| Credentials { username: cam.username.clone(), password: cam.password.clone() });
    let opts = SessionOptions::default().creds(creds).user_agent("ipCamDesktop".into());
    let mut session = tokio::time::timeout(Duration::from_secs(10), Session::describe(url, opts))
        .await
        .map_err(|_| t("Időtúllépés (DESCRIBE)", "Timeout (DESCRIBE)").to_string())?
        .map_err(|e| e.to_string())?;

    let idx = session
        .streams()
        .iter()
        .position(|s| s.media() == "video")
        .ok_or_else(|| t("A kamera nem küld videót", "The camera has no video stream").to_string())?;
    let enc = session.streams()[idx].encoding_name().to_ascii_lowercase();
    if enc != "h264" {
        return Err(match crate::i18n::lang() {
            crate::i18n::Lang::Hu => format!(
                "Nem támogatott kodek: {enc} (csak H.264). Állítsd a kamerát H.264-re, vagy használd az alstreamet."
            ),
            crate::i18n::Lang::En => format!(
                "Unsupported codec: {enc} (H.264 only). Switch the camera to H.264 or use the sub-stream."
            ),
        });
    }
    session
        .setup(idx, SetupOptions::default().frame_format(FrameFormat::SIMPLE))
        .await
        .map_err(|e| e.to_string())?;
    let mut demuxed = session
        .play(PlayOptions::default())
        .await
        .map_err(|e| e.to_string())?
        .demuxed()
        .map_err(|e| e.to_string())?;

    let mut decoder = openh264::decoder::Decoder::new().map_err(|e| e.to_string())?;
    let mut idle = 0u32;
    let mut rgba = Vec::new();
    loop {
        if sh.stopped() {
            return Ok(());
        }
        let item = match tokio::time::timeout(Duration::from_millis(500), demuxed.next()).await {
            Err(_) => {
                idle += 1;
                if idle > 20 {
                    return Err(t("Nem érkezik kép (időtúllépés)", "No video received (timeout)").into());
                }
                continue;
            }
            Ok(None) => return Ok(()),
            Ok(Some(Err(e))) => return Err(e.to_string()),
            Ok(Some(Ok(item))) => item,
        };
        idle = 0;
        if let CodecItem::VideoFrame(frame) = item {
            // minden képkockát dekódolni kell (a következők ezekre épülnek),
            // de RGBA-ra csak akkor alakítunk, ha a felület már elvette az előzőt
            match decoder.decode(frame.data()) {
                Ok(Some(yuv)) if sh.wants_frame() => {
                    let (w, h) = yuv.dimensions();
                    rgba.resize(w * h * 4, 0);
                    yuv.write_rgba8(&mut rgba);
                    sh.publish(ColorImage::from_rgba_unmultiplied([w, h], &rgba));
                }
                _ => {} // dekódolási hiba (pl. csomagvesztés): a következő kulcskockánál helyreáll
            }
        }
    }
}
