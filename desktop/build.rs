//! Windows-on az ikon és a verzióadatok beágyazása az exe-be.
//! Az ikon forrása: assets/icon.ico (újragenerálás: python assets/make_icon.py).
//!
//! A statikusan linkelt FFmpeg (H.265 dekóder) Windows-rendszerkönyvtárai is itt vannak:
//! a vcpkg-s libavcodec Media Foundation- és COM-függőségeit az ffmpeg-sys-next nem linkeli.

fn main() {
    println!("cargo:rerun-if-changed=assets/icon.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/icon.ico")
            .set("ProductName", "IP Kamerák")
            .set("FileDescription", "IP Kamerák – asztali kameranéző");
        if let Err(e) = res.compile() {
            panic!("Az ikon beágyazása nem sikerült: {e}");
        }
        if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
            for lib in ["mfplat", "mfuuid", "strmiids", "ole32", "user32", "bcrypt"] {
                println!("cargo:rustc-link-lib={lib}");
            }
        }
    }
}
