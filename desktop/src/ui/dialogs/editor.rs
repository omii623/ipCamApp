//! Kamera hozzáadása / szerkesztése: sablonok, kapcsolatteszt, új gyártói sablon.

use super::{Task, form_row, modal_buttons};
use crate::i18n::t;
use crate::model::{self, Camera, Preset, StreamType};
use crate::net::tester::{self, TestResult};
use crate::ui::dialogs::Confirm;
use crate::ui::theme::{ERROR, SUCCESS, WARNING};
use crate::ui::{IpCamApp, View};
use eframe::egui::{self, RichText};

pub struct Editor {
    cam: Camera,
    is_new: bool,
    port_text: String,
    /// A kiválasztott sablon indexe a (beépített + saját) listában.
    pub(super) preset: Option<usize>,
    /// Some = az „új gyártói sablon” ablak nyitva, a beírt névvel.
    new_preset: Option<String>,
    test: Option<Task<TestResult>>,
    error: Option<String>,
    show_password: bool,
}

/// Amit a szerkesztő ablak kér az alkalmazástól (a megjelenítés után hajtjuk végre).
enum Event {
    Save,
    Close,
    AddPreset(Preset),
    RemovePreset(String),
}

impl Editor {
    pub fn new(cam: Camera, is_new: bool) -> Self {
        Editor {
            port_text: cam.port.to_string(),
            cam,
            is_new,
            preset: None,
            new_preset: None,
            test: None,
            error: None,
            show_password: false,
        }
    }

