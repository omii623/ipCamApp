//! Mentés exportálása fájlba és visszatöltése (összefésülés vagy felülírás).
//! A fájlformátum az Android appal közös ([crate::storage::backup]).

use super::Confirm;
use crate::i18n::t;
use crate::model;
use crate::storage::backup;
use crate::ui::IpCamApp;
use eframe::egui;
use serde_json::Value;

/// Fájl kiválasztása, ellenőrzése, majd rákérdezés a betöltés módjára.
pub(super) fn choose_import_file(app: &mut IpCamApp) {
    let Some(path) = rfd::FileDialog::new().add_filter("JSON", &["json"]).pick_file() else {
        return;
    };
    let root = std::fs::read_to_string(&path).ok().and_then(|text| serde_json::from_str::<Value>(&text).ok());
    match root {
        Some(root) if backup::is_valid(&root) => app.confirm = Some(Confirm::ImportMode(root)),
        _ => app.toast(t("Érvénytelen mentésfájl", "Invalid backup file")),
    }
}

/// Mentés fájlba (a figyelmeztetés elfogadása után).
pub(super) fn export(app: &mut IpCamApp) {
    let dialog = rfd::FileDialog::new().add_filter("JSON", &["json"]).set_file_name("ipcam-backup.json");
    let Some(path) = dialog.save_file() else {
        return;
    };
    let root = backup::build(&app.cameras, &app.presets, &app.settings);
    match std::fs::write(&path, serde_json::to_string_pretty(&root).unwrap_or_default()) {
        Ok(()) => app.toast(t("Mentés elkészült", "Backup saved")),
        Err(e) => app.toast_err(e),
    }
}

/// A mentés betöltése. Előbb minden beolvasás és ellenőrzés; hiba esetén semmi nem változik.
pub(super) fn import(app: &mut IpCamApp, ctx: &egui::Context, root: &Value, replace: bool) {
    let cameras = backup::parse_cameras(root);
    let presets = backup::parse_presets(root);
    let settings = match backup::parse_settings(root) {
        Ok(s) => s,
        Err(e) => {
            app.toast(format!("{}: {e}", t("Érvénytelen mentésfájl", "Invalid backup file")));
            return;
        }
    };
    if replace {
        app.cameras = cameras;
        app.presets = presets;
    } else {
        app.cameras = model::merge_by_id(&app.cameras, &cameras);
        app.presets = model::merge_presets_by_name(&app.presets, &presets);
    }
    settings.apply(&mut app.settings);
    app.save_cameras();
    app.save_presets();
    app.save_settings(ctx);
    app.toast(t("Importálás kész", "Import finished"));
}
