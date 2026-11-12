//! Beállítások ablak: téma, nyelv, kiemelőszín, keresési portok, adatok, névjegy.

use super::{Confirm, backup, form_row};
use crate::i18n::t;
use crate::net::scanner;
use crate::storage::{NIGHT_DARK, NIGHT_LIGHT, NIGHT_SYSTEM, Settings};
use crate::ui::IpCamApp;
use crate::ui::theme::{ERROR, accent_color, to_argb};
use eframe::egui::{self, RichText};

pub(super) fn settings_window(app: &mut IpCamApp, ctx: &egui::Context) {
    if !app.settings_open {
        return;
    }
    let mut open = true;
    let mut edited = app.settings.clone();
    let (mut export, mut import) = (false, false);

    egui::Window::new(t("Beállítások", "Settings"))
        .id(egui::Id::unique("settings"))
        .open(&mut open)
        .collapsible(false)
        .resizable(false)
        .default_width(420.0)
        .show(ctx, |ui| {
            appearance_form(ui, &mut edited);
            ui.separator();
            ui.strong(t("Adatok", "Data"));
            ui.horizontal(|ui| {
                export = ui.button(format!("⬆ {}", t("Exportálás…", "Export…"))).clicked();
                import = ui.button(format!("⬇ {}", t("Importálás…", "Import…"))).clicked();
            });
            let note = t(
                "A mentés kompatibilis az Android IP Kamerák alkalmazással (kamerák, sablonok, beállítások).",
                "Backups are compatible with the Android IP Cameras app (cameras, presets, settings).",
            );
            ui.label(RichText::new(note).small().weak());
            ui.separator();
            about(ui);
        });

    if edited != app.settings {
        app.settings = edited;
        app.save_settings(ctx);
    }
    if export {
        app.confirm = Some(Confirm::ExportWarning);
    }
    if import {
        backup::choose_import_file(app);
    }
    if !open {
        app.settings_open = false;
    }
}

fn appearance_form(ui: &mut egui::Ui, s: &mut Settings) {
    egui::Grid::new("settings_grid").num_columns(2).spacing([12.0, 10.0]).show(ui, |ui| {
        form_row(ui, t("Téma", "Theme"), |ui| {
            ui.selectable_value(&mut s.night_mode, NIGHT_SYSTEM, t("Rendszer szerint", "System"));
            ui.selectable_value(&mut s.night_mode, NIGHT_LIGHT, t("Világos", "Light"));
            ui.selectable_value(&mut s.night_mode, NIGHT_DARK, t("Sötét", "Dark"));
        });
        form_row(ui, t("Nyelv", "Language"), |ui| {
            ui.selectable_value(&mut s.language, String::new(), t("Rendszer szerint", "System"));
            ui.selectable_value(&mut s.language, "hu".to_string(), "Magyar");
            ui.selectable_value(&mut s.language, "en".to_string(), "English");
        });
        form_row(ui, t("Kiemelőszín", "Accent color"), |ui| {
            let mut color = accent_color(s.accent).unwrap_or(ui.visuals().selection.bg_fill);
            if ui.color_edit_button_srgba(&mut color).changed() {
                s.accent = to_argb(color);
            }
            if s.accent != 0 && ui.small_button(t("Alapértelmezett", "Default")).clicked() {
                s.accent = 0;
            }
        });
        form_row(ui, t("Keresési portok", "Search ports"), |ui| {
            ui.vertical(|ui| {
                ui.add(egui::TextEdit::singleline(&mut s.search_ports).desired_width(200.0));
                if let Err(e) = scanner::parse_ports(&s.search_ports) {
                    ui.colored_label(ERROR, e.to_string());
                }
            });
        });
    });
}

fn about(ui: &mut egui::Ui) {
    ui.strong(t("Névjegy", "About"));
    ui.label(format!("ipCamDesktop · v{}", env!("CARGO_PKG_VERSION")));
    let text = t(
        "IP kamerák megtekintése RTSP (H.264), HTTP MJPEG és JPEG streamen keresztül, a helyi hálózaton.",
        "View IP cameras over RTSP (H.264), HTTP MJPEG and JPEG streams on your local network.",
    );
    ui.label(RichText::new(text).small());
}
