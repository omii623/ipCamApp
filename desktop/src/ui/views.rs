//! Középső terület: kezdőlap, egy kamera nagyítható képe, osztott (rács) nézet.

use super::{Action, IpCamApp, View, Zoom};
use crate::i18n::t;
use crate::model::{self, Camera};
use crate::stream::Status;
use eframe::egui::{self, Color32, Rect, RichText, Sense, Vec2};
use std::time::Duration;

impl IpCamApp {
    pub(super) fn central(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        match self.view.clone() {
            View::Home => {
                let hint = if self.cameras.is_empty() {
                    t(
                        "Adj hozzá egy kamerát a ➕ gombbal, vagy keress a hálózaton a 🔍 gombbal.",
                        "Add a camera with ➕ or search your network with 🔍.",
                    )
                } else {
                    t(
                        "Válassz egy kamerát a listából, vagy nyisd meg az osztott nézetet.",
                        "Pick a camera from the list or open the split view.",
                    )
                };
                centered_hint(ui, hint);
            }
            View::Single(id) => self.single_view(ui, &id, actions),
            View::Grid => self.grid_view(ui, actions),
        }
    }

    // ---- egy kamera ----

    fn single_view(&mut self, ui: &mut egui::Ui, id: &str, actions: &mut Vec<Action>) {
        let Some(cam) = self.camera(id).cloned() else {
            return;
        };
        if !self.fullscreen {
            self.single_header(ui, &cam, actions);
        }
        let (rect, resp) = ui.allocate_exact_size(ui.available_size(), Sense::click_and_drag());
        ui.painter().rect_filled(rect, 0.0, Color32::BLACK);
        let Some(player) = self.players.get_mut(&cam.id) else {
            return;
        };
        let status = player.status();

        let ctx = ui.ctx().clone();
        if let Some(tex) = player.texture(&ctx, &cam.id) {
            let img_rect = fit(rect, tex.size_vec2());
            handle_zoom_input(ui, &resp, &mut self.zoom, img_rect);
            ui.painter().image(tex.id(), img_rect, self.zoom.uv(), Color32::WHITE);
        }
        status_overlay(ui, rect, &status, true);
        if self.fullscreen {
            ui.painter().text(
                rect.left_top() + Vec2::new(12.0, 10.0),
                egui::Align2::LEFT_TOP,
                &cam.name,
                egui::FontId::proportional(16.0),
                Color32::from_white_alpha(200),
            );
        }
    }

    /// Fejléc: vissza az osztott nézetre, név, URL, nagyítás visszaállítása, szerkesztés.
    fn single_header(&mut self, ui: &mut egui::Ui, cam: &Camera, actions: &mut Vec<Action>) {
        ui.horizontal(|ui| {
            if self.from_grid && ui.button(format!("⬅ {}", t("Osztott nézet", "Split view"))).clicked() {
                self.view = View::Grid;
            }
            ui.strong(&cam.name);
            ui.label(RichText::new(cam.display_url()).weak());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button(format!("✏ {}", t("Szerkesztés", "Edit"))).clicked() {
                    actions.push(Action::Edit(cam.id.clone()));
                }
                if self.zoom.scale > 1.0 && ui.button(format!("1:1 ({:.1}×)", self.zoom.scale)).clicked() {
                    self.zoom = Zoom::default();
                }
            });
        });
    }

    // ---- osztott nézet ----

    fn grid_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let cams: Vec<Camera> = self.cameras.iter().filter(|c| self.filter.matches(c)).cloned().collect();
        if cams.is_empty() {
            centered_hint(ui, t("Nincs megjeleníthető kamera.", "No cameras to show."));
            return;
        }
        let area = ui.available_rect_before_wrap();
        let groups = model::groups(&self.cameras);
        for (cam, rect) in cams.iter().zip(grid_cells(area, cams.len(), self.settings.columns)) {
            let resp = ui.interact(rect, egui::Id::unique(("tile", &cam.id)), Sense::click());
            self.draw_tile(ui, cam, rect, resp.hovered());
            if resp.clicked() {
                actions.push(Action::Open(cam.id.clone()));
            }
            resp.context_menu(|ui| self.camera_menu(ui, cam, &groups, actions));
        }
        ui.advance_cursor_after_rect(area);
    }

    /// Egy csempe: kép, név felirat, állapot, kiemelés egér alatt.
    fn draw_tile(&mut self, ui: &egui::Ui, cam: &Camera, rect: Rect, hovered: bool) {
        let painter = ui.painter_at(rect);
        painter.rect_filled(rect, 4.0, Color32::BLACK);
        let status = match self.players.get_mut(&cam.id) {
            Some(p) => {
                if let Some(tex) = p.texture(ui.ctx(), &cam.id) {
                    let full = Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1.0, 1.0));
                    painter.image(tex.id(), fit(rect, tex.size_vec2()), full, Color32::WHITE);
                }
                p.status()
            }
            None => Status::Connecting,
        };

        let galley =
            painter.layout_no_wrap(cam.name.clone(), egui::FontId::proportional(13.0), Color32::WHITE);
        let label = Rect::from_min_size(rect.min + Vec2::new(6.0, 6.0), galley.size() + Vec2::new(8.0, 4.0));
        painter.rect_filled(label, 3.0, Color32::from_black_alpha(150));
        painter.galley(label.min + Vec2::new(4.0, 2.0), galley, Color32::WHITE);

        status_overlay(ui, rect, &status, rect.width() > 220.0);
        if hovered {
            let stroke = egui::Stroke::new(2.0, ui.visuals().selection.bg_fill);
            painter.rect_stroke(rect, 4.0, stroke, egui::StrokeKind::Inside);
        }
    }
}

