#!/usr/bin/env python3
"""Draws the website's share image (og.png, 1200x630) and touch icon
(apple-touch-icon.png, 180x180) into site/.

Needs Pillow, fontTools and brotli (pip install pillow fonttools brotli).
It reads the site's own fonts from site/fonts, so the images match the page.
The remote is drawn the same way as the app icon in make_icons.py, and
site/favicon.svg is the same drawing by hand.

Run from the repository root:  python3 tools/make_site_images.py
"""
import os
import tempfile

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

SITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site")
FONTS = os.path.join(SITE, "fonts")

ICON_BG = (0x26, 0x2C, 0x3E)
BODY = (0xE8, 0xEA, 0xF0)
RING = (0x96, 0x9C, 0xAC)
BUTTONS = [(0xE5, 0x39, 0x35), (0x43, 0xA0, 0x47), (0xFD, 0xD8, 0x35), (0x1E, 0x88, 0xE5)]

PAPER = (0x14, 0x14, 0x0E)
INK = (0xEC, 0xEB, 0xE3)
MUTED = (0xB4, 0xB5, 0xA6)
DOTS = [(0xFF, 0x6A, 0x4D), (0x4F, 0xD5, 0x8A), (0xF6, 0xC9, 0x45), (0x6F, 0xA3, 0xFF)]

SCALE = 4


def ttf_from_woff2(name, tmp):
    """Pillow can't read woff2, so unpack a copy into a temporary .ttf."""
    font = TTFont(os.path.join(FONTS, name))
    font.flavor = None
    path = os.path.join(tmp, name.replace(".woff2", ".ttf"))
    font.save(path)
    return path


def remote(d, cx, cy, height):
    """The remote: body, d-pad ring, and the four colour buttons."""
    w = height * 0.42
    top = cy - height / 2
    d.rounded_rectangle([cx - w / 2, top, cx + w / 2, top + height], radius=w * 0.32, fill=BODY)
    pad_y = top + height * 0.30
    pr = w * 0.26
    d.ellipse([cx - pr, pad_y - pr, cx + pr, pad_y + pr], fill=RING)
    ir = pr * 0.42
    d.ellipse([cx - ir, pad_y - ir, cx + ir, pad_y + ir], fill=BODY)
    by = top + height * 0.62
    br = w * 0.08
    for i, colour in enumerate(BUTTONS):
        bx = cx + (i - 1.5) * w * 0.20
        d.ellipse([bx - br, by - br, bx + br, by + br], fill=colour)


def touch_icon(size=180):
    s = size * SCALE
    img = Image.new("RGB", (s, s), ICON_BG)
    remote(ImageDraw.Draw(img), s / 2, s / 2, s * 0.74)
    return img.resize((size, size), Image.LANCZOS)


def share_image(tmp):
    w, h = 1200, 630
    s = SCALE
    img = Image.new("RGB", (w * s, h * s), PAPER)
    d = ImageDraw.Draw(img)

    mono = ImageFont.truetype(ttf_from_woff2("space-mono-latin-700-normal.woff2", tmp), 40 * s)
    mono_small = ImageFont.truetype(ttf_from_woff2("space-mono-latin-400-normal.woff2", tmp), 28 * s)
    display = ImageFont.truetype(ttf_from_woff2("familjen-grotesk-latin-wght-normal.woff2", tmp), 92 * s)
    sub = ImageFont.truetype(ttf_from_woff2("familjen-grotesk-latin-wght-normal.woff2", tmp), 34 * s)
    try:  # the Familjen file is a variable font: ask for bold and regular
        display.set_variation_by_axes([700])
        sub.set_variation_by_axes([400])
    except Exception:
        pass

    left = 80 * s
    # four dots, then the wordmark
    for i, colour in enumerate(DOTS):
        x = left + i * 26 * s + 10 * s
        d.ellipse([x - 10 * s, 76 * s - 10 * s, x + 10 * s, 76 * s + 10 * s], fill=colour)
    d.text((left + 4 * 26 * s + 18 * s, 76 * s), "remapd", font=mono, fill=INK, anchor="lm")

    # headline
    d.text((left, 190 * s), "Every button on your", font=display, fill=INK, anchor="la")
    d.text((left, 190 * s + 104 * s), "TV remote, working.", font=display, fill=INK, anchor="la")
    d.text((left, 190 * s + 104 * s + 150 * s), "For Android TV and Google TV. No root.", font=sub, fill=MUTED, anchor="la")

    d.text((left, 560 * s), "remapd.gureet.ca", font=mono_small, fill=MUTED, anchor="lm")
    return img.resize((w, h), Image.LANCZOS)


def main():
    touch_icon().save(os.path.join(SITE, "apple-touch-icon.png"), optimize=True)
    with tempfile.TemporaryDirectory() as tmp:
        share_image(tmp).save(os.path.join(SITE, "og.png"), optimize=True)
    print("wrote site/apple-touch-icon.png and site/og.png")


if __name__ == "__main__":
    main()
