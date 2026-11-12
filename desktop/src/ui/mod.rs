//! A főablak: felső eszköztár, bal oldali kameralista, középen egy kamera vagy az
//! osztott nézet, fölötte a párbeszédablakok.
//!
//! Az [IpCamApp] metódusai témák szerint több fájlban vannak:
//! [toolbar], [camera_list], [views]; a párbeszédablakok a [dialogs] modulban.

mod camera_list;
mod dialogs;
mod qr;
mod state;
mod theme;
mod toolbar;
mod views;

use crate::i18n::t;
use crate::model::{Camera, Preset};
use crate::storage::{Settings, Store, backup};
use crate::stream::{self, Player};
use dialogs::{Confirm, Discovery, Editor};
use eframe::egui::{self, Color32, Key, Vec2};
use state::{Action, GroupFilter, View, Zoom};
use std::collections::HashMap;
use std::time::{Duration, Instant};

pub struct IpCamApp {
    // ---- adatok ----
    store: Store,
    cameras: Vec<Camera>,
    /// A felhasználó saját sablonjai (a beépítettek: [crate::model::builtin_presets]).
    presets: Vec<Preset>,
    settings: Settings,
    /// A legutóbb mentett belépés (a szerkesztő felajánlja).
    last_login: Option<(String, String)>,

    // ---- nézet ----
    filter: GroupFilter,
    view: View,
    /// Az osztott nézetből nyitották-e meg az egykamerás nézetet (vissza gomb).
    from_grid: bool,
    zoom: Zoom,
    fullscreen: bool,
    players: HashMap<String, Player>,

    // ---- párbeszédablakok ----
    editor: Option<Editor>,
    confirm: Option<Confirm>,
    discovery: Option<Discovery>,
    settings_open: bool,
    /// (kamera neve, QR-kép)
    qr_show: Option<(String, egui::TextureHandle)>,
    /// (régi név, új név) – csoport átnevezése
    group_rename: Option<(String, String)>,
    /// (kamera id, név) – áthelyezés új csoportba
    new_group_for: Option<(String, String)>,
    /// Hány kamera jelszava nem fejthető vissza (figyelmeztetés induláskor).
    locked_notice: usize,
    toast: Option<(String, Instant)>,
}

