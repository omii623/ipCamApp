# IP Kamerák

Android alkalmazás IP-kamerák megtekintésére a helyi hálózaton — RTSP, HTTP MJPEG,
HTTP JPEG (képfrissítés) és HLS streameken keresztül. Kamerák kézi felvétele vagy
automatikus keresése, csoportosítás, osztott nézet, és mentés/megosztás.

> Magyar és angol nyelvű felület, világos/sötét téma, választható kiemelőszín.

## Funkciók

- **Stream-típusok:** RTSP és HLS (Media3 **ExoPlayer**), HTTP **MJPEG** és **JPEG**
  (saját dekóder).
- **Kamera nézet:** csípéssel nagyítás (1–5×), dupla koppintásos zoom, teljes képernyő
  (fekvőben automatikus), automatikus újracsatlakozás.
- **Osztott (rács) nézet:** több kamera egyszerre, állítható oszlopszámmal; csoportonként is.
- **Hálózati keresés:** **ONVIF** (WS-Discovery) a pontos stream-cím lekérdezésével, és
  **RTSP port-scan** megadható IP-tartományban.
- **Kapcsolatteszt:** a beírt belépéssel ellenőrzi az elérhetőséget (ikonos visszajelzés).
- **Gyártói sablonok:** Hikvision, Dahua, Reolink, Tapo, Axis, Foscam stb. útvonalai —
  saját sablonokkal bővíthető.
- **Csoportok:** szűrő-csipek, áthúzásos sorrend, átnevezés/megszüntetés.
- **Beállítások:** téma (rendszer/világos/sötét), nyelv (magyar/angol), kiemelőszín
  csúszkával, alapértelmezett keresési portok, Névjegy build-azonosítóval.
- **Mentés / megosztás:**
  - **Fájl:** teljes mentés/visszaállítás (kamerák + sablonok + beállítások) JSON-ba,
    a rendszer fájlválasztójával (SAF).
  - **QR:** egy kamera megosztása két telefon között — az egyik generál, a másik beolvas.
- **Biztonság:** a kamerajelszavak az **Android Keystore**-ban tárolt AES-256/GCM kulccsal
  titkosítva kerülnek a telefonra.

## Követelmények

- Android Studio (AGP a `gradle/libs.versions.toml`-ban), JDK 11+
- `minSdk` 24, cél `targetSdk` 37
- A streameléshez a telefon és a kamera **ugyanazon a helyi hálózaton** legyen.

## Fordítás és futtatás

```bash
# Debug APK
./gradlew assembleDebug

# Telepítés csatlakoztatott eszközre
./gradlew installDebug

# Unit tesztek
./gradlew testDebugUnitTest
```

Vagy Android Studióból: válaszd ki az eszközt, majd **Run ▶**.

## Projektszerkezet

```
app/src/main/java/com/example/ipcamapp/
  Camera.kt, CameraRepository.kt   – modell és (titkosított) tárolás
  Crypto.kt                        – AES/GCM a Keystore-ral
  MjpegView.kt, ViewerActivity.kt, ZoomLayout.kt – lejátszás, zoom, teljes képernyő
  GridActivity.kt, CameraTile.kt   – osztott nézet
  Onvif.kt, PortScanner.kt, DiscoveryActivity.kt – hálózati keresés
  ConnectionTester.kt, Presets.kt  – kapcsolatteszt, gyártói sablonok
  App.kt, Settings, Accent.kt, SettingsActivity.kt – beállítások, téma, szín
  Backup.kt, Qr.kt, PortraitCaptureActivity.kt – mentés/import, QR
  MainActivity.kt                  – főképernyő (lista, csoportok, + menü)
app/src/test/…                     – unit tesztek
test-server/                       – helyi kamu kameraszerver fejlesztéshez
```

Az egész logika az Activity-kben és néhány segédobjektumban él (nincs külön
architektúra-réteg) — a projekt méretéhez igazodva egyszerű és átlátható.

## Tesztek

Tiszta JVM unit tesztek (gyorsak, eszköz nélkül futnak):

- `PortScannerTest` – IP-tartomány és port feldolgozás
- `CameraTest` – URL-építés, JSON oda-vissza
- `PresetTest`, `MergeTest` – sablonok és import-összefésülés
- `BackupTest`, `BackupCompatTest` – mentés/QR oda-vissza és verzió-kompatibilitás
- `NegativeTest` – hibás/hiányos adatok kezelése

```bash
./gradlew testDebugUnitTest
# riport: app/build/reports/tests/testDebugUnitTest/index.html
```

## Helyi teszt kamera

Valódi kamera nélkül a `test-server/` mappa kamu kamerát indít a PC-n:

```bash
cd test-server
python fake_camera.py                 # HTTP MJPEG/JPEG + ONVIF a 8080-as porton
python fake_camera.py --user admin --password 1234
```

Az appban: IP = a PC hálózati címe, port = 8080, útvonal = `/video.mjpg`.
Részletek (RTSP is): `test-server/README.md`.

## Megjegyzések és korlátok

- A kamerakeresés és élőkép csak a **helyi hálózaton** működik (otthonon kívülről
  VPN vagy porttovábbítás kell).
- A mentés-**fájl** a jelszavakat **olvashatóan** tartalmazza (a Keystore-kulcs
  eszközhöz kötött, nem hordozható) — az app export előtt figyelmeztet.
- Valódi kamerákkal (Digest-hitelesítés, H.265) a streamelés még kiterjedt tesztelést
  igényel; a fejlesztés eddig kamu szerverrel és emulátoron/telefonon a felületen történt.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
