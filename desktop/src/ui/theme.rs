//! Megjelenés: betűkészletek, téma, kiemelőszín, nyelv.

use crate::i18n::{self, t};
use crate::storage::{NIGHT_LIGHT, NIGHT_SYSTEM, Settings};
use eframe::egui::{self, Color32};

/// A Windows „Segoe UI Symbol” betűkészlete tartalékként (szimbólumok: ⏶ ⏷ ⛶ ⊞ …).
pub fn install_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let windir = std::env::var("WINDIR").unwrap_or_else(|_| r"C:\Windows".into());
    let path = std::path::Path::new(&windir).join("Fonts").join("seguisym.ttf");
    if let Ok(bytes) = std::fs::read(path) {
        fonts.font_data.insert("segoe_symbol".into(), std::sync::Arc::new(egui::FontData::from_owned(bytes)));
        for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
            if let Some(list) = fonts.families.get_mut(&family) {
                list.push("segoe_symbol".into());
            }
        }
    }
    ctx.set_fonts(fonts);
}

/// Nyelv, téma és kiemelőszín alkalmazása a beállítások alapján.
pub fn apply(ctx: &egui::Context, settings: &Settings) {
    i18n::set_lang(i18n::resolve(&settings.language));
    ctx.set_theme(match settings.night_mode {
        NIGHT_SYSTEM => egui::ThemePreference::System,
        NIGHT_LIGHT => egui::ThemePreference::Light,
        _ => egui::ThemePreference::Dark,
    });
    for (theme, mut v) in
        [(egui::Theme::Dark, egui::Visuals::dark()), (egui::Theme::Light, egui::Visuals::light())]
    {
        if let Some(c) = accent_color(settings.accent) {
            v.selection.bg_fill = c;
            v.selection.stroke.color = on_accent(c);
            v.hyperlink_color = c;
            v.widgets.hovered.bg_stroke.color = c;
            v.text_cursor.stroke.color = c;
        }
        ctx.set_visuals_of(theme, v);
    }
    ctx.send_viewport_cmd(egui::ViewportCommand::Title(t("IP Kamerák", "IP Cameras").into()));
}

/// Android ARGB Int -> szín; 0 = nincs egyéni szín.
pub fn accent_color(argb: i32) -> Option<Color32> {
    if argb == 0 {
        return None;
    }
    let v = argb as u32;
    Some(Color32::from_rgb((v >> 16) as u8, (v >> 8) as u8, v as u8))
}

/// Szín -> Android ARGB Int (teljesen átlátszatlan).
pub fn to_argb(c: Color32) -> i32 {
    (0xFF00_0000u32 | (c.r() as u32) << 16 | (c.g() as u32) << 8 | c.b() as u32) as i32
}

/// Olvasható szövegszín a kiemelőszínen.
fn on_accent(c: Color32) -> Color32 {
    let lum = 0.2126 * c.r() as f32 + 0.7152 * c.g() as f32 + 0.0722 * c.b() as f32;
    if lum > 140.0 { Color32::from_rgb(0x11, 0x13, 0x18) } else { Color32::WHITE }
}

// ---- közös színek a hibákhoz / állapotokhoz ----

pub const ERROR: Color32 = Color32::from_rgb(240, 90, 90);
pub const WARNING: Color32 = Color32::from_rgb(230, 160, 60);
pub const SUCCESS: Color32 = Color32::from_rgb(80, 200, 120);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argb_roundtrip() {
        let c = Color32::from_rgb(0xE9, 0x1E, 0x63);
        assert_eq!(accent_color(to_argb(c)), Some(c));
        assert_eq!(accent_color(0), None);
        assert_eq!(to_argb(c), 0xFFE91E63u32 as i32); // Android Color.parseColor("#E91E63")
    }
}
