"""Az alkalmazás ikonjának előállítása (az Android ipCamApp launcher-ikonja alapján).

Kamera-objektív: türkiz gyűrű, sötét lencse, csillanás és piros „REC” pont,
sötét, lekerekített négyzet alakú háttéren, finom rácsvonallal (osztott nézet).

Futtatás (Pillow kell hozzá):  python assets/make_icon.py
Kimenet:  assets/icon.ico (16–256 px, az exe-be kerül), assets/icon.png (256 px, ablakikon)
"""

from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SS = 4  # túlmintavételezés az élsimításhoz

BG = (0x12, 0x18, 0x21, 255)
GRID = (0x22, 0xD3, 0xEE, 40)
RING = (0x22, 0xD3, 0xEE, 255)
BODY = (0x0B, 0x0F, 0x14, 255)
LENS = (0x0E, 0x74, 0x90, 255)
PUPIL = (0x06, 0x10, 0x18, 255)
GLINT = (255, 255, 255, 204)
REC = (0xEF, 0x44, 0x44, 255)


def render(size: int) -> Image.Image:
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = s / 100  # 100 egységes rajzlap

    def circle(cx, cy, r, color):
        d.ellipse([(cx - r) * u, (cy - r) * u, (cx + r) * u, (cy + r) * u], fill=color)

    # háttér: lekerekített négyzet, finom kereszt-rács (16 px-en elhagyjuk, csak zaj lenne)
    d.rounded_rectangle([2 * u, 2 * u, 98 * u, 98 * u], radius=20 * u, fill=BG)
    if size >= 32:
        w = max(1, round(1.2 * u))
        d.line([50 * u, 2 * u, 50 * u, 98 * u], fill=GRID, width=w)
        d.line([2 * u, 50 * u, 98 * u, 50 * u], fill=GRID, width=w)

    # objektív (az Android-ikon arányai, nagyobbra skálázva)
    circle(50, 52, 36, RING)
    circle(50, 52, 28.5, BODY)
    circle(50, 52, 18, LENS)
    circle(50, 52, 10.5, PUPIL)
    if size >= 24:
        circle(43, 45, 4.5, GLINT)
    # piros felvétel-jelző pont
    circle(80, 18, 8.5 if size >= 32 else 10, REC)

    return img.resize((size, size), Image.LANCZOS)


def main():
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    images = [render(n) for n in sizes]
    images[-1].save(HERE / "icon.ico", sizes=[(n, n) for n in sizes], append_images=images[:-1])
    images[-1].save(HERE / "icon.png")
    # előnézet: minden méret egymás mellett
    preview = Image.new("RGBA", (sum(sizes) + 10 * len(sizes), 256), (240, 240, 240, 255))
    x = 0
    for im in images:
        preview.alpha_composite(im, (x, 256 - im.height))
        x += im.width + 10
    preview.save(HERE / "icon-preview.png")
    print("kész:", HERE / "icon.ico")


if __name__ == "__main__":
    main()