impl IpCamApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let mut store = Store::open();
        let cameras = store.load_cameras();
        let presets = store.load_presets();
        let settings = store.load_settings();
        let view = View::restore(&settings.last_view, &cameras);
        let app = IpCamApp {
            locked_notice: store.locked_count(),
            store,
            cameras,
            presets,
            settings,
            last_login: None,
            filter: GroupFilter::All,
            view,
            from_grid: false,
            zoom: Zoom::default(),
            fullscreen: false,
            players: HashMap::new(),
            editor: None,
            confirm: None,
            discovery: None,
            settings_open: false,
            qr_show: None,
            group_rename: None,
            new_group_for: None,
            toast: None,
        };
        theme::install_fonts(&cc.egui_ctx);
        theme::apply(&cc.egui_ctx, &app.settings);
        app
    }

    // ---- mentés ----

    fn save_settings(&mut self, ctx: &egui::Context) {
        if let Err(e) = self.store.save_settings(&self.settings) {
            self.toast_err(e);
        }
        theme::apply(ctx, &self.settings);
    }

    fn save_cameras(&mut self) {
        if let Err(e) = self.store.save_cameras(&self.cameras) {
            self.toast_err(e);
        }
        // a megszűnt csoport / kamera ne maradjon kiválasztva
        if let GroupFilter::Named(g) = &self.filter
            && !self.cameras.iter().any(|c| &c.group == g)
        {
            self.filter = GroupFilter::All;
        }
        if let View::Single(id) = &self.view
            && !self.cameras.iter().any(|c| &c.id == id)
        {
            self.view = View::Home;
        }
    }

    fn save_presets(&mut self) {
        if let Err(e) = self.store.save_presets(&self.presets) {
            self.toast_err(e);
        }
    }

    /// Az aktuális nézet megjegyzése (induláskor visszaáll); csak változáskor ír.
    fn remember_view(&mut self) {
        let v = self.view.to_setting();
        if v != self.settings.last_view {
            self.settings.last_view = v;
            let _ = self.store.save_settings(&self.settings);
        }
    }

    // ---- kamerák ----

    fn camera(&self, id: &str) -> Option<&Camera> {
        self.cameras.iter().find(|c| c.id == id)
    }

    /// Kamera felvétele vagy frissítése (id szerint).
    fn upsert_camera(&mut self, cam: Camera) {
        if !cam.username.is_empty() {
            self.last_login = Some((cam.username.clone(), cam.password.clone()));
        }
        match self.cameras.iter().position(|c| c.id == cam.id) {
            Some(i) => self.cameras[i] = cam,
            None => self.cameras.push(cam),
        }
        self.save_cameras();
    }

    /// Csere a szűrt nézetben szomszédos kamerával (a többi a helyén marad).
    fn move_camera(&mut self, id: &str, up: bool) {
        let visible: Vec<usize> =
            (0..self.cameras.len()).filter(|&i| self.filter.matches(&self.cameras[i])).collect();
        let Some(pos) = visible.iter().position(|&i| self.cameras[i].id == id) else {
            return;
        };
        let other = if up { pos.checked_sub(1) } else { Some(pos + 1).filter(|&p| p < visible.len()) };
        if let Some(o) = other {
            self.cameras.swap(visible[pos], visible[o]);
            self.save_cameras();
        }
    }

    // ---- csoportok ----

    fn set_group(&mut self, id: &str, group: String) {
        if let Some(c) = self.cameras.iter_mut().find(|c| c.id == id) {
            c.group = group;
        }
        self.save_cameras();
    }

    /// Átnevezés; ha a csoport volt kiválasztva szűrőnek, a szűrő is követi.
    fn rename_group(&mut self, old: &str, new: &str) {
        for c in self.cameras.iter_mut().filter(|c| c.group == old) {
            c.group = new.to_string();
        }
        if self.filter == GroupFilter::Named(old.to_string()) {
            self.filter = GroupFilter::Named(new.to_string());
        }
        self.save_cameras();
    }

    /// A csoport megszüntetése: a kamerák megmaradnak, csak csoport nélküliek lesznek.
    fn dissolve_group(&mut self, group: &str) {
        self.rename_group(group, "");
    }

    fn share_camera(&mut self, ctx: &egui::Context, id: &str) {
        let Some(cam) = self.camera(id) else { return };
        match qr::encode(&backup::qr_build(cam), 6) {
            Some(img) => {
                let tex = ctx.load_texture("qr", img, egui::TextureOptions::NEAREST);
                self.qr_show = Some((cam.name.clone(), tex));
            }
            None => self.toast(t(
                "A QR-kód nem készíthető el (túl hosszú adat)",
                "Could not create QR code (data too long)",
            )),
        }
    }

    /// A listában / menükben kiváltott művelet végrehajtása.
    fn apply(&mut self, ctx: &egui::Context, action: Action) {
        match action {
            Action::Open(id) => self.open_single(id),
            Action::Edit(id) => {
                if let Some(c) = self.camera(&id) {
                    self.editor = Some(Editor::new(c.clone(), false));
                }
            }
            Action::Delete(id) => {
                if let Some(c) = self.camera(&id) {
                    self.confirm = Some(Confirm::DeleteCamera(id.clone(), c.name.clone()));
                }
            }
            Action::Share(id) => self.share_camera(ctx, &id),
            Action::MoveUp(id) => self.move_camera(&id, true),
            Action::MoveDown(id) => self.move_camera(&id, false),
            Action::SetGroup(id, group) => self.set_group(&id, group),
            Action::NewGroupFor(id) => self.new_group_for = Some((id, String::new())),
            Action::RenameGroup(g) => self.group_rename = Some((g.clone(), g)),
            Action::DissolveGroup(g) => self.confirm = Some(Confirm::DissolveGroup(g)),
        }
    }

    // ---- nézet ----

    fn open_single(&mut self, id: String) {
        self.from_grid = self.view == View::Grid;
        self.view = View::Single(id);
        self.zoom = Zoom::default();
    }

    fn set_fullscreen(&mut self, ctx: &egui::Context, on: bool) {
        self.fullscreen = on;
        ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(on));
    }

    /// F11: teljes képernyő be/ki, Esc: kilépés belőle.
    fn handle_keys(&mut self, ctx: &egui::Context) {
        if ctx.input(|i| i.key_pressed(Key::F11)) && self.view != View::Home {
            self.set_fullscreen(ctx, !self.fullscreen);
        }
        if self.fullscreen && (self.view == View::Home || ctx.input(|i| i.key_pressed(Key::Escape))) {
            self.set_fullscreen(ctx, false);
        }
    }

    /// A most látható kamerák lejátszóinak indítása; a többi (és a megváltozottak) leáll.
    fn sync_players(&mut self, ctx: &egui::Context) {
        let needed: Vec<&Camera> = match &self.view {
            View::Home => vec![],
            View::Single(id) => self.cameras.iter().filter(|c| &c.id == id).collect(),
            View::Grid => self.cameras.iter().filter(|c| self.filter.matches(c)).collect(),
        };
        self.players
            .retain(|id, p| needed.iter().any(|c| &c.id == id && stream::signature(c) == p.signature));
        for cam in needed {
            if !self.players.contains_key(&cam.id) {
                self.players.insert(cam.id.clone(), Player::start(cam, ctx));
            }
        }
    }

    // ---- értesítés ----

    fn toast(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), Instant::now()));
    }

    fn toast_err(&mut self, e: impl std::fmt::Display) {
        self.toast(format!("{}: {e}", t("A művelet nem sikerült", "Operation failed")));
    }

    /// Az értesítés alul középen, 4 másodpercig.
    fn show_toast(&mut self, ctx: &egui::Context) {
        let Some((msg, at)) = &self.toast else { return };
        if at.elapsed() > Duration::from_secs(4) {
            self.toast = None;
            return;
        }
        egui::Area::new(egui::Id::unique("toast"))
            .anchor(egui::Align2::CENTER_BOTTOM, Vec2::new(0.0, -24.0))
            .order(egui::Order::Tooltip)
            .show(ctx, |ui| {
                egui::Frame::popup(ui.style()).show(ui, |ui| ui.label(msg.as_str()));
            });
        ctx.request_repaint_after(Duration::from_millis(500));
    }
}

impl eframe::App for IpCamApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.handle_keys(&ctx);
        self.remember_view();
        self.sync_players(&ctx);

        let mut actions = Vec::new();
        if !self.fullscreen {
            egui::Panel::top("toolbar").show(ui, |ui| {
                ui.add_space(4.0);
                self.toolbar(ui);
                ui.add_space(2.0);
            });
            egui::Panel::left("cameras").resizable(true).default_size(260.0).show(ui, |ui| {
                ui.add_space(6.0);
                self.camera_list(ui, &mut actions);
            });
        }
        let frame = if self.fullscreen {
            egui::Frame::NONE.fill(Color32::BLACK)
        } else {
            egui::Frame::central_panel(&ctx.global_style()).inner_margin(6.0)
        };
        egui::CentralPanel::default_margins().frame(frame).show(ui, |ui| self.central(ui, &mut actions));

        for a in actions {
            self.apply(&ctx, a);
        }
        dialogs::show_all(self, &ctx);
        self.show_toast(&ctx);
    }
}
