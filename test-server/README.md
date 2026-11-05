# Teszt kamerák a mobilapphoz

A PC-n kamu IP kamerákat indít, amikhez a telefonos app csatlakozni tud.
Az apphoz nem kell hozzányúlni.

## 1. HTTP kamera (MJPEG, JPEG, HLS): `fake_camera.py`

Kell hozzá: Python 3 + Pillow (`pip install pillow`), HLS-hez ffmpeg.

```
python fake_camera.py
python fake_camera.py --user admin --password 1234   # jelszavas teszt
```

| App „Stream típusa” | Port | Útvonal |
|---|---|---|
| HTTP MJPEG | 8080 | `/video.mjpg` |
| HTTP JPEG (képfrissítés) | 8080 | `/snapshot.jpg` |
| HLS (m3u8) | 8080 | `/index.m3u8` |

Több kamera: `/cam2/video.mjpg`, `/cam3/snapshot.jpg` … (a képen „CAM 2”, „CAM 3”).
Böngészős ellenőrzés: http://localhost:8080/

### Kamera keresés (ONVIF) tesztelése

A `fake_camera.py` alapból ONVIF kameraként is viselkedik: az appban a 🔍 gomb
megtalálja „Teszt Kamera” néven. Koppints rá, add meg a belépést
(ha a szervert `--user/--password`-del indítottad), és válassz streamet
(„Fo stream (MJPEG)” vagy „Allokep (JPEG)”) – az app kitölti a szerkesztőt.

Kikapcsolás: `--no-onvif`. Más név: `--name "Garázs"`.
A tűzfalon az UDP 3702-es portot is engedni kell:
```
netsh advfirewall firewall add rule name="FakeCam ONVIF" dir=in action=allow protocol=UDP localport=3702
```

A port-keresés (554 / 8554) a `start-rtsp.ps1`-gyel tesztelhető.

## 2. RTSP kamera: `start-rtsp.ps1`

Kell hozzá: ffmpeg. A MediaMTX RTSP szervert első futáskor letölti a `bin\` mappába.

```
powershell -ExecutionPolicy Bypass -File .\start-rtsp.ps1
powershell -ExecutionPolicy Bypass -File .\start-rtsp.ps1 -Cameras 4
```

Az appban: **RTSP**, port **8554**, útvonal **/stream1** (…/stream4).

## Fontos

- A telefon és a PC **ugyanazon a Wi-Fi/hálózaton** legyen.
- Az appba a **PC hálózati IP-jét** írd (pl. `192.168.0.100`), **ne** `localhost`-ot:
  a telefonon a localhost a telefon saját magát jelenti.
- Első indításkor a Windows tűzfal rákérdez: engedélyezd **Magánhálózat** esetén.
  Ha nem kérdez és nem megy:
  ```
  netsh advfirewall firewall add rule name="FakeCam" dir=in action=allow protocol=TCP localport=8080,8554
  ```
  (rendszergazdai parancssorból)
- Ha VPN fut a PC-n, az elviheti a forgalmat. Ha nem megy, kapcsold ki teszt idejére.
