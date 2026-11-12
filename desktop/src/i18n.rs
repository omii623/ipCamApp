//! Egyszerű kétnyelvű (magyar/angol) szövegkezelés.
//! A szövegek a hívás helyén állnak párban: `t("Mentés", "Save")`.

use std::sync::atomic::{AtomicU8, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lang {
    Hu,
    En,
}

static CURRENT: AtomicU8 = AtomicU8::new(0);

pub fn set_lang(lang: Lang) {
    CURRENT.store(if lang == Lang::En { 1 } else { 0 }, Ordering::Relaxed);
}

pub fn lang() -> Lang {
    if CURRENT.load(Ordering::Relaxed) == 1 { Lang::En } else { Lang::Hu }
}

/// A beállított nyelvkód ("" = rendszer, "hu", "en") feloldása tényleges nyelvre.
pub fn resolve(code: &str) -> Lang {
    let code = if code.is_empty() { sys_locale::get_locale().unwrap_or_default() } else { code.to_string() };
    if code.to_ascii_lowercase().starts_with("hu") || code.is_empty() { Lang::Hu } else { Lang::En }
}

/// A szöveg az aktuális nyelven.
pub fn t(hu: &'static str, en: &'static str) -> &'static str {
    match lang() {
        Lang::Hu => hu,
        Lang::En => en,
    }
}
