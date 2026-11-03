"""Draws TFR-TAK's icons from simple primitives (original artwork): a white
top-down airplane silhouette pointing up, flying over a red ground ring
seen in perspective (the TFR's footprint).

    python3 tools/icons/make_icons.py .
"""
import math
import sys

from PIL import Image, ImageDraw

WHITE = (255, 255, 255, 255)
RED = (229, 57, 53, 255)


def plane_poly(cx, cy, size, angle_deg):
    """Generic airliner silhouette, nose along +y in unit coordinates
    (mirrored about the fuselage), rotated clockwise by angle_deg."""
    half = [(0, 1.00), (0.07, 0.93), (0.09, 0.80), (0.09, 0.30), (0.95, -0.10),
            (0.95, -0.24), (0.09, -0.06), (0.07, -0.58), (0.36, -0.80),
            (0.36, -0.92), (0.04, -0.84), (0, -0.88)]
    pts = half + [(-x, y) for (x, y) in reversed(half[1:-1])]
    a = math.radians(angle_deg)
    out = []
    for x, y in pts:
        xr = x * math.cos(a) + y * math.sin(a)
        yr = -x * math.sin(a) + y * math.cos(a)
        out.append((cx + xr * size, cy - yr * size))
    return out


def icon(size, path):
    s = size * 4  # supersample
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = s // 14
    d.ellipse([s * 0.10, s * 0.66, s * 0.90, s * 0.90], outline=RED, width=w)
    d.polygon(plane_poly(s * 0.50, s * 0.42, s * 0.36, 0), fill=WHITE)
    im.resize((size, size), Image.LANCZOS).save(path)


if __name__ == "__main__":
    out = sys.argv[1]
    icon(96, out + "/app/src/main/res/drawable/ic_launcher.png")
    icon(256, out + "/docs/user_manual/plugin_icon.png")
