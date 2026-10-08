"""Build the 1024x500 Play feature graphic from brand assets only.

No screenshots and no message content, so there is nothing to redact. The logo is rasterised
from the launcher vector rather than upscaled, same as the store icon.

Play crops this image to different shapes depending on where it surfaces, so the whole
composition is centred and kept inside a safe inset rather than running to the edges.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import render_icon as ri

W, H = 1024, 500
SAFE = 0.80  # keep content inside the central 80% - Play may crop to the edges

ORANGE = (0xFF, 0x7A, 0x00)
INK_TOP = (0x1A, 0x15, 0x12)
INK_BOT = (0x2F, 0x21, 0x18)
WHITE = (0xFF, 0xFF, 0xFF)
MUTED = (0xC6, 0xB8, 0xAD)

NAME = os.environ.get("APP_NAME", "Lisolo")
TAGLINE = os.environ.get("TAGLINE", "Private messaging on an open network")

FONT_CANDIDATES_BOLD = [
    "/System/Library/Fonts/Supplemental/Futura.ttc",
    "/System/Library/Fonts/Avenir Next.ttc",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]
FONT_CANDIDATES_REG = [
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
]


def load(paths, size, index=0):
    for p in paths:
        if os.path.exists(p):
            try:
                f = ImageFont.truetype(p, size, index=index)
                return f, p
            except Exception:
                continue
    return ImageFont.load_default(), "<default>"


def background():
    """Vertical gradient with a soft orange bloom where the logo sits."""
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):
        t = y / (H - 1)
        d.line(
            [(0, y), (W, y)],
            fill=tuple(int(INK_TOP[i] + (INK_BOT[i] - INK_TOP[i]) * t) for i in range(3)),
        )
    # bloom: a blurred orange disc, composited at low opacity so it reads as warmth not a blob
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse([-40, 60, 520, 620], fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(110))
    bg = Image.composite(Image.new("RGB", (W, H), ORANGE), bg, glow.point(lambda v: v // 3))
    return bg


def logo(px):
    """The launcher vector as an RGBA image of the given pixel size, alpha from coverage."""
    prev_size = ri.SIZE
    ri.SIZE = px
    try:
        poly = ri.parse(ri.PATH)
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        span = max(max(xs) - min(xs), max(ys) - min(ys))
        cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
        poly = [(x - cx + span / 2, y - cy + span / 2) for (x, y) in poly]
        ri.VIEWPORT = span
        cov = ri.coverage(poly)
    finally:
        ri.SIZE = prev_size
    img = Image.new("RGBA", (px, px), ORANGE + (0,))
    img.putalpha(
        Image.frombytes("L", (px, px), bytes(int(round(255 * v)) for r in cov for v in r))
    )
    return img


def main(out):
    img = background().convert("RGBA")
    d = ImageDraw.Draw(img)

    f_name, p1 = load(FONT_CANDIDATES_BOLD, 104)
    f_tag, p2 = load(FONT_CANDIDATES_REG, 33)
    print(f"  wordmark font: {p1}\n  tagline font:  {p2}")

    LOGO_PX = 188
    GAP = 54
    mark = logo(LOGO_PX)

    name_w = d.textlength(NAME, font=f_name)
    tag_w = d.textlength(TAGLINE, font=f_tag)
    text_w = max(name_w, tag_w)

    total = LOGO_PX + GAP + text_w
    safe_w = W * SAFE
    if total > safe_w:
        print(f"  WARNING: composition {total:.0f}px exceeds safe width {safe_w:.0f}px")
    x0 = (W - total) / 2

    img.alpha_composite(mark, (int(x0), int((H - LOGO_PX) / 2)))

    tx = x0 + LOGO_PX + GAP
    # optical centring: cap height, not the font's full line box
    nb = d.textbbox((0, 0), NAME, font=f_name)
    tb = d.textbbox((0, 0), TAGLINE, font=f_tag)
    block_h = (nb[3] - nb[1]) + 26 + (tb[3] - tb[1])
    ty = (H - block_h) / 2

    d.text((tx, ty - nb[1]), NAME, font=f_name, fill=WHITE)
    d.text((tx, ty + (nb[3] - nb[1]) + 26 - tb[1]), TAGLINE, font=f_tag, fill=MUTED)

    # a single accent rule under the wordmark, the one piece of ornament
    ry = ty + (nb[3] - nb[1]) + 13
    d.rectangle([tx, ry, tx + 64, ry + 4], fill=ORANGE)

    img.convert("RGB").save(out, "PNG", optimize=True)
    print(f"  wrote {out} ({os.path.getsize(out) / 1024:.0f} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
