//! Felső eszköztár: hozzáadás, keresés, osztott nézet, oszlopok, teljes képernyő, beállítások.

use super::dialogs::{Discovery, Editor};
use super::{IpCamApp, View};
use crate::i18n::t;
use crate::model::{Camera, StreamType};
use eframe::egui;

impl IpCamApp {
    pub(super) fn toolbar(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.heading(t("IP Kamerák", "IP Cameras"));
            ui.add_space(12.0);
            let add = ui.button(format!("➕ {}", t("Kamera", "Camera")));
            if add.on_hover_text(t("Kamera hozzáadása", "Add camera")).clicked() {
                let blank =
                    Camera::new("", "", StreamType::Rtsp.default_port(), StreamType::Rtsp, "/stream1");
                self.editor = Some(Editor::new(blank, true));
            }
            let discover = ui.button(format!("🔍 {}", t("Keresés", "Discover")));
            if discover
                .on_hover_text(t("Kamerák keresése a hálózaton", "Find cameras on the network"))
                .clicked()
            {
                self.discovery = Some(Discovery::new(&self.settings, ui.ctx()));
            }

            ui.separator();
            let grid = self.view == View::Grid;
            if ui.selectable_label(grid, format!("⊞ {}", t("Osztott nézet", "Split view"))).clicked() {
                self.view = if grid { View::Home } else { View::Grid };
            }
            if grid {
                self.columns_picker(ui);
            }

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button(format!("⚙ {}", t("Beállítások", "Settings"))).clicked() {
                    self.settings_open = true;
                }
                if self.view != View::Home
                    && ui.button("⛶").on_hover_text(t("Teljes képernyő (F11)", "Full screen (F11)")).clicked()
                {
                    self.set_fullscreen(ui.ctx(), true);
                }
            });
        });
    }

    /// Az osztott nézet oszlopszáma (automatikus vagy 1–6).
    fn columns_picker(&mut self, ui: &mut egui::Ui) {
        let current = match self.settings.columns {
            0 => t("Automatikus", "Automatic").to_string(),
            n => n.to_string(),
        };
        let mut columns = self.settings.columns;
        egui::ComboBox::from_id_salt("cols")
            .selected_text(format!("{}: {current}", t("Oszlopok", "Columns")))
            .show_ui(ui, |ui| {
                ui.selectable_value(&mut columns, 0, t("Automatikus", "Automatic"));
                for n in 1..=6 {
                    ui.selectable_value(&mut columns, n, n.to_string());
                }
            });
        if columns != self.settings.columns {
            self.settings.columns = columns;
            self.save_settings(ui.ctx());
        }
    }
}
