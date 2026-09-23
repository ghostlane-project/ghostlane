#!/usr/bin/env python3
"""Render Ghostlane launcher icons (Pillow only).

The mark: a lane — two parallel edges on a 32° diagonal — with three dots
running along it, the front one lime and the two behind it fading into the
tile, a trail. Drawn in a 32-unit box centred at (16, 16) like the old favicon
mesh was, so the density and safe-zone arithmetic below is unchanged.

Outputs:
  androidApp/src/main/res/mipmap-*/ic_launcher.png          legacy launcher
  androidApp/src/main/res/mipmap-*/ic_launcher_{background,foreground}.png
                                                            adaptive layers (108dp)
  androidApp/src/main/res/mipmap-*/ic_qs_tile.png           monochrome QS tile
  androidApp/src/main/res/playstore_icon.png                512 store icon
  desktopApp/appIcons/{LinuxIcon.png,WindowsIcon.ico,MacosIcon.icns}

Usage: python3 tools/render-appicons.py [repo-root]
"""
from PIL import Image, ImageDraw
import json
import os
import sys

SS = 8  # supersampling factor

BG = "#07080D"
LIME = "#B5F23D"
INDIGO = "#6675FF"
EDGE = "#2A2D42"

# Density buckets: legacy launcher px, adaptive layer px (108dp), QS tile px.
DENSITIES = {
    "hdpi": (72, 162, 36),
    "xhdpi": (96, 216, 48),
    "xxhdpi": (144, 324, 72),
    "xxxhdpi": (192, 432, 96),
}

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def _blend(colour: str, alpha: float) -> str:
    """`colour` laid over the tile at `alpha`, as an opaque colour: the legacy
    tile and the adaptive foreground both sit on BG, and ImageDraw replaces
    pixels rather than compositing, so a real alpha would punch a hole."""
    c = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
    b = tuple(int(BG[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02X%02X%02X" % tuple(round(b[i] + (c[i] - b[i]) * alpha) for i in range(3))


# The lane axis: 32° up and to the right, half-length LANE_HALF; the two edges
# sit ±LANE_OFFSET from it. Dots along the axis at LANE_DOTS (position in units
# of LANE_HALF, radius in favicon units, opacity).
#
# The desktop tray draws the same mark in code (LaneMarkPainter in
# sharedUI/src/jvmMain/.../desktop/DesktopTrayIcon.kt): change the two together.
LANE_ANGLE_DEG = 32.0
LANE_HALF = 11.0
LANE_OFFSET = 4.2
LANE_STROKE = 1.5
LANE_DOTS = ((-0.78, 1.6, 0.30), (-0.22, 2.0, 0.62), (0.50, 2.6, 1.0))


def _mesh(d: ImageDraw.ImageDraw, u: float, cx: float, cy: float, mono: bool) -> None:
    """Draw the lane glyph. `u` = one favicon viewBox unit; (cx, cy) = glyph centre."""
    import math

    ang = math.radians(LANE_ANGLE_DEG)
    dx, dy = math.cos(ang), -math.sin(ang)
    px, py = -dy, dx
    width = max(1, round(LANE_STROKE * u))
    edge = "#FFFFFF" if mono else EDGE

    for side in (-1, 1):
        ox, oy = side * px * LANE_OFFSET * u, side * py * LANE_OFFSET * u
        p0 = (cx - dx * LANE_HALF * u + ox, cy - dy * LANE_HALF * u + oy)
        p1 = (cx + dx * LANE_HALF * u + ox, cy + dy * LANE_HALF * u + oy)
        d.line([p0, p1], fill=edge, width=width)
        r = width / 2
        for (x, y) in (p0, p1):
            d.ellipse([x - r, y - r, x + r, y + r], fill=edge)

    for index, (t, radius, alpha) in enumerate(LANE_DOTS):
        x = cx + dx * LANE_HALF * u * t
        y = cy + dy * LANE_HALF * u * t
        front = index == len(LANE_DOTS) - 1
        if mono:
            colour = (255, 255, 255, round(255 * alpha))
        else:
            colour = LIME if front else _blend(INDIGO, alpha)
        r = radius * u
        d.ellipse([x - r, y - r, x + r, y + r], fill=colour)


def tile(size: int) -> Image.Image:
    """Legacy/store icon: dark rounded tile with the mesh glyph."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = s / 32.0
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=7 * u, fill=BG)
    _mesh(d, u, s / 2, s / 2, mono=False)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_background(size: int) -> Image.Image:
    """Adaptive background layer: flat brand colour, the system applies the mask."""
    return Image.new("RGBA", (size, size), BG)


# Ink bounds of the lane glyph in favicon units, from the geometry above: the
# far corners of the two edges (half-length 11, offset 4.2, half-stroke 0.75)
# reach x ±12.3 and y ±10.1 around the centre, and the farthest ink from the
# centre is 12.5 units. The glyph is symmetric about (16, 16).
INK_SPAN = 24.6
INK_HEIGHT = 20.3
INK_CENTRE_Y = 16.0
INK_RADIUS = 12.5

# Adaptive icons are masked to a circle inscribed in the inner 72dp of the 108dp
# canvas, so the ink must fit that circle by its farthest point, not its width.
ADAPTIVE_SAFE_DIAMETER = (72.0 / 108.0) * 0.98
ADAPTIVE_INK_FRACTION = ADAPTIVE_SAFE_DIAMETER * INK_SPAN / (2 * INK_RADIUS)


def _glyph_only(size: int, fraction: float, mono: bool) -> Image.Image:
    """Glyph on transparency, its ink filling `fraction` of the canvas."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = (s * fraction) / INK_SPAN
    # shift down so the ink box — not the nominal box — is centred
    _mesh(d, u, s / 2, s / 2 + (16.0 - INK_CENTRE_Y) * u, mono=mono)
    return img.resize((size, size), Image.LANCZOS)


def adaptive_foreground(size: int) -> Image.Image:
    """Adaptive foreground: glyph inside the circular safe zone, transparent bg."""
    return _glyph_only(size, fraction=ADAPTIVE_INK_FRACTION, mono=False)


def qs_tile(size: int) -> Image.Image:
    """Quick Settings tile: monochrome glyph on transparency (system tints it)."""
    return _glyph_only(size, fraction=0.90, mono=True)


def ios_icon(size: int) -> Image.Image:
    """iOS app icon: square, fully opaque, no rounded corners.

    iOS applies its own superellipse mask, and an alpha channel gets an App Store
    submission rejected — so this is RGB with the corners left square.
    """
    s = size * SS
    img = Image.new("RGB", (s, s), BG)
    d = ImageDraw.Draw(img)
    u = (s * 0.72) / INK_SPAN
    _mesh(d, u, s / 2, s / 2 + (16.0 - INK_CENTRE_Y) * u, mono=False)
    return img.resize((size, size), Image.LANCZOS)


def save(img: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print(f"wrote {path} {img.size}")


def main(root: str) -> None:
    res = os.path.join(root, "androidApp/src/main/res")

    for dpi, (legacy_px, adaptive_px, tile_px) in DENSITIES.items():
        base = os.path.join(res, f"mipmap-{dpi}")
        save(tile(legacy_px), os.path.join(base, "ic_launcher.png"))
        save(adaptive_background(adaptive_px), os.path.join(base, "ic_launcher_background.png"))
        save(adaptive_foreground(adaptive_px), os.path.join(base, "ic_launcher_foreground.png"))
        save(qs_tile(tile_px), os.path.join(base, "ic_qs_tile.png"))

    # Wire the adaptive icon so Android 8+ uses the masked layers.
    xml_dir = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(xml_dir, exist_ok=True)
    xml_path = os.path.join(xml_dir, "ic_launcher.xml")
    with open(xml_path, "w") as fh:
        fh.write(ADAPTIVE_XML)
    print(f"wrote {xml_path}")

    save(tile(512), os.path.join(res, "playstore_icon.png"))

    icons = os.path.join(root, "desktopApp/appIcons")
    save(tile(512), os.path.join(icons, "LinuxIcon.png"))

    master = tile(1024)
    ico_path = os.path.join(icons, "WindowsIcon.ico")
    master.save(
        ico_path,
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    print(f"wrote {ico_path}")

    icns_path = os.path.join(icons, "MacosIcon.icns")
    master.save(icns_path)
    print(f"wrote {icns_path}")

    # iOS: single 1024 icon in the asset catalog (Xcode 14+ single-size form).
    appiconset = os.path.join(root, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
    save(ios_icon(1024), os.path.join(appiconset, "AppIcon1024.png"))

    with open(os.path.join(root, "iosApp/iosApp/Assets.xcassets/Contents.json"), "w") as fh:
        json.dump({"info": {"author": "xcode", "version": 1}}, fh, indent=2)
        fh.write("\n")
    with open(os.path.join(appiconset, "Contents.json"), "w") as fh:
        json.dump(
            {
                "images": [
                    {
                        "filename": "AppIcon1024.png",
                        "idiom": "universal",
                        "platform": "ios",
                        "size": "1024x1024",
                    }
                ],
                "info": {"author": "xcode", "version": 1},
            },
            fh,
            indent=2,
        )
        fh.write("\n")
    print("wrote iOS asset catalog")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
