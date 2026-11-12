//! Megerősítő ablakok (törlés, csoport megszüntetése, export, import) és a jelszó-figyelmeztetés.

use super::backup;
use crate::i18n::{Lang, lang, t};
use crate::ui::IpCamApp;
use eframe::egui;
use serde_json::Value;

/// Megerősítést igénylő művelet.
pub enum Confirm {
    /// (kamera id, név)
    DeleteCamera(String, String),
    DissolveGroup(String),
    RemovePreset(String),
    ExportWarning,
    /// A betöltendő (már ellenőrzött) mentés.
    ImportMode(Value),
}

/// A felhasználó döntése.
#[derive(Clone, Copy, PartialEq)]
enum Choice {
    Cancel,
    /// OK, illetve importnál „Összefésülés”.
    Accept,
    /// Csak importnál: „Felülírás”.
    Replace,
}

impl Confirm {
    /// (cím, szöveg)
    fn texts(&self) -> (&'static str, String) {
        let hu = lang() == Lang::Hu;
        match self {
            Confirm::DeleteCamera(_, name) => (
                t("Törlés", "Delete"),
                if hu { format!("Biztosan törlöd: {name}?") } else { format!("Delete {name}?") },
            ),
            Confirm::DissolveGroup(g) => (
                t("Csoport megszüntetése", "Dissolve group"),
                if hu {
                    format!("A(z) „{g}” csoport megszűnik. A kamerák megmaradnak, csak csoport nélküliek lesznek.")
                } else {
                    format!("The group \"{g}\" will be removed. Cameras are kept, just ungrouped.")
                },
            ),
            Confirm::RemovePreset(name) => (
                t("Sablon törlése", "Remove preset"),
                if hu { format!("Törlöd ezt a sablont: {name}?") } else { format!("Remove this preset: {name}?") },
            ),
            Confirm::ExportWarning => (
                t("Exportálás", "Export"),
                t(
                    "A mentésfájl a kamerák jelszavait OLVASHATÓAN tartalmazza. Csak biztonságos helyre mentsd. Folytatod?",
                    "The backup file contains camera passwords in PLAIN TEXT. Store it somewhere safe. Continue?",
                )
                .into(),
            ),
            Confirm::ImportMode(root) => {
                let cams = crate::storage::backup::parse_cameras(root).len();
                let presets = crate::storage::backup::parse_presets(root).len();
                (
                    t("Importálás", "Import"),
                    if hu {
                        format!(
                            "{cams} kamera és {presets} sablon. Hogyan töltsük be?\n\
                             Összefésülés: a meglévők megmaradnak, az azonosak frissülnek.\n\
                             Felülírás: a jelenlegi lista törlődik."
                        )
                    } else {
                        format!(
                            "{cams} cameras and {presets} presets. How should they be loaded?\n\
                             Merge: existing ones stay, matching ones are updated.\n\
                             Replace: the current list is cleared."
                        )
                    },
                )
            }
        }
    }

    fn execute(self, app: &mut IpCamApp, ctx: &egui::Context, choice: Choice) {
        match self {
            Confirm::DeleteCamera(id, _) => {
                app.cameras.retain(|c| c.id != id);
                app.save_cameras();
            }
            Confirm::DissolveGroup(g) => app.dissolve_group(&g),
            Confirm::RemovePreset(name) => {
                app.presets.retain(|p| p.label != name);
                app.save_presets();
                if let Some(ed) = app.editor.as_mut() {
                    ed.preset = None;
                }
            }
            Confirm::ExportWarning => backup::export(app),
            Confirm::ImportMode(root) => backup::import(app, ctx, &root, choice == Choice::Replace),
        }
    }
}

pub(super) fn confirm_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some(confirm) = app.confirm.as_ref() else {
        return;
    };
    let (title, text) = confirm.texts();
    let is_import = matches!(confirm, Confirm::ImportMode(_));
    let mut choice = None;

    let modal = egui::Modal::new(egui::Id::unique("confirm")).show(ctx, |ui| {
        ui.set_max_width(420.0);
        ui.heading(title);
        ui.add_space(6.0);
        ui.label(text);
        ui.add_space(10.0);
        ui.horizontal(|ui| {
            let buttons: &[(&str, Choice)] = if is_import {
                &[(t("Összefésülés", "Merge"), Choice::Accept), (t("Felülírás", "Replace"), Choice::Replace)]
            } else {
                &[("OK", Choice::Accept)]
            };
            for &(label, c) in buttons.iter().chain([(t("Mégse", "Cancel"), Choice::Cancel)].iter()) {
                if ui.button(label).clicked() {
                    choice = Some(c);
                }
            }
        });
    });
    if modal.should_close() && choice.is_none() {
        choice = Some(Choice::Cancel);
    }
    let Some(choice) = choice else { return };
    let confirm = app.confirm.take().expect("megerősítés nyitva");
    if choice != Choice::Cancel {
        confirm.execute(app, ctx, choice);
    }
}

/// Figyelmeztetés induláskor, ha egyes jelszavak nem fejthetők vissza ezen a gépen.
pub(super) fn locked_notice(app: &mut IpCamApp, ctx: &egui::Context) {
    let n = app.locked_notice;
    if n == 0 {
        return;
    }
    let text = match lang() {
        Lang::Hu => format!(
            "{n} kamera jelszava nem olvasható ezen a gépen (pl. más Windows-felhasználó vagy gép mentette). \
             Szerkeszd ezeket a kamerákat, és add meg újra a jelszót."
        ),
        Lang::En => format!(
            "The password of {n} camera(s) cannot be read on this computer (e.g. saved by another Windows user \
             or machine). Edit those cameras and enter the password again."
        ),
    };
    egui::Window::new(t("Jelszavak", "Passwords"))
        .collapsible(false)
        .resizable(false)
        .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
        .show(ctx, |ui| {
            ui.label(text);
            if ui.button("OK").clicked() {
                app.locked_notice = 0;
            }
        });
}
