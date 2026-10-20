#!/usr/bin/env python3
"""Generates the venue reference marker.

The image is deterministic (fixed seed), high contrast, non-repetitive and rich in corners, which
is what ARCore Augmented Images and ARKit image detection need for reliable detection and pose
estimation.

Outputs:
  ar/arcore/src/main/assets/markers/venue_marker.png   (1024 px, loaded at runtime)
  docs/marker/venue_marker_print_20cm.pdf              (exactly 20 cm wide at 300 DPI)
  docs/marker/venue_marker.png                         (preview)

Usage: python3 tools/generate_marker.py
If you change the image or print size, update DefaultVenue in the app and bump the venue frame version.
"""
import os
import random
from PIL import Image, ImageDraw

SIZE = 1024
SEED = 20261020
PRINT_WIDTH_CM = 20.0
DPI = 300

def draw_marker(size: int) -> Image.Image:
    rnd = random.Random(SEED)
    img = Image.new("L", (size, size), 255)
    d = ImageDraw.Draw(img)
    s = size / 1024.0
    border = int(48 * s)
    d.rectangle([0, 0, size - 1, size - 1], fill=0)
    d.rectangle([border, border, size - 1 - border, size - 1 - border], fill=255)
    inner = (border + int(24 * s), size - border - int(24 * s))

    def rp():
        return rnd.uniform(*inner), rnd.uniform(*inner)

    for _ in range(70):
        kind = rnd.random()
        x, y = rp()
        r = rnd.uniform(18, 70) * s
        fill = 0 if rnd.random() < 0.8 else 255
        if kind < 0.35:
            w, h = rnd.uniform(0.4, 1.0) * r, rnd.uniform(0.4, 1.0) * r
            d.rectangle([x - w, y - h, x + w, y + h], fill=fill)
        elif kind < 0.65:
            pts = [(x + rnd.uniform(-r, r), y + rnd.uniform(-r, r)) for _ in range(3)]
            d.polygon(pts, fill=fill)
        elif kind < 0.85:
            d.ellipse([x - r * 0.6, y - r * 0.6, x + r * 0.6, y + r * 0.6], fill=fill)
        else:
            x2, y2 = rp()
            d.line([x, y, x2, y2], fill=0, width=max(2, int(rnd.uniform(6, 16) * s)))
    # Orientation cue: a solid block near the top-left corner (image "top" is the top edge).
    c = border + int(30 * s)
    d.rectangle([c, c, c + int(110 * s), c + int(110 * s)], fill=0)
    d.rectangle([c + int(30 * s), c + int(30 * s), c + int(80 * s), c + int(80 * s)], fill=255)
    return img

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets = os.path.join(root, "ar/arcore/src/main/assets/markers")
    docs = os.path.join(root, "docs/marker")
    os.makedirs(assets, exist_ok=True)
    os.makedirs(docs, exist_ok=True)
    draw_marker(SIZE).save(os.path.join(assets, "venue_marker.png"), optimize=True)
    draw_marker(SIZE).save(os.path.join(docs, "venue_marker.png"), optimize=True)
    print_px = round(PRINT_WIDTH_CM / 2.54 * DPI)
    # A4 page at 300 DPI with the marker centred at its exact physical size.
    a4 = Image.new("L", (round(21.0 / 2.54 * DPI), round(29.7 / 2.54 * DPI)), 255)
    marker = draw_marker(print_px)
    a4.paste(marker, ((a4.width - print_px) // 2, (a4.height - print_px) // 2))
    a4.save(os.path.join(docs, "venue_marker_print_20cm.pdf"), resolution=DPI)
    print(f"Marker written; print width {PRINT_WIDTH_CM} cm ({print_px}px @ {DPI} DPI)")

if __name__ == "__main__":
    main()