    /// A beírt adatokból összerakott kamera, vagy a hiba oka.
    fn validated(&self) -> Result<Camera, &'static str> {
        let port: u16 = self
            .port_text
            .trim()
            .parse()
            .ok()
            .filter(|&p| p > 0)
            .ok_or(t("Érvénytelen port (1–65535)", "Invalid port (1–65535)"))?;
        let mut c = self.cam.clone();
        c.port = port;
        c.host = c.host.trim().to_string();
        c.path = c.path.trim().to_string();
        c.name = c.name.trim().to_string();
        c.group = c.group.trim().to_string();
        if c.host.is_empty() {
            return Err(t("Add meg az IP címet", "Enter the IP address"));
        }
        if c.name.is_empty() {
            c.name = format!("{} {}", t("Kamera", "Camera"), c.bare_host());
        }
        Ok(c)
    }

    /// Típusváltás: az alapértelmezett portot / útvonalat követjük, a kézzel beírtat megtartjuk.
    fn set_kind(&mut self, kind: StreamType) {
        let old = self.cam.kind;
        if old == kind {
            return;
        }
        let port = self.port_text.trim();
        if port.is_empty() || port == old.default_port().to_string() {
            self.port_text = kind.default_port().to_string();
        }
        if self.cam.path.trim().is_empty() || self.cam.path == old.default_path() {
            self.cam.path = kind.default_path().into();
        }
        self.cam.kind = kind;
    }

    fn apply_preset(&mut self, index: usize, p: &Preset) {
        self.preset = Some(index);
        self.set_kind(p.kind);
        self.cam.path = p.path.clone();
        if self.cam.username.is_empty() {
            self.cam.username = p.default_user.clone();
        }
    }

    fn start_test(&mut self, ctx: &egui::Context) {
        match self.validated() {
            Err(e) => self.error = Some(e.into()),
            Ok(cam) => {
                self.error = None;
                self.test = Some(Task::spawn(ctx, move || tester::test(&cam)));
            }
        }
    }

    // ---- felület ----

    fn form(
        &mut self,
        ui: &mut egui::Ui,
        presets: &[Preset],
        groups: &[String],
        last_login: Option<&(String, String)>,
        events: &mut Vec<Event>,
    ) {
        egui::Grid::new("editor_grid").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
            form_row(ui, t("Gyártó / típus", "Vendor / model"), |ui| {
                self.preset_picker(ui, presets, events)
            });
            form_row(ui, t("Név", "Name"), |ui| {
                text_field(ui, &mut self.cam.name, t("pl. Bejárat", "e.g. Front door"), 260.0)
            });
            form_row(ui, t("IP cím", "IP address"), |ui| {
                text_field(ui, &mut self.cam.host, t("pl. 192.168.1.50", "e.g. 192.168.1.50"), 260.0)
            });
            form_row(ui, t("Stream típusa", "Stream type"), |ui| {
                let mut kind = self.cam.kind;
                egui::ComboBox::from_id_salt("kind").width(260.0).selected_text(kind.label()).show_ui(
                    ui,
                    |ui| {
                        for k in StreamType::ALL {
                            ui.selectable_value(&mut kind, k, k.label());
                        }
                    },
                );
                self.set_kind(kind);
            });
            form_row(ui, t("Port", "Port"), |ui| text_field(ui, &mut self.port_text, "", 80.0));
            form_row(ui, t("Útvonal", "Path"), |ui| text_field(ui, &mut self.cam.path, "/stream1", 260.0));
            form_row(ui, t("Felhasználónév", "Username"), |ui| {
                text_field(ui, &mut self.cam.username, t("opcionális", "optional"), 170.0);
                if let Some((u, p)) = last_login
                    && self.cam.username.is_empty()
                    && ui
                        .small_button(t("legutóbbi", "last used"))
                        .on_hover_text(format!(
                            "{} ({u})",
                            t("Legutóbbi belépés használata", "Use last login")
                        ))
                        .clicked()
                {
                    self.cam.username = u.clone();
                    self.cam.password = p.clone();
                }
            });
            form_row(ui, t("Jelszó", "Password"), |ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.cam.password)
                        .password(!self.show_password)
                        .hint_text(t("opcionális", "optional"))
                        .desired_width(220.0),
                );
                if ui.small_button(if self.show_password { "🙈" } else { "👁" }).clicked() {
                    self.show_password = !self.show_password;
                }
            });
            form_row(ui, t("Csoport", "Group"), |ui| {
                text_field(ui, &mut self.cam.group, t("opcionális, pl. Ház", "optional, e.g. House"), 220.0);
                if !groups.is_empty() {
                    ui.menu_button("▾", |ui| {
                        if ui.button(t("Nincs csoport", "No group")).clicked() {
                            self.cam.group.clear();
                            ui.close();
                        }
                        for g in groups {
                            if ui.button(g.as_str()).clicked() {
                                self.cam.group = g.clone();
                                ui.close();
                            }
                        }
                    });
                }
            });
        });

        if let Ok(c) = self.validated() {
            ui.add_space(4.0);
            ui.label(RichText::new(c.display_url()).small().weak());
        }
        if self.cam.kind == StreamType::Hls {
            let note = t(
                "Megjegyzés: a HLS lejátszást az asztali app nem támogatja.",
                "Note: HLS playback is not supported by the desktop app.",
            );
            ui.label(RichText::new(note).small().color(WARNING));
        }
    }

    /// Sablonválasztó + a saját sablon törlése gomb.
    fn preset_picker(&mut self, ui: &mut egui::Ui, presets: &[Preset], events: &mut Vec<Event>) {
        let selected = self.preset.and_then(|i| presets.get(i));
        let text = selected
            .map(Preset::display)
            .unwrap_or_else(|| t("(kitöltés sablonból)", "(fill from preset)").into());
        egui::ComboBox::from_id_salt("preset").width(220.0).selected_text(text).show_ui(ui, |ui| {
            for (i, p) in presets.iter().enumerate() {
                let label = if p.builtin { p.display() } else { format!("★ {}", p.display()) };
                if ui.selectable_label(self.preset == Some(i), label).clicked() {
                    self.apply_preset(i, p);
                }
            }
            ui.separator();
            if ui.selectable_label(false, t("+ Új gyártó…", "+ New vendor…")).clicked() {
                self.new_preset = Some(String::new());
            }
        });
        if let Some(p) = selected.filter(|p| !p.builtin)
            && ui.small_button("🗑").on_hover_text(t("Sablon törlése", "Remove preset")).clicked()
        {
            events.push(Event::RemovePreset(p.label.clone()));
        }
    }

    /// Kapcsolatteszt gomb és az eredménye.
    fn test_row(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            let running = self.test.as_ref().is_some_and(|task| task.is_running());
            let button = egui::Button::new(format!("🔌 {}", t("Kapcsolat tesztelése", "Test connection")));
            if ui.add_enabled(!running, button).clicked() {
                self.start_test(ui.ctx());
            }
            let Some(task) = &self.test else { return };
            let (color, text) = match task.result().as_ref() {
                None => {
                    ui.spinner();
                    return;
                }
                Some(TestResult::Ok) => (SUCCESS, format!("✔ {}", t("Sikeres kapcsolat", "Connection OK"))),
                Some(TestResult::AuthFailed) => (
                    WARNING,
                    format!("🔒 {}", t("Hibás felhasználónév vagy jelszó", "Wrong username or password")),
                ),
                Some(TestResult::Unreachable) => (
                    ERROR,
                    format!(
                        "✖ {}",
                        t("Nem elérhető (IP, port, hálózat?)", "Unreachable (check IP, port, network)")
                    ),
                ),
                Some(TestResult::Other(m)) => (
                    WARNING,
                    format!(
                        "⚠ {} ({m})",
                        t(
                            "A kamera válaszol, de a teszt nem egyértelmű",
                            "Camera responds, but the result is unclear"
                        )
                    ),
                ),
            };
            ui.colored_label(color, text);
        });
    }

    /// Az „új gyártói sablon” ablak (a jelenlegi típus, útvonal és felhasználónév mentése).
    fn new_preset_window(&mut self, ctx: &egui::Context, events: &mut Vec<Event>) {
        let Some(name) = self.new_preset.as_mut() else {
            return;
        };
        let mut buttons = (false, false);
        egui::Window::new(t("Új gyártói sablon", "New vendor preset"))
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.label(t(
                    "A sablon a jelenlegi stream-típust, útvonalat és felhasználónevet menti el (jelszót nem).",
                    "The preset stores the current stream type, path and username (no password).",
                ));
                ui.add(egui::TextEdit::singleline(name).hint_text(t("Gyártó neve", "Vendor name")));
                buttons = modal_buttons(ui, t("Mentés", "Save"), t("Mégse", "Cancel"));
            });
        let (ok, cancel) = buttons;
        if ok && name.trim().is_empty() {
            self.error = Some(t("Add meg a gyártó nevét", "Enter the vendor name").into());
        } else if ok {
            let p = Preset::user(name.trim(), self.cam.kind, self.cam.path.trim(), self.cam.username.trim());
            events.push(Event::AddPreset(p));
            self.new_preset = None;
        } else if cancel {
            self.new_preset = None;
        }
    }
}

