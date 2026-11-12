//! Windows-on az ikon és a verzióadatok beágyazása az exe-be.
//! Az ikon forrása: assets/icon.ico (újragenerálás: python assets/make_icon.py).

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
    }
}
