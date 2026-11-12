//! Bal oldali kameralista: csoportszűrő-csipek, kamerák, helyi menü.

use super::{Action, GroupFilter, IpCamApp, View};
use crate::i18n::t;
use crate::model::{self, Camera};
use eframe::egui::{self, RichText, Vec2};

impl IpCamApp {
    pub(super) fn camera_list(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let groups = model::groups(&self.cameras);
        if !groups.is_empty() {
            self.group_filter_bar(ui, &groups, actions);
            ui.separator();
        }
        if self.cameras.is_empty() {
            ui.add_space(8.0);
            ui.label(t(
                "Még nincs kamera.\nKattints a ➕ gombra egy új kamera hozzáadásához, \
                 vagy a 🔍 gombra a hálózaton lévő kamerák kereséséhez.",
                "No cameras yet.\nClick ➕ to add a camera, or 🔍 to find cameras on your network.",
            ));
            return;
        }
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            let visible: Vec<&Camera> = self.cameras.iter().filter(|c| self.filter.matches(c)).collect();
            let last = visible.len().saturating_sub(1);
            for (i, cam) in visible.into_iter().enumerate() {
                self.camera_row(ui, cam, i > 0, i < last, &groups, actions);
            }
        });
    }

    /// Csoportszűrő-csipek; jobb klikk: átnevezés / megszüntetés.
    fn group_filter_bar(&mut self, ui: &mut egui::Ui, groups: &[String], actions: &mut Vec<Action>) {
        ui.horizontal_wrapped(|ui| {
            ui.selectable_value(&mut self.filter, GroupFilter::All, t("Összes", "All"));
            for g in groups {
                ui.selectable_value(&mut self.filter, GroupFilter::Named(g.clone()), g.as_str())
                    .context_menu(|ui| {
                        if ui.button(t("Csoport átnevezése", "Rename group")).clicked() {
                            actions.push(Action::RenameGroup(g.clone()));
                            ui.close();
                        }
                        if ui.button(t("Csoport megszüntetése", "Dissolve group")).clicked() {
                            actions.push(Action::DissolveGroup(g.clone()));
                            ui.close();
                        }
                    });
            }
            if self.cameras.iter().any(|c| c.group.is_empty()) {
                ui.selectable_value(&mut self.filter, GroupFilter::NoGroup, t("Csoport nélkül", "Ungrouped"));
            }
        });
    }

    /// Egy kamera sora: név + típus, sorrend-gombok, alatta az URL.
    /// Kattintás: megnyitás, dupla kattintás: szerkesztés, jobb klikk: menü.
    fn camera_row(
        &self,
        ui: &mut egui::Ui,
        cam: &Camera,
        can_move_up: bool,
        can_move_down: bool,
        groups: &[String],
        actions: &mut Vec<Action>,
    ) {
        let selected = matches!(&self.view, View::Single(id) if id == &cam.id);
        ui.horizontal(|ui| {
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let down = egui::Button::new("⏷").small();
                if ui.add_enabled(can_move_down, down).on_hover_text(t("Lejjebb", "Move down")).clicked() {
                    actions.push(Action::MoveDown(cam.id.clone()));
                }
                let up = egui::Button::new("⏶").small();
                if ui.add_enabled(can_move_up, up).on_hover_text(t("Feljebb", "Move up")).clicked() {
                    actions.push(Action::MoveUp(cam.id.clone()));
                }

                // a kijelölt sor kiemelőszínén a típus felirat ne legyen halvány
                let kind = RichText::new(cam.kind.label()).small();
                let kind = if selected { kind } else { kind.weak() };
                let name = if cam.name.is_empty() { cam.bare_host() } else { &cam.name };
                let button = egui::Button::selectable(selected, RichText::new(name).strong())
                    .right_text(kind)
                    .min_size(Vec2::new(ui.available_width(), 0.0));
                let r = ui.add(button).on_hover_text(cam.display_url());
                if r.clicked() {
                    actions.push(Action::Open(cam.id.clone()));
                }
                if r.double_clicked() {
                    actions.push(Action::Edit(cam.id.clone()));
                }
                r.context_menu(|ui| self.camera_menu(ui, cam, groups, actions));
            });
        });
        ui.label(RichText::new(cam.display_url()).small().weak());
        ui.add_space(4.0);
    }

    /// Egy kamera helyi menüje (a listában és az osztott nézet csempéin is).
    pub(super) fn camera_menu(
        &self,
        ui: &mut egui::Ui,
        cam: &Camera,
        groups: &[String],
        actions: &mut Vec<Action>,
    ) {
        let id = || cam.id.clone();
        let mut item = |ui: &mut egui::Ui, label: String, action: Action| {
            if ui.button(label).clicked() {
                actions.push(action);
                ui.close();
            }
        };
        item(ui, format!("✏ {}", t("Szerkesztés", "Edit")), Action::Edit(id()));
        item(ui, format!("🔗 {}", t("Megosztás (QR)", "Share (QR)")), Action::Share(id()));
        ui.menu_button(format!("📁 {}", t("Áthelyezés csoportba", "Move to group")), |ui| {
            let choices = std::iter::once((String::new(), t("Nincs csoport", "No group").to_string()))
                .chain(groups.iter().map(|g| (g.clone(), g.clone())));
            for (group, label) in choices {
                if ui.selectable_label(cam.group == group, label).clicked() {
                    actions.push(Action::SetGroup(id(), group));
                    ui.close();
                }
            }
            if ui.button(t("+ Új csoport…", "+ New group…")).clicked() {
                actions.push(Action::NewGroupFor(id()));
                ui.close();
            }
        });
        ui.separator();
        if ui.button(format!("🗑 {}", t("Törlés", "Delete"))).clicked() {
            actions.push(Action::Delete(id()));
            ui.close();
        }
    }
}
