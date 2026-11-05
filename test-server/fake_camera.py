"""
Kamu IP kamera a mobilapp teszteléséhez.

Generált (mozgó) képet szolgál ki HTTP-n, ugyanazokon az útvonalakon,
amiket az app alapértelmezetten használ:

  http://<PC-IP>:8080/video.mjpg      -> HTTP MJPEG
  http://<PC-IP>:8080/snapshot.jpg    -> HTTP JPEG (képfrissítés)
  http://<PC-IP>:8080/index.m3u8      -> HLS (csak ha van ffmpeg)

Több kamera szimulálása: tegyél elé egy /camN előtagot, pl.
  http://<PC-IP>:8080/cam2/video.mjpg  (a képen "CAM 2" felirat lesz)

Indítás:
  python fake_camera.py                      # 8080-as port, jelszó nélkül
  python fake_camera.py --port 8081
  python fake_camera.py --user admin --password 1234   # Basic auth teszthez
  python fake_camera.py --no-hls             # HLS nélkül
  python fake_camera.py --no-onvif           # ONVIF keresés nélkül

ONVIF: a szerver válaszol a WS-Discovery keresésre (UDP 3702), és a
GetCapabilities / GetProfiles / GetStreamUri hívásokra (POST /onvif/...).
Ha --user/--password meg van adva, az ONVIF is kéri (WS-Security digest).
"""

import argparse
import base64
import hashlib
import io
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 640, 360
FPS = 15
COLORS = [(34, 211, 238), (239, 68, 68), (132, 204, 22), (250, 204, 21), (168, 85, 247), (249, 115, 22)]

try:
    FONT_BIG = ImageFont.truetype("arial.ttf", 36)
    FONT_SMALL = ImageFont.truetype("arial.ttf", 18)
except OSError:
    FONT_BIG = FONT_SMALL = ImageFont.load_default()


