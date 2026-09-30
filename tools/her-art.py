#!/usr/bin/env python3
"""Every picture xnav.k shows of itself, made from HER artwork - nothing drawn here.

    python3 tools/her-art.py [path/to/NVII-DASH/assets/icons]

Her masters (NVII-DASH assets/icons, supplied as PNGs):
  navi-*.png     her CB750 with the route and the pin     - the main artwork
  navi-ex-*.png  her Ninja EX, yellow, the same composition - the alternate

What this writes into app/src/main/res, and from which file:
  mipmap-*/ic_launcher_foreground.png   navi-maskable-512.png, as it is (launcher, Android 12+ splash)
  drawable-nodpi/xnav_logo.png          navi-512.png, cut to its own ring (the splash before Android 12)
  drawable-*/bike_black.png             navi-512.png cut to its ring  - the map marker, "black": her black CB750
  drawable-*/bike_yellow.png            navi-ex-512.png cut to its ring - the map marker, "yellow": her yellow Ninja
  drawable-*/ic_notify.png              navi-512.png's yellow ring, pin and road dashes, as a one-colour
                                        silhouette: Android draws a notification's small icon from its
                                        alpha alone, so the full-colour art would be a blank disc there.

"Cut to its ring": everything outside the yellow ring made transparent. The
navi-ex files carry a light checkerboard painted in around the ring (a
"transparent" background that is really pixels); the cut removes it.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/home/nvii/dashboredapp-server/NVII-DASH/assets/icons")
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def is_yellow(p):
    return p[0] > 200 and p[1] > 150 and p[2] < 80


def ring_box(im):
    """The yellow ring's outer edge, measured across the middle row and column."""
    w, h = im.size
    xs = [x for x in range(w) if is_yellow(im.getpixel((x, h // 2)))]
    ys = [y for y in range(h) if is_yellow(im.getpixel((w // 2, y)))]
    return xs[0], ys[0], xs[-1] + 1, ys[-1] + 1


def cut_to_ring(im):
    """The art inside its ring, transparent outside - drawn 4x and scaled down, so the edge is smooth."""
    im = im.convert("RGBA")
    x0, y0, x1, y1 = ring_box(im)
    k = 4
    mask = Image.new("L", (im.width * k, im.height * k), 0)
    ImageDraw.Draw(mask).ellipse((x0 * k, y0 * k, x1 * k, y1 * k), fill=255)
    im.putalpha(mask.resize(im.size, Image.LANCZOS))
    return im.crop((x0, y0, x1, y1))


def silhouette(im):
    """The yellow of her art as white, everything else transparent."""
    im = im.convert("RGBA")
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    px, op = im.load(), out.load()
    for y in range(im.height):
        for x in range(im.width):
            if is_yellow(px[x, y]):
                op[x, y] = (255, 255, 255, 255)
    return out.crop(ring_box(im))


def write(img, folder, name, px):
    d = RES / folder
    d.mkdir(parents=True, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(d / name, optimize=True)


def main():
    main_art = Image.open(SRC / "navi-512.png")
    ex_art = Image.open(SRC / "navi-ex-512.png")
    maskable = Image.open(SRC / "navi-maskable-512.png").convert("RGB")
    black, yellow, notify = cut_to_ring(main_art), cut_to_ring(ex_art), silhouette(main_art)
    for dens, scale in DENSITIES.items():
        write(maskable, f"mipmap-{dens}", "ic_launcher_foreground.png", round(108 * scale))
        write(black, f"drawable-{dens}", "bike_black.png", round(56 * scale))
        write(yellow, f"drawable-{dens}", "bike_yellow.png", round(56 * scale))
        write(notify, f"drawable-{dens}", "ic_notify.png", round(24 * scale))
    write(black, "drawable-nodpi", "xnav_logo.png", 512)
    print("written from", SRC)


if __name__ == "__main__":
    main()
