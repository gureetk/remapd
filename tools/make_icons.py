#!/usr/bin/env python3
"""Draws the app icon and the Android TV banner (needs Pillow).

    python3 tools/make_icons.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
BG = (38, 44, 62)
BODY = (232, 234, 240)
DPAD = (150, 156, 172)
COLOURS = [(229, 57, 53), (67, 160, 71), (253, 216, 53), (30, 136, 229)]
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SS = 4  # draw big, then scale down for smooth edges


def remote(draw, cx, cy, h):
    """A remote control h pixels tall, centred on (cx, cy)."""
    w = h * 0.42
    x0, y0 = cx - w / 2, cy - h / 2
    draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=w * 0.32, fill=BODY)
    r = w * 0.26  # the d-pad ring
    dy = y0 + h * 0.27
    draw.ellipse([cx - r, dy - r, cx + r, dy + r], fill=DPAD)
    draw.ellipse([cx - r * 0.42, dy - r * 0.42, cx + r * 0.42, dy + r * 0.42], fill=BODY)
    b = w * 0.16  # the four colour buttons
    gap = w * 0.2
    by = y0 + h * 0.66
    for i, colour in enumerate(COLOURS):
        bx = cx + (i - 1.5) * gap
        draw.ellipse([bx - b / 2 - 1, by - b / 2, bx + b / 2 + 1, by + b / 2 + b * 0.1], fill=colour)


def icon(size):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=s * 0.22, fill=BG)
    remote(d, s / 2, s / 2, s * 0.74)
    return img.resize((size, size), Image.LANCZOS)


def banner():
    w, h = 320 * SS, 180 * SS
    img = Image.new("RGBA", (w, h), BG + (255,))
    d = ImageDraw.Draw(img)
    remote(d, w * 0.2, h / 2, h * 0.74)
    title = ImageFont.truetype(FONT, 40 * SS)
    sub = ImageFont.truetype(FONT_REGULAR, 17 * SS)
    d.text((w * 0.36, h * 0.31), "remapd", font=title, fill=(255, 255, 255))
    d.text((w * 0.365, h * 0.6), "remote buttons", font=sub, fill=(190, 196, 212))
    return img.resize((320, 180), Image.LANCZOS)


def header():
    """The README header, drawn at twice its display size."""
    w, h = 1440, 400
    img = Image.new("RGBA", (w * SS // 2, h * SS // 2), BG + (255,))
    d = ImageDraw.Draw(img)
    s = SS // 2
    remote(d, 250 * s, h / 2 * s, 300 * s)
    title = ImageFont.truetype(FONT, 104 * s)
    sub = ImageFont.truetype(FONT_REGULAR, 36 * s)
    d.text((440 * s, 108 * s), "remapd", font=title, fill=(255, 255, 255))
    d.text((446 * s, 248 * s), "Every button on your TV remote, working.", font=sub, fill=(190, 196, 212))
    return img.resize((w, h), Image.LANCZOS)


def save(img, folder, name):
    path = os.path.join(ROOT, folder)
    os.makedirs(path, exist_ok=True)
    img.save(os.path.join(path, name), optimize=True)


if __name__ == "__main__":
    for folder, size in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        save(icon(size), folder, "ic_launcher.png")
    save(banner(), "drawable-xhdpi", "banner.png")
    docs = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "images")
    os.makedirs(docs, exist_ok=True)
    header().save(os.path.join(docs, "header.png"), optimize=True)
    print("icons written to", os.path.normpath(ROOT), "and the README header to", os.path.normpath(docs))