def render_frame(cam_no: int) -> bytes:
    """Egy JPEG képkocka: sötét háttér, mozgó labda, óra, kamera neve."""
    t = time.time()
    color = COLORS[(cam_no - 1) % len(COLORS)]
    img = Image.new("RGB", (WIDTH, HEIGHT), (14, 17, 22))
    d = ImageDraw.Draw(img)

    # rács (a zoom kipróbálásához is jó)
    for x in range(0, WIDTH, 40):
        d.line([(x, 0), (x, HEIGHT)], fill=(30, 36, 46))
    for y in range(0, HEIGHT, 40):
        d.line([(0, y), (WIDTH, y)], fill=(30, 36, 46))

    # mozgó labda -> látszik, hogy élő a kép
    bx = WIDTH / 2 + math.sin(t * 1.3) * (WIDTH / 2 - 40)
    by = HEIGHT / 2 + math.sin(t * 2.1) * (HEIGHT / 2 - 40)
    d.ellipse([bx - 25, by - 25, bx + 25, by + 25], fill=color)

    d.text((16, 12), f"CAM {cam_no}", font=FONT_BIG, fill=color)
    d.text((16, HEIGHT - 34), time.strftime("%Y-%m-%d  %H:%M:%S"), font=FONT_SMALL, fill=(230, 232, 235))
    # villogó REC jelzés
    if int(t * 2) % 2 == 0:
        d.ellipse([WIDTH - 90, 20, WIDTH - 74, 36], fill=(239, 68, 68))
    d.text((WIDTH - 66, 16), "REC", font=FONT_SMALL, fill=(239, 68, 68))

    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=75)
    return buf.getvalue()


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeIPCam/1.0"
    auth_token = None   # beállítja a main()
    hls_dir = None

    def log_message(self, fmt, *args):
        sys.stdout.write(f"[{time.strftime('%H:%M:%S')}] {self.client_address[0]} {fmt % args}\n")

    def _check_auth(self) -> bool:
        if not self.auth_token:
            return True
        if self.headers.get("Authorization") == f"Basic {self.auth_token}":
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="FakeIPCam"')
        self.end_headers()
        return False

    def do_GET(self):
        if not self._check_auth():
            return
        path = self.path.split("?")[0]
        cam_no = 1
        m = re.match(r"^/cam(\d+)(/.*)$", path)
        if m:
            cam_no, path = int(m.group(1)), m.group(2)

        if path in ("/", "/index.html"):
            return self._index()
        if path == "/snapshot.jpg":
            return self._snapshot(cam_no)
        if path == "/video.mjpg":
            return self._mjpeg(cam_no)
        if self.hls_dir and (path.endswith(".m3u8") or path.endswith(".ts")):
            return self._hls_file(os.path.basename(path))
        self.send_error(404, "Nincs ilyen utvonal")

    # ---------------- ONVIF (SOAP) ----------------
    def do_POST(self):
        path = self.path.split("?")[0]
        if not path.startswith("/onvif/"):
            return self.send_error(404)
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace")
        if self.auth_token and not onvif_auth_ok(body, *base64.b64decode(self.auth_token).decode().split(":", 1)):
            self.log_message("ONVIF: hibás/hiányzó WS-Security belépés")
            return self._soap(ONVIF_FAULT_AUTH, status=400)

        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        base = f"http://{host}"
        if "GetCapabilities" in body:
            self.log_message("ONVIF GetCapabilities")
            return self._soap(f"""<tds:GetCapabilitiesResponse><tds:Capabilities>
<tt:Media><tt:XAddr>{base}/onvif/media_service</tt:XAddr></tt:Media>
</tds:Capabilities></tds:GetCapabilitiesResponse>""")
        if "GetProfiles" in body:
            self.log_message("ONVIF GetProfiles")
            return self._soap("""<trt:GetProfilesResponse>
<trt:Profiles token="main" fixed="true"><tt:Name>Fo stream (MJPEG)</tt:Name></trt:Profiles>
<trt:Profiles token="snap" fixed="true"><tt:Name>Allokep (JPEG)</tt:Name></trt:Profiles>
</trt:GetProfilesResponse>""")
        if "GetStreamUri" in body:
            snap = ">snap<" in body
            self.log_message(f"ONVIF GetStreamUri ({'snap' if snap else 'main'})")
            uri = f"{base}/snapshot.jpg" if snap else f"{base}/video.mjpg"
            return self._soap(f"""<trt:GetStreamUriResponse><trt:MediaUri>
<tt:Uri>{uri}</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout>
</trt:MediaUri></trt:GetStreamUriResponse>""")
        self.send_error(400, "Nem tamogatott ONVIF hivas")

    def _soap(self, inner, status=200):
        data = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
            'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" '
            'xmlns:trt="http://www.onvif.org/ver10/media/wsdl" '
            'xmlns:tt="http://www.onvif.org/ver10/schema" '
            'xmlns:ter="http://www.onvif.org/ver10/error">'
            f"<s:Body>{inner}</s:Body></s:Envelope>"
        ).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/soap+xml; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _index(self):
        body = (
            "<html><body style='background:#0e1116;color:#e6e8eb;font-family:sans-serif'>"
            "<h2>Fake IP kamera fut</h2>"
            "<p><a style='color:#22d3ee' href='/video.mjpg'>/video.mjpg</a> (MJPEG)</p>"
            "<p><a style='color:#22d3ee' href='/snapshot.jpg'>/snapshot.jpg</a> (JPEG)</p>"
            "<p>/index.m3u8 (HLS)</p>"
            "<img src='/video.mjpg' width=640></body></html>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _snapshot(self, cam_no):
        jpg = render_frame(cam_no)
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(jpg)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(jpg)

    def _mjpeg(self, cam_no):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            while True:
                jpg = render_frame(cam_no)
                self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\n")
                self.wfile.write(f"Content-Length: {len(jpg)}\r\n\r\n".encode())
                self.wfile.write(jpg)
                self.wfile.write(b"\r\n")
                time.sleep(1 / FPS)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            self.log_message("MJPEG kliens lecsatlakozott")

    def _hls_file(self, name):
        full = os.path.join(self.hls_dir, name)
        if not os.path.isfile(full):
            return self.send_error(404, "HLS meg nem keszult el, varj par masodpercet")
        with open(full, "rb") as f:
            data = f.read()
        ctype = "application/vnd.apple.mpegurl" if name.endswith(".m3u8") else "video/mp2t"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)


ONVIF_FAULT_AUTH = """<s:Fault><s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:NotAuthorized</s:Value>
</s:Subcode></s:Code><s:Reason><s:Text xml:lang="en">Sender not authorized</s:Text></s:Reason></s:Fault>"""


def onvif_auth_ok(body: str, user: str, password: str) -> bool:
    """WS-Security UsernameToken PasswordDigest ellenőrzése."""
    def tag(name):
        m = re.search(rf"<(?:\w+:)?{name}(?:\s[^>]*)?>([^<]*)</(?:\w+:)?{name}>", body)
        return m.group(1) if m else None
    u, digest, nonce, created = tag("Username"), tag("Password"), tag("Nonce"), tag("Created")
    if not (u and digest and nonce and created) or u != user:
        return False
    expected = base64.b64encode(
        hashlib.sha1(base64.b64decode(nonce) + created.encode() + password.encode()).digest()
    ).decode()
    return expected == digest


