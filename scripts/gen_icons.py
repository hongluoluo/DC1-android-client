#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Generate DC1 launcher PNG icons (green rounded square + white power symbol)."""
import os
from PIL import Image, ImageDraw

ROOT = r"J:\dc1-app\app\src\main\res"
BG = (27, 94, 32, 255)      # #1B5E20
WHITE = (255, 255, 255, 255)

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def draw_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = size / 24.0
    # background: rounded rect
    r = int(size * 0.18)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=BG)
    cx, cy = size / 2.0, 13.0 * u  # ring center slightly below middle
    ring_r = 8.4 * u
    w = max(2, int(2.6 * u))  # ring thickness
    bbox = [cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r]
    # full ring
    d.ellipse(bbox, outline=WHITE, width=w)
    # erase top gap of ring (bg-colored rect)
    gap_w = 4.6 * u
    d.rectangle([cx - gap_w, cy - ring_r - 2, cx + gap_w, cy - ring_r + w + 2], fill=BG)
    # stem (vertical bar)
    stem_w = max(2, int(2.2 * u))
    d.rounded_rectangle(
        [cx - stem_w / 2, 3.4 * u, cx + stem_w / 2, 13.6 * u],
        radius=stem_w / 2, fill=WHITE,
    )
    return img


for density, size in SIZES.items():
    out_dir = os.path.join(ROOT, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "ic_launcher.png")
    draw_icon(size).save(path, "PNG")
    print("saved", path, size, "px")
