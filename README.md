# IP Kamerák

IP-kamerák megtekintése a helyi hálózaton — **Androidon és Windowson**. Kamerák kézi
felvétele vagy automatikus keresése (ONVIF, port-scan), csoportosítás, osztott nézet,
gyártói sablonok, mentés/megosztás.

A két alkalmazás **ugyanazt a mentésfájl- és QR-formátumot** használja, így a kamerák
egyikből a másikba átvihetők (pl. a telefonon felvett kamerák QR-kóddal vagy exporttal
a PC-re).

> Magyar és angol nyelvű felület, világos/sötét téma, választható kiemelőszín.

## Letöltés

| Platform | Legújabb verzió | Letöltés |
|---|---|---|
| Android | [1.1.0](https://github.com/omii623/ipCamApp/releases/tag/android-v1.1.0) | [ipCamApp-v1.1.0.apk](https://github.com/omii623/ipCamApp/releases/download/android-v1.1.0/ipCamApp-v1.1.0.apk) |
| Windows | [1.0.0](https://github.com/omii623/ipCamApp/releases/tag/desktop-v1.0.0) | [ipCamDesktop-1.0.0.exe](https://github.com/omii623/ipCamApp/releases/download/desktop-v1.0.0/ipCamDesktop-1.0.0.exe) |

Az összes korábbi kiadás: [Releases](https://github.com/omii623/ipCamApp/releases).

## Platformok

| Platform | Mappa | Technológia | Leírás | Release tag |
|---|---|---|---|---|
| Android | [`app/`](app/README.md) | Kotlin, Media3 ExoPlayer | [app/README.md](app/README.md) | `android-vX.Y` |
| Windows | [`desktop/`](desktop/README.md) | Rust, egui, retina + openh264 / FFmpeg (H.265) | [desktop/README.md](desktop/README.md) | `desktop-vX.Y.Z` |

Főbb eltérés: az Android a HLS-t is lejátssza (ExoPlayer), az asztali nem. A H.265-öt
mindkettő tudja (az asztali a beépített, statikusan linkelt FFmpeg libavcodec-kel).

## Repó szerkezete

```
app/                 – Android Gradle projekt (ezt nyisd meg az Android Studióban)
desktop/             – Windows asztali változat (Cargo projekt)
test-server/         – helyi kamu kameraszerver (HTTP MJPEG/JPEG, ONVIF, RTSP) – mindkettőhöz
.github/workflows/   – release workflow-k (Android, asztali)
```

## Gyors kezdés

```bash
# Android – debug APK
cd app && ./gradlew assembleDebug

# Windows – futtatás
cd desktop && cargo run --release

# Kamu kamera teszteléshez
cd test-server && python fake_camera.py
```

Részletek a platformok saját README-jében és a [`test-server/README.md`](test-server/README.md)-ben.

## Kiadás (release)

A két platform **külön verziózott** és **külön GitHub Release-be** kerül. A kiadást egy
tag pusholása indítja, a buildet a GitHub Actions végzi (`.github/workflows/`). A tag
verziójának egyeznie kell a projektben lévővel, különben a build leáll.

| Tag | Verzió helye | Eredmény |
|---|---|---|
| `android-vX.Y` | `app/app/build.gradle.kts` → `versionName` (+ `versionCode` emelése) | aláírt `ipCamApp-X.Y.apk` |
| `desktop-vX.Y.Z` | `desktop/Cargo.toml` → `version` | `ipCamDesktop-X.Y.Z.exe` |

```bash
# pl. Android 1.2: versionName = "1.2", versionCode += 1, commit, majd
git tag android-v1.2
git push origin android-v1.2

# pl. asztali 0.2.0: version = "0.2.0", commit, majd
git tag desktop-v0.2.0
git push origin desktop-v0.2.0
```

### Android aláírás a CI-ban

A repó *Settings → Secrets and variables → Actions* alatt kell négy secret:

| Secret | Tartalom |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | a `release-keystore.jks` base64-ben |
| `ANDROID_STORE_PASSWORD` | keystore jelszó |
| `ANDROID_KEY_ALIAS` | kulcs alias |
| `ANDROID_KEY_PASSWORD` | kulcs jelszó |

A base64 előállítása PowerShellben:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\release-keystore.jks")) | Set-Clipboard
```

Ha a secretek hiányoznak, az Android workflow hibával leáll (aláíratlan APK nem kerül ki).

## Megjegyzések

- A kamerakeresés és élőkép csak a **helyi hálózaton** működik (otthonon kívülről
  VPN vagy porttovábbítás kell).
- A mentés-**fájl** mindkét platformon **olvashatóan** tartalmazza a jelszavakat
  (a tároláshoz használt kulcs eszközhöz kötött) — export előtt mindkét app figyelmeztet.