def ws_discovery_responder(http_port: int, name: str):
    """Válaszol a WS-Discovery Probe-okra (UDP 3702, 239.255.255.250)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", 3702))
    except OSError as e:
        print(f"! ONVIF keresés nem indult (3702-es port foglalt?): {e}")
        return
    group = socket.inet_aton("239.255.255.250")
    for ip in local_ips():  # minden hálózati kártyán csatlakozunk a csoporthoz
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, group + socket.inet_aton(ip))
        except OSError:
            pass
    dev_uuid = uuid.uuid4()
    while True:
        data, addr = sock.recvfrom(65535)
        text = data.decode("utf-8", "replace")
        if "Probe" not in text or "ProbeMatches" in text:
            continue
        msg_id = re.search(r"MessageID>([^<]+)<", text)
        # melyik saját IP-nket látja a kérdező?
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(addr)
        my_ip = probe.getsockname()[0]
        probe.close()
        resp = f"""<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:{uuid.uuid4()}</w:MessageID><w:RelatesTo>{msg_id.group(1) if msg_id else ''}</w:RelatesTo>
<w:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</w:To>
<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</w:Action></e:Header>
<e:Body><d:ProbeMatches><d:ProbeMatch>
<w:EndpointReference><w:Address>urn:uuid:{dev_uuid}</w:Address></w:EndpointReference>
<d:Types>dn:NetworkVideoTransmitter</d:Types>
<d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/{name.replace(' ', '%20')} onvif://www.onvif.org/hardware/FakeCam-1000</d:Scopes>
<d:XAddrs>http://{my_ip}:{http_port}/onvif/device_service</d:XAddrs>
<d:MetadataVersion>1</d:MetadataVersion>
</d:ProbeMatch></d:ProbeMatches></e:Body></e:Envelope>"""
        sock.sendto(resp.encode(), addr)
        print(f"[{time.strftime('%H:%M:%S')}] ONVIF keresés innen: {addr[0]} -> válaszoltam ({my_ip})")


def start_hls(out_dir: str):
    """ffmpeg tesztképből élő HLS streamet gyárt a megadott mappába."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("! ffmpeg nem található – a HLS ki van kapcsolva.")
        return None
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-re",
        "-f", "lavfi", "-i", f"testsrc2=size={WIDTH}x{HEIGHT}:rate=25",
        "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-g", "50",
        "-pix_fmt", "yuv420p",
        "-f", "hls", "-hls_time", "2", "-hls_list_size", "5",
        "-hls_flags", "delete_segments",
        os.path.join(out_dir, "index.m3u8"),
    ]
    return subprocess.Popen(cmd)


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description="Kamu IP kamera teszteléshez")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--user", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--no-hls", action="store_true")
    ap.add_argument("--no-onvif", action="store_true", help="ONVIF keresés/SOAP kikapcsolása")
    ap.add_argument("--name", default="Teszt Kamera", help="ONVIF-ben hirdetett név")
    args = ap.parse_args()

    if args.user:
        Handler.auth_token = base64.b64encode(f"{args.user}:{args.password}".encode()).decode()

    ffmpeg_proc = None
    if not args.no_hls:
        Handler.hls_dir = tempfile.mkdtemp(prefix="fakecam_hls_")
        ffmpeg_proc = start_hls(Handler.hls_dir)
        if not ffmpeg_proc:
            Handler.hls_dir = None

    if not args.no_onvif:
        threading.Thread(target=ws_discovery_responder, args=(args.port, args.name), daemon=True).start()

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True

    print("=" * 60)
    print(" Fake IP kamera elindult")
    print("=" * 60)
    for ip in local_ips() or ["<a PC IP címe>"]:
        print(f"  IP: {ip}   port: {args.port}")
    print()
    print("  Az appban:  IP = fenti cím, Port =", args.port)
    print("   - HTTP MJPEG              útvonal: /video.mjpg")
    print("   - HTTP JPEG (képfrissítés) útvonal: /snapshot.jpg")
    if Handler.hls_dir:
        print("   - HLS (m3u8)              útvonal: /index.m3u8")
    print("   - több kamera: /cam2/video.mjpg, /cam3/snapshot.jpg ...")
    if not args.no_onvif:
        print(f"  ONVIF: bekapcsolva (\"{args.name}\") -> az appban a 🔍 Kamera keresés megtalálja")
    if args.user:
        print(f"  Hitelesítés: {args.user} / {args.password}")
    print()
    print(f"  Böngészős ellenőrzés: http://localhost:{args.port}/")
    print("  Leállítás: Ctrl+C")
    print("=" * 60)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if ffmpeg_proc:
            ffmpeg_proc.terminate()
        if Handler.hls_dir:
            shutil.rmtree(Handler.hls_dir, ignore_errors=True)
        print("Leállítva.")


if __name__ == "__main__":
    main()