// ---- segédfüggvények ----

fn centered_hint(ui: &mut egui::Ui, text: &str) {
    ui.centered_and_justified(|ui| ui.label(RichText::new(text).weak()));
}

/// A kép téglalapja a területen belül, méretarány-tartóan középre igazítva.
fn fit(area: Rect, image: Vec2) -> Rect {
    let scale = (area.width() / image.x).min(area.height() / image.y);
    Rect::from_center_size(area.center(), image * scale)
}

/// A rács celláinak téglalapjai (`columns` = 0: automatikus, kb. négyzetes elrendezés).
fn grid_cells(area: Rect, n: usize, columns: u32) -> Vec<Rect> {
    let cols = match columns {
        0 => (n as f32).sqrt().ceil() as usize,
        c => c as usize,
    }
    .clamp(1, n.max(1));
    let rows = n.div_ceil(cols);
    let gap = 4.0;
    let cell = Vec2::new(
        (area.width() - gap * (cols - 1) as f32) / cols as f32,
        (area.height() - gap * (rows - 1) as f32) / rows as f32,
    );
    (0..n)
        .map(|i| {
            let offset = Vec2::new((i % cols) as f32 * (cell.x + gap), (i / cols) as f32 * (cell.y + gap));
            Rect::from_min_size(area.min + offset, cell)
        })
        .collect()
}

/// Görgetés: nagyítás a mutató körül; dupla kattintás: 1× / 2,5×; húzás: mozgatás.
fn handle_zoom_input(ui: &egui::Ui, resp: &egui::Response, zoom: &mut Zoom, img_rect: Rect) {
    let scroll = if resp.hovered() { ui.input(|i| i.smooth_scroll_delta.y) } else { 0.0 };
    if scroll != 0.0 {
        zoom.zoom_to(zoom.scale * (scroll / 200.0).exp(), resp.hover_pos(), img_rect);
    }
    if resp.double_clicked() {
        let target = if zoom.scale > 1.0 { 1.0 } else { 2.5 };
        zoom.zoom_to(target, resp.interact_pointer_pos(), img_rect);
    }
    if zoom.scale > 1.0 {
        if resp.dragged() {
            zoom.pan(resp.drag_delta(), img_rect);
        }
        let icon = if resp.dragged() { egui::CursorIcon::Grabbing } else { egui::CursorIcon::Grab };
        ui.ctx().set_cursor_icon(icon);
    }
}

/// Állapotjelzés a kép fölött: „Csatlakozás…” vagy a hiba oka.
fn status_overlay(ui: &egui::Ui, rect: Rect, status: &Status, detailed: bool) {
    let painter = ui.painter_at(rect);
    match status {
        Status::Playing => {}
        Status::Connecting => {
            painter.text(
                rect.center(),
                egui::Align2::CENTER_CENTER,
                t("Csatlakozás…", "Connecting…"),
                egui::FontId::proportional(15.0),
                Color32::from_gray(200),
            );
            ui.ctx().request_repaint_after(Duration::from_millis(250));
        }
        Status::Error(msg) => {
            let text = if detailed {
                format!("⚠ {}\n{msg}", t("Nem sikerült csatlakozni", "Could not connect"))
            } else {
                format!("⚠ {}", t("Nem elérhető", "Unavailable"))
            };
            let color = Color32::from_rgb(255, 170, 120);
            let galley = painter.layout(text, egui::FontId::proportional(14.0), color, rect.width() - 24.0);
            let pos = rect.center() - galley.size() / 2.0;
            let bg = Rect::from_min_size(pos, galley.size()).expand(8.0);
            painter.rect_filled(bg, 6.0, Color32::from_black_alpha(170));
            painter.galley(pos, galley, Color32::WHITE);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use eframe::egui::pos2;

    #[test]
    fn grid_layout() {
        let area = Rect::from_min_size(pos2(0.0, 0.0), Vec2::new(404.0, 204.0));
        let cells = grid_cells(area, 4, 0); // automatikus: 2×2
        assert_eq!(cells.len(), 4);
        assert_eq!(cells[0], Rect::from_min_size(pos2(0.0, 0.0), Vec2::new(200.0, 100.0)));
        assert_eq!(cells[3].min, pos2(204.0, 104.0));
        assert_eq!(grid_cells(area, 3, 1).len(), 3); // 1 oszlop, 3 sor
    }

    #[test]
    fn fit_keeps_aspect() {
        let r = fit(Rect::from_min_size(pos2(0.0, 0.0), Vec2::new(200.0, 200.0)), Vec2::new(160.0, 90.0));
        assert!((r.width() / r.height() - 16.0 / 9.0).abs() < 1e-4);
        assert_eq!(r.center(), pos2(100.0, 100.0));
    }
}
