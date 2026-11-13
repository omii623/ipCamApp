# IP Kamerák – asztali (ipCamDesktop)

Windows asztali alkalmazás IP-kamerák megtekintésére a helyi hálózaton, Rustban
(egui/eframe). Az Android **ipCamApp** ([`../app/`](../app/README.md)) asztali párja:
ugyanazok a funkciók, és a mentésfájlok / QR-kódok a két alkalmazás között átvihetők.

> Magyar és angol felület, világos/sötét téma, választható kiemelőszín.

## Funkciók

- **Stream-típusok:** RTSP (**H.264**), HTTP **MJPEG** és HTTP **JPEG** (képfrissítés) –
  tisztán Rustban, külső program (ffmpeg, VLC) nélkül.
  - RTSP: [retina](https://crates.io/crates/retina) kliens + [openh264](https://crates.io/crates/openh264) dekóder.
  - Hitelesítés: Basic és **Digest** (MD5 / SHA-256), RTSP-n és HTTP-n is.
- **Kamera nézet:** egérgörgős nagyítás (1–8×) a kurzor körül, húzással mozgatás,
  dupla kattintásos zoom, **teljes képernyő** (F11, kilépés: Esc), automatikus újracsatlakozás.
- **Osztott (rács) nézet:** az összes (vagy a szűrt csoport) kamerája egyszerre,
  automatikus vagy 1–6 oszloppal; kattintásra nagy nézet.
- **Hálózati keresés:** **ONVIF** (WS-Discovery) a pontos stream-cím lekérdezésével
  (WS-Security belépéssel), és **port-scan** megadható IP-tartományban
  (`192.168.0.1-254`, `10.0.0.0/24`, vesszővel több is).
- **Kapcsolatteszt** a szerkesztőben (siker / hibás belépés / nem elérhető).
- **Gyártói sablonok:** Hikvision, Dahua, Reolink, Tapo, Axis, Foscam stb. – saját sablonokkal bővíthető.
- **Csoportok:** szűrő-csipek, átnevezés/megszüntetés (jobb klikk), áthelyezés csoportba,
  sorrend a ⏶/⏷ gombokkal.
- **Mentés / megosztás:**
  - **Fájl:** teljes export/import (kamerák + sablonok + beállítások), összefésülés vagy felülírás.
    **Formátuma azonos az Android appéval.**
  - **QR:** egy kamera QR-kódja a telefonnak (az Android app beolvassa).
- **Biztonság:** a jelszavak a **Windows DPAPI**-val titkosítva kerülnek a lemezre
  (a Windows-felhasználói fiókhoz kötve – az Android Keystore megfelelője).
- Megjegyzi az utolsó nézetet (osztott nézet / kiválasztott kamera), és induláskor visszaállítja.

## Korlátok

- **HLS (m3u8)** és **H.265** stream lejátszása nem támogatott (tiszta Rust dekóder csak
  H.264-hez van). H.265-ös kamerán állítsd a fő- vagy alstreamet H.264-re.
- A HTTPS csak érvényes tanúsítvánnyal működik (önaláírt kameratanúsítvány nem).

## Fordítás és futtatás

Kell hozzá: [Rust](https://rustup.rs) (stable, MSVC) és a Visual Studio C++ build tools
(az openh264 C++ forrásból fordul).

```bash
cargo run                # debug (a függőségek optimalizálva vannak)
cargo build --release    # target/release/ipcam_desktop.exe – egyetlen, önálló exe
cargo test               # unit tesztek
```

A GitHubon a kiadást a `desktop-vX.Y.Z` tag indítja (a verzió a `Cargo.toml`-ban),
lásd a [gyökér README-t](../README.md#kiadás-release).

## Ikon

Az ikon az Android app launcher-ikonját követi (objektív + piros „REC” pont).
Forrása az `assets/make_icon.py` szkript (Pillow kell hozzá); újragenerálás:

```bash
python assets/make_icon.py   # assets/icon.ico (16–256 px) és assets/icon.png
```

Az `icon.ico`-t a `build.rs` ágyazza be az exe-be (a verzióadatokkal együtt), az `icon.png`
az ablak- és tálcaikon.

## Adatok helye

`%APPDATA%\ipCamDesktop\` – `cameras.json` (titkosított jelszavakkal), `presets.json`,
`settings.json`. Más mappa (pl. hordozható mód): `IPCAM_DATA_DIR` környezeti változó.

## Projektszerkezet

```
src/
  main.rs                  – indítás (ablak)
  lib.rs                   – a modulfa
  i18n.rs                  – magyar/angol szövegek
  model/                   – adatmodell (Android-kompatibilis JSON)
    camera.rs                Camera, StreamType, URL-építés, összefésülés, csoportok
    preset.rs                gyártói sablonok
  storage/                 – tárolás
    settings.rs              beállítások
    store.rs                 cameras/presets/settings.json írása-olvasása
    crypto.rs                jelszótitkosítás (Windows DPAPI)
    backup.rs                mentés / QR formátum
  net/                     – hálózat
    http.rs, digest.rs       HTTP GET Basic/Digest hitelesítéssel
    onvif.rs                 WS-Discovery + SOAP stream-lekérdezés
    scanner.rs               IP-tartomány / port-scan
    tester.rs                kapcsolatteszt
  stream/                  – élőkép
    mod.rs                   Player: háttérszál, állapot, újracsatlakozás
    mjpeg.rs                 HTTP MJPEG és JPEG
    rtsp.rs                  RTSP + H.264 (retina, openh264)
  ui/                      – felület (egui)
    mod.rs                   IpCamApp: állapot, műveletek, főkeret
    state.rs                 nézet, csoportszűrő, nagyítás, műveletek
    theme.rs                 betűk, téma, kiemelőszín
    toolbar.rs, camera_list.rs, views.rs
    qr.rs                    QR-kép készítése
    dialogs/                 szerkesztő, keresés, beállítások, mentés,
                             megosztás, csoportok, megerősítések
tests/
  live.rs                  – élő tesztek a kamu kamerákkal (alapból kimaradnak)
```

## Tesztelés kamu kamerákkal

A repó gyökerében lévő közös `../test-server/` mappa használható (lásd annak README-jét):

```bash
python fake_camera.py --user admin --password 1234   # HTTP 8080 + ONVIF
powershell -ExecutionPolicy Bypass -File .\start-rtsp.ps1   # RTSP 8554 /stream1
cargo test --test live -- --ignored --test-threads=1   # élő tesztek
```

> Ha az Android emulátor fut, az a `127.0.0.1:8554`-et lefoglalja (gRPC) – ilyenkor az
> RTSP-teszt a `::1` (IPv6 loopback) címet használja, az ffmpeg-forrást is oda kell publikálni.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
