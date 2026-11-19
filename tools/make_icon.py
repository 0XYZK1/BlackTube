#!/usr/bin/env python3
"""BlackTube icon generator.

Source artwork lives in assets/eye-source.png. The iris is replaced with a
play mark, then the launcher icons and the GitHub/release artwork are built
from that master.
"""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
RES = ROOT / "app" / "src" / "main" / "res"


def remove_iris(img):
    """Delete the white crescent iris and put a play mark in the pupil."""
    rgb = img.convert("RGB")
    marked = rgb.copy()
    ImageDraw.floodfill(marked, (480, 560), (255, 0, 255), thresh=40)

    r, g, b = marked.split()
    mask = ImageChops.multiply(
        r.point(lambda p: 255 if p > 240 else 0),
        ImageChops.multiply(
            g.point(lambda p: 255 if p < 15 else 0),
            b.point(lambda p: 255 if p > 240 else 0),
        ),
    )
    mask = mask.filter(ImageFilter.MaxFilter(5))
    rgb.paste((0, 0, 0), mask=mask)

    draw = ImageDraw.Draw(rgb)
    draw.polygon([(460, 405), (460, 575), (605, 490)], fill=(255, 255, 255))
    return rgb


def tight_square(img, threshold=25, fill=0.95):
    """Crop the artwork to a centred square with a little breathing room."""
    grey = img.convert("L").point(lambda p: 255 if p > threshold else 0)
    box = grey.getbbox()
    if not box:
        return img

    left, top, right, bottom = box
    side = int(max(right - left, bottom - top) / fill)
    cx = (left + right) // 2
    cy = (top + bottom) // 2
    x0 = max(0, cx - side // 2)
    y0 = max(0, cy - side // 2)
    return img.crop((x0, y0, x0 + side, y0 + side))


def transparent(img):
    """White artwork on transparency, black background removed."""
    rgb = img.convert("RGB")
    alpha = rgb.convert("L").point(lambda p: 0 if p < 18 else p)
    out = Image.new("RGBA", img.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def main():
    source = Image.open(ASSETS / "eye-source.png")
    master = tight_square(remove_iris(source))
    white = transparent(master)

    master.save(ASSETS / "icon.png")
    master.resize((512, 512), Image.LANCZOS).save(ASSETS / "icon-512.png")
    master.resize((192, 192), Image.LANCZOS).save(ASSETS / "icon-192.png")
    master.save(
        ASSETS / "icon.ico",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
    )

    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    foreground = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

    for dpi, px in legacy.items():
        folder = RES / ("mipmap-" + dpi)
        folder.mkdir(parents=True, exist_ok=True)

        square = master.resize((px, px), Image.LANCZOS)
        square.save(folder / "ic_launcher.png")

        round_icon = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        disc = Image.new("RGBA", (px, px), (0, 0, 0, 255))
        round_icon.paste(disc, (0, 0), circle_mask(px))
        eye = white.resize((int(px * 0.92), int(px * 0.92)), Image.LANCZOS)
        off = (px - eye.width) // 2
        round_icon.alpha_composite(eye, (off, off))
        round_icon.save(folder / "ic_launcher_round.png")

    for dpi, px in foreground.items():
        folder = RES / ("mipmap-" + dpi)
        folder.mkdir(parents=True, exist_ok=True)
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        eye = white.resize((int(px * 0.64), int(px * 0.64)), Image.LANCZOS)
        off = (px - eye.width) // 2
        canvas.alpha_composite(eye, (off, off))
        canvas.save(folder / "ic_launcher_foreground.png")

    print("icons written")


if __name__ == "__main__":
    main()
