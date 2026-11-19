"""Generate the BlackTube launcher icon.

Monochrome by design: a white play glyph inside a white ring on black.
Run from the repository root:

    python tools/make_icon.py
"""

import os
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
ASSETS = os.path.join(os.path.dirname(__file__), "..", "assets")
S = 1024
WHITE = (255, 255, 255, 255)


def glyph(size=S, background=True):
    base = Image.new("RGBA", (size, size), (0, 0, 0, 255) if background else (0, 0, 0, 0))

    cx = cy = size / 2.0
    r = size * 0.292

    # soft white halo behind the mark
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(10, 0, -1):
        rr = r * (1.0 + i * 0.014)
        gd.ellipse(
            [cx - rr, cy - rr, cx + rr, cy + rr],
            outline=(255, 255, 255, 8 * i),
            width=int(size * 0.014),
        )
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.026))
    base = Image.alpha_composite(base, glow)

    d = ImageDraw.Draw(base)

    # ring
    d.ellipse(
        [cx - r, cy - r, cx + r, cy + r],
        outline=(255, 255, 255, 235),
        width=max(2, int(size * 0.013)),
    )

    # play triangle
    s = size / 1024.0
    pts = [(406 * s, 325 * s), (406 * s, 699 * s), (720 * s, 512 * s)]
    d.polygon(pts, fill=WHITE)

    return base


def rounded(img):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


master = glyph(S, background=True)
master_fg = glyph(S, background=False)
master_round = rounded(master)

legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
foreground = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for dpi, px in legacy.items():
    folder = os.path.normpath(os.path.join(RES, "mipmap-" + dpi))
    os.makedirs(folder, exist_ok=True)
    master.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    master_round.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_round.png"))

for dpi, px in foreground.items():
    folder = os.path.normpath(os.path.join(RES, "mipmap-" + dpi))
    os.makedirs(folder, exist_ok=True)
    master_fg.resize((px, px), Image.LANCZOS).save(
        os.path.join(folder, "ic_launcher_foreground.png")
    )

os.makedirs(ASSETS, exist_ok=True)
master.save(os.path.join(ASSETS, "icon.png"))
master.resize((512, 512), Image.LANCZOS).save(os.path.join(ASSETS, "icon-512.png"))
master.resize((192, 192), Image.LANCZOS).save(os.path.join(ASSETS, "icon-192.png"))
master.save(os.path.join(ASSETS, "icon.ico"), sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
print("BlackTube icons written.")
