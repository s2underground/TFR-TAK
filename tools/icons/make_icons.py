"""Draws TFR-TAK's icons from simple primitives (original artwork):
an extruded airspace cylinder in white with a red restriction band."""
import sys
from PIL import Image, ImageDraw

def cylinder(size, path):
    s = size * 4  # supersample
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = s // 16
    l, r = s * 0.16, s * 0.84
    top, bot = s * 0.14, s * 0.70
    eh = s * 0.20
    white, red = (255, 255, 255, 255), (229, 57, 53, 255)
    # translucent red body
    d.rectangle([l, top + eh / 2, r, bot + eh / 2], fill=(229, 57, 53, 110))
    d.ellipse([l, bot, r, bot + eh], fill=(229, 57, 53, 110))
    # outline: sides, bottom arc, top ellipse
    d.line([l, top + eh / 2, l, bot + eh / 2], fill=white, width=w)
    d.line([r, top + eh / 2, r, bot + eh / 2], fill=white, width=w)
    d.arc([l, bot, r, bot + eh], 0, 180, fill=white, width=w)
    d.ellipse([l, top, r, top + eh], outline=white, width=w)
    # red ground band
    d.arc([l - w, bot - w, r + w, bot + eh + w], 0, 180, fill=red, width=w)
    im = im.resize((size, size), Image.LANCZOS)
    im.save(path)

if __name__ == "__main__":
    out = sys.argv[1]
    cylinder(96, out + "/app/src/main/res/drawable/ic_launcher.png")
    cylinder(256, out + "/docs/user_manual/plugin_icon.png")
