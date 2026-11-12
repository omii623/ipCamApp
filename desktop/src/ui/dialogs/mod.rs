//! Párbeszédablakok, mindegyik a saját fájljában; a [show_all] jeleníti meg a nyitottakat.

mod backup;
mod confirm;
mod discovery;
mod editor;
mod groups;
mod settings;
mod share;

pub use confirm::Confirm;
pub use discovery::Discovery;
pub use editor::Editor;

use crate::ui::IpCamApp;
use eframe::egui;
use std::sync::{Arc, Mutex, MutexGuard};

/// Minden nyitott ablak megjelenítése.
pub fn show_all(app: &mut IpCamApp, ctx: &egui::Context) {
    editor::editor_window(app, ctx);
    discovery::discovery_window(app, ctx);
    settings::settings_window(app, ctx);
    share::share_window(app, ctx);
    groups::rename_group_window(app, ctx);
    groups::new_group_window(app, ctx);
    confirm::confirm_window(app, ctx);
    confirm::locked_notice(app, ctx);
}

/// OK / Mégse gombsor (Esc = Mégse). Visszaad: (ok, mégse).
fn modal_buttons(ui: &mut egui::Ui, ok: &str, cancel: &str) -> (bool, bool) {
    let mut res = (false, false);
    ui.add_space(8.0);
    ui.horizontal(|ui| {
        res.0 = ui.button(ok).clicked();
        res.1 = ui.button(cancel).clicked() || ui.input(|i| i.key_pressed(egui::Key::Escape));
    });
    res
}

/// Egy címkézett sor egy kétoszlopos `egui::Grid`-ben.
fn form_row(ui: &mut egui::Ui, label: &str, content: impl FnOnce(&mut egui::Ui)) {
    ui.label(label);
    ui.horizontal(content);
    ui.end_row();
}

/// Háttérszálon futó művelet; az eredményét a felület képkockánként lekérdezi.
pub(super) struct Task<T>(Arc<Mutex<Option<T>>>);

impl<T: Send + 'static> Task<T> {
    /// Indítás háttérszálon; befejezéskor újrarajzolást kér.
    pub fn spawn(ctx: &egui::Context, job: impl FnOnce() -> T + Send + 'static) -> Self {
        let slot = Arc::new(Mutex::new(None));
        let (slot2, ctx) = (slot.clone(), ctx.clone());
        std::thread::spawn(move || {
            let result = job();
            *slot2.lock().unwrap() = Some(result);
            ctx.request_repaint();
        });
        Task(slot)
    }

    pub fn is_running(&self) -> bool {
        self.0.lock().unwrap().is_none()
    }

    /// Az eredmény (None, amíg fut).
    pub fn result(&self) -> MutexGuard<'_, Option<T>> {
        self.0.lock().unwrap()
    }
}
