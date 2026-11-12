//! Csoport átnevezése és új csoport létrehozása (kamera áthelyezésekor).

use super::modal_buttons;
use crate::i18n::t;
use crate::ui::IpCamApp;
use eframe::egui;

/// A névbekérő ablak eredménye.
enum Prompt {
    Open,
    Ok(String),
    Cancel,
}

/// Egysoros név bekérése (Enter = OK, Esc = Mégse); üres név nem fogadható el.
fn name_prompt(ctx: &egui::Context, title: &str, value: &mut String, hint: &str) -> Prompt {
    let mut buttons = (false, false);
    egui::Window::new(title)
        .collapsible(false)
        .resizable(false)
        .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
        .show(ctx, |ui| {
            ui.add(egui::TextEdit::singleline(value).hint_text(hint)).request_focus();
            buttons = modal_buttons(ui, "OK", t("Mégse", "Cancel"));
        });
    let enter = ctx.input(|i| i.key_pressed(egui::Key::Enter));
    let name = value.trim();
    match buttons {
        (_, true) => Prompt::Cancel,
        (ok, _) if (ok || enter) && !name.is_empty() => Prompt::Ok(name.to_string()),
        _ => Prompt::Open,
    }
}

pub(super) fn rename_group_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some((old, new)) = app.group_rename.as_mut() else {
        return;
    };
    match name_prompt(ctx, t("Csoport átnevezése", "Rename group"), new, t("Csoport neve", "Group name")) {
        Prompt::Open => {}
        Prompt::Cancel => app.group_rename = None,
        Prompt::Ok(name) => {
            let old = old.clone();
            app.group_rename = None;
            app.rename_group(&old, &name);
        }
    }
}

pub(super) fn new_group_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some((id, name)) = app.new_group_for.as_mut() else {
        return;
    };
    match name_prompt(ctx, t("Új csoport", "New group"), name, t("Csoport neve", "Group name")) {
        Prompt::Open => {}
        Prompt::Cancel => app.new_group_for = None,
        Prompt::Ok(group) => {
            let id = id.clone();
            app.new_group_for = None;
            app.set_group(&id, group);
        }
    }
}
