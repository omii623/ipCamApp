//! Kamera megosztása QR-kóddal (az Android app beolvassa).

use crate::i18n::t;
use crate::ui::IpCamApp;
use crate::ui::theme::WARNING;
use eframe::egui::{self, RichText};

pub(super) fn share_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some((name, tex)) = &app.qr_show else {
        return;
    };
    let mut open = true;
    egui::Window::new(format!("{}: {name}", t("Megosztás", "Share")))
        .id(egui::Id::unique("qr_show"))
        .open(&mut open)
        .collapsible(false)
        .resizable(false)
        .show(ctx, |ui| {
            let warning = t(
                "⚠ A QR-kód a kamera jelszavát is tartalmazza. Csak megbízható eszközzel olvastasd be.",
                "⚠ The QR code contains the camera password. Only scan it with a trusted device.",
            );
            ui.label(RichText::new(warning).color(WARNING));
            ui.label(t(
                "Olvasd be az Android IP Kamerák alkalmazással (+ → QR beolvasás).",
                "Scan it with the Android IP Cameras app (+ → Scan QR).",
            ));
            ui.image((tex.id(), tex.size_vec2().min(egui::vec2(380.0, 380.0))));
        });
    if !open {
        app.qr_show = None;
    }
}
