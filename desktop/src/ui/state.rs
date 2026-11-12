//! A felület állapottípusai: csoportszűrő, nézet, nagyítás, felhasználói műveletek.

use crate::model::Camera;
use eframe::egui::{Pos2, Rect, Vec2};

/// A kameralista szűrése csoport szerint.
#[derive(Clone, Debug, PartialEq)]
pub enum GroupFilter {
    All,
    NoGroup,
    Named(String),
}

impl GroupFilter {
    pub fn matches(&self, cam: &Camera) -> bool {
        match self {
            GroupFilter::All => true,
            GroupFilter::NoGroup => cam.group.is_empty(),
            GroupFilter::Named(g) => &cam.group == g,
        }
    }
}

/// A középső terület tartalma.
#[derive(Clone, Debug, PartialEq)]
pub enum View {
    Home,
    /// Egy kamera (id) nagyítható képe.
    Single(String),
    /// Osztott (rács) nézet.
    Grid,
}

impl View {
    /// A beállításokban tárolt alak: "" | "grid" | "cam:<id>".
    pub fn to_setting(&self) -> String {
        match self {
            View::Home => String::new(),
            View::Grid => "grid".into(),
            View::Single(id) => format!("cam:{id}"),
        }
    }

    /// Visszaállítás a tárolt alakból (törölt kamera esetén a kezdőlap).
    pub fn restore(setting: &str, cameras: &[Camera]) -> View {
        if setting == "grid" {
            return View::Grid;
        }
        match setting.strip_prefix("cam:") {
            Some(id) if cameras.iter().any(|c| c.id == id) => View::Single(id.to_string()),
            _ => View::Home,
        }
    }
}

pub const MAX_ZOOM: f32 = 8.0;

/// Nagyítás: `scale` 1–8×, `center` a látható rész közepe a kép (0..1) koordinátáiban.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Zoom {
    pub scale: f32,
    pub center: Pos2,
}

impl Default for Zoom {
    fn default() -> Self {
        Zoom { scale: 1.0, center: Pos2::new(0.5, 0.5) }
    }
}

impl Zoom {
    /// Új nagyítás úgy, hogy a mutató alatti képpont a helyén maradjon.
    pub fn zoom_to(&mut self, scale: f32, pointer: Option<Pos2>, img_rect: Rect) {
        let scale = scale.clamp(1.0, MAX_ZOOM);
        if let Some(p) = pointer.filter(|p| img_rect.contains(*p)) {
            let rel = (p - img_rect.min) / img_rect.size() - Vec2::splat(0.5);
            let at = self.center + rel / self.scale;
            self.center = at - rel / scale;
        }
        self.scale = scale;
        self.clamp();
    }

    /// Mozgatás képernyő-képpontokban (húzás).
    pub fn pan(&mut self, delta: Vec2, img_rect: Rect) {
        self.center -= delta / img_rect.size() / self.scale;
        self.clamp();
    }

    /// A kép látható része textúra-koordinátákban.
    pub fn uv(&self) -> Rect {
        Rect::from_center_size(self.center, Vec2::splat(1.0 / self.scale))
    }

    fn clamp(&mut self) {
        let half = 0.5 / self.scale;
        self.center.x = self.center.x.clamp(half, 1.0 - half);
        self.center.y = self.center.y.clamp(half, 1.0 - half);
    }
}

/// A listában / menükben kiváltott műveletek (a bejárás után hajtjuk végre őket).
pub enum Action {
    Open(String),
    Edit(String),
    Delete(String),
    Share(String),
    MoveUp(String),
    MoveDown(String),
    SetGroup(String, String),
    NewGroupFor(String),
    RenameGroup(String),
    DissolveGroup(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn view_roundtrip() {
        let cams = vec![Camera::new("a", "h", 1, crate::model::StreamType::Rtsp, "/")];
        for v in [View::Home, View::Grid, View::Single(cams[0].id.clone())] {
            assert_eq!(View::restore(&v.to_setting(), &cams), v);
        }
        assert_eq!(View::restore("cam:torolt", &cams), View::Home);
    }

    #[test]
    fn zoom_keeps_point_under_cursor() {
        let img = Rect::from_min_size(Pos2::ZERO, Vec2::new(200.0, 100.0));
        let mut z = Zoom::default();
        let p = Pos2::new(150.0, 25.0); // a kép (0.75, 0.25) pontja
        z.zoom_to(2.0, Some(p), img);
        // a mutató alatt továbbra is ugyanaz a képpont
        let uv = z.uv();
        let under = uv.min + (p - img.min) / img.size() * uv.size();
        assert!((under - Pos2::new(0.75, 0.25)).length() < 1e-5);
        z.zoom_to(1.0, None, img);
        assert_eq!(z, Zoom::default());
    }
}