fn text_field(ui: &mut egui::Ui, value: &mut String, hint: &str, width: f32) {
    ui.add(egui::TextEdit::singleline(value).hint_text(hint).desired_width(width));
}

pub(super) fn editor_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some(ed) = app.editor.as_mut() else {
        return;
    };
    let presets: Vec<Preset> =
        model::builtin_presets().into_iter().chain(app.presets.iter().cloned()).collect();
    let groups = model::groups(&app.cameras);
    let title = if ed.is_new {
        t("Kamera hozzáadása", "Add camera")
    } else {
        t("Kamera szerkesztése", "Edit camera")
    };
    let mut events = Vec::new();
    let mut open = true;

    egui::Window::new(title)
        .id(egui::Id::unique("editor"))
        .open(&mut open)
        .collapsible(false)
        .resizable(false)
        .default_width(420.0)
        .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
        .show(ctx, |ui| {
            ed.form(ui, &presets, &groups, app.last_login.as_ref(), &mut events);
            ui.add_space(6.0);
            ed.test_row(ui);
            if let Some(e) = &ed.error {
                ui.colored_label(ERROR, e);
            }
            ui.separator();
            ui.horizontal(|ui| {
                if ui.button(format!("💾 {}", t("Mentés", "Save"))).clicked() {
                    events.push(Event::Save);
                }
                if ui.button(t("Mégse", "Cancel")).clicked() {
                    events.push(Event::Close);
                }
            });
        });
    ed.new_preset_window(ctx, &mut events);
    if !open {
        events.push(Event::Close);
    }

    for event in events {
        handle(app, event);
    }
}

fn handle(app: &mut IpCamApp, event: Event) {
    match event {
        Event::Close => app.editor = None,
        Event::RemovePreset(name) => app.confirm = Some(Confirm::RemovePreset(name)),
        Event::AddPreset(p) => {
            let label = p.label.clone();
            app.presets = model::merge_presets_by_name(&app.presets, &[p]);
            app.save_presets();
            let index = model::builtin_presets().len()
                + app.presets.iter().position(|x| x.label == label).unwrap_or(0);
            if let Some(ed) = app.editor.as_mut() {
                ed.preset = Some(index);
            }
        }
        Event::Save => {
            let Some(ed) = app.editor.as_mut() else {
                return;
            };
            match ed.validated() {
                Err(e) => ed.error = Some(e.into()),
                Ok(cam) => {
                    let (is_new, id) = (ed.is_new, cam.id.clone());
                    app.editor = None;
                    app.upsert_camera(cam);
                    if is_new {
                        app.view = View::Single(id);
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kind_change_follows_defaults_only() {
        let mut ed = Editor::new(Camera::new("", "1.2.3.4", 554, StreamType::Rtsp, "/stream1"), true);
        ed.set_kind(StreamType::Mjpeg);
        assert_eq!((ed.port_text.as_str(), ed.cam.path.as_str()), ("80", "/video.mjpg"));
        ed.port_text = "8080".into();
        ed.cam.path = "/sajat".into();
        ed.set_kind(StreamType::Snapshot);
        assert_eq!((ed.port_text.as_str(), ed.cam.path.as_str()), ("8080", "/sajat"));
    }

    #[test]
    fn validation() {
        let mut ed = Editor::new(Camera::new(" ", " 10.0.0.9 ", 554, StreamType::Rtsp, "/s"), true);
        assert_eq!(ed.validated().unwrap().name, "Kamera 10.0.0.9");
        ed.port_text = "0".into();
        assert!(ed.validated().is_err());
        ed.port_text = "554".into();
        ed.cam.host = "  ".into();
        assert!(ed.validated().is_err());
    }
}
