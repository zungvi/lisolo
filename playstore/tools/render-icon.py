"""Render the Lisolo launcher vector to a 512x512 Play Store icon.

The store icon in fastlane is still Snikket's parrot. The real icon is an adaptive icon whose
foreground lives as an Android vector drawable, so render that rather than upscaling the 432px
raster: the path is flat colour, so a clean rasterisation beats any interpolation.

No rsvg, Inkscape, ImageMagick, cairosvg or PIL on this machine, so this does the whole job:
parse the pathData, flatten the cubics, fill with 4x4 supersampling, and write the PNG by hand.
"""
import re
import struct
import zlib

SIZE = 512
SS = 4  # supersampling factor per axis
VIEWPORT = 108.0

FG = (0xFF, 0x7A, 0x00)  # android:fillColor on the foreground path
BG = (0xFF, 0xFF, 0xFF)  # @color/new_launcher_background

PATH = (
    "M31,19L80,36C88,39 90,50 83,55L31,89C25,93 18,87 20,80L29,57H50C56,57 56,48 50,48H29"
    "L20,28C18,21 25,16 31,19Z"
)


def parse(path):
    """Absolute M/L/C/H/V/Z only - which is all this drawable uses."""
    tokens = re.findall(r"([MLCHVZ])([^MLCHVZ]*)", path, re.I)
    pts, cur, start = [], (0.0, 0.0), (0.0, 0.0)
    for cmd, raw in tokens:
        nums = [float(n) for n in re.findall(r"-?\d*\.?\d+", raw)]
        c = cmd.upper()
        if c == "M":
            cur = start = (nums[0], nums[1])
            pts.append(cur)
        elif c == "L":
            for i in range(0, len(nums), 2):
                cur = (nums[i], nums[i + 1])
                pts.append(cur)
        elif c == "H":
            for n in nums:
                cur = (n, cur[1])
                pts.append(cur)
        elif c == "V":
            for n in nums:
                cur = (cur[0], n)
                pts.append(cur)
        elif c == "C":
            for i in range(0, len(nums), 6):
                p1 = (nums[i], nums[i + 1])
                p2 = (nums[i + 2], nums[i + 3])
                p3 = (nums[i + 4], nums[i + 5])
                pts.extend(cubic(cur, p1, p2, p3, 48))
                cur = p3
        elif c == "Z":
            pts.append(start)
            cur = start
    return pts


def cubic(p0, p1, p2, p3, steps):
    out = []
    for s in range(1, steps + 1):
        t = s / steps
        u = 1 - t
        x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]
        y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
        out.append((x, y))
    return out


def coverage(poly):
    """Per-pixel coverage in 0..1 via crossing counts on each supersample row."""
    scale = SIZE * SS / VIEWPORT
    n = len(poly)
    edges = []
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if y0 == y1:
            continue
        edges.append((x0 * scale, y0 * scale, x1 * scale, y1 * scale))

    acc = [[0] * SIZE for _ in range(SIZE)]
    for sy in range(SIZE * SS):
        yc = sy + 0.5
        xs = []
        for x0, y0, x1, y1 in edges:
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                xs.append(x0 + (yc - y0) * (x1 - x0) / (y1 - y0))
        if not xs:
            continue
        xs.sort()
        row = acc[sy // SS]
        # even-odd; identical to nonzero for this single non-self-intersecting contour
        for i in range(0, len(xs) - 1, 2):
            a, b = xs[i], xs[i + 1]
            ia, ib = int(a // SS), int(b // SS)
            for px in range(max(0, ia), min(SIZE - 1, ib) + 1):
                lo = max(a, px * SS)
                hi = min(b, (px + 1) * SS)
                if hi > lo:
                    row[px] += (hi - lo)
    denom = SS * SS
    return [[min(1.0, v / denom) for v in r] for r in acc]


def write_png(path, cov):
    raw = bytearray()
    for y in range(SIZE):
        raw.append(0)  # filter type: none
        row = cov[y]
        for x in range(SIZE):
            a = row[x]
            for ch in range(3):
                raw.append(int(round(BG[ch] * (1 - a) + FG[ch] * a)))
            raw.append(255)  # opaque: Play does its own masking

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    import sys

    poly = parse(PATH)
    print(f"flattened to {len(poly)} points")
    cov = coverage(poly)
    filled = sum(1 for r in cov for v in r if v > 0.5)
    print(f"arrow covers {filled} px ({100 * filled / (SIZE * SIZE):.1f}% of the canvas)")
    write_png(sys.argv[1], cov)
    print(f"wrote {sys.argv[1]}")
