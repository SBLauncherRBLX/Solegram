#!/usr/bin/env python3
"""
Solegram icon generator.

The mark is a sun disc split into two halves by an S-shaped gap ("Sol" + "S"),
surrounded by short rays. Geometry is computed exactly (arcs only), and emitted as:
  * SVG files (for previews / store / docs)
  * Android VectorDrawable XML (adaptive icon foreground, monochrome, backgrounds)

Adaptive icon canvas is 108x108 with a 66dp safe circle (radius 33) in the centre.
"""
import math
import os
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)

C = 54.0          # centre of the 108x108 canvas
R = 20.4          # sun disc radius (before the channel erosion)
A = R / 2         # radius of the S arcs
G = 3.0           # width of the S channel
ROT = -28.0       # rotation of the whole mark (degrees)
RAY_IN, RAY_OUT, RAY_W = 24.6, 30.2, 3.6
RAYS = 8

def rot(p, deg=ROT):
    a = math.radians(deg)
    x, y = p
    return (x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a))

def P(p):
    x, y = rot(p)
    return f"{C + x:.3f},{C + y:.3f}"

def _arc(cx, cy, r, a0, a1, n=180):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

def halves():
    """Disc split by an S curve, each half eroded by G/2 so the channel has a constant width,
    then morphologically opened so the thin tails end in soft rounded tips."""
    from shapely.geometry import LineString, Point
    from shapely.ops import split
    disc = Point(0, 0).buffer(R, quad_segs=256)
    # Upper arc: around (0,-A), from the top (angle 270 deg, y down) clockwise to the centre (450 deg).
    # Lower arc: around (0, A), from the centre (270 deg) counter-clockwise to the bottom (90 deg).
    pts = [(-3.0, -R)] + _arc(0, -A, A, 270, 450) + _arc(0, A, A, 270, 90)[1:] + [(3.0, R)]
    parts = list(split(disc, LineString(pts)).geoms)
    left = next(g for g in parts if g.contains(Point(-0.8 * R, 0)))
    right = next(g for g in parts if g is not left)
    out = []
    for g in (left, right):
        g = g.buffer(-G / 2, quad_segs=64).buffer(-0.9, quad_segs=64).buffer(0.9, quad_segs=64)
        g = g.simplify(0.012, preserve_topology=True)
        out.append(g)
    return out

def poly_path(poly):
    coords = list(poly.exterior.coords)[:-1]
    return "M" + " L".join(P(c) for c in coords) + " Z"

def rays_path():
    d = []
    for i in range(RAYS):
        ang = math.radians(360.0 / RAYS * i + 22.5)
        a = (RAY_IN * math.cos(ang), RAY_IN * math.sin(ang))
        b = (RAY_OUT * math.cos(ang), RAY_OUT * math.sin(ang))
        d.append(f"M{P(a)} L{P(b)}")
    return " ".join(d)

_l, _r = halves()
LEFT, RIGHT, RAYS_D = poly_path(_l), poly_path(_r), rays_path()

# name -> (title, gradient stops [(offset, color)], foreground colour, secondary-half colour)
PALETTES = {
    "sunset":   ("Sunset",   [(0, "#FFB547"), (0.48, "#FF5A6E"), (1, "#7A3CF5")], "#FFFFFF", "#FFF1E6"),
    "gold":     ("Gold",     [(0, "#FFD36B"), (0.5, "#FFA41B"), (1, "#E2620A")], "#FFFFFF", "#FFF7E0"),
    "ocean":    ("Ocean",    [(0, "#5EF0FF"), (0.5, "#1FA2FF"), (1, "#3A3DE0")], "#FFFFFF", "#E8F7FF"),
    "aurora":   ("Aurora",   [(0, "#7CF2B0"), (0.5, "#1CB5E0"), (1, "#7A3CF5")], "#FFFFFF", "#ECFFF6"),
    "ember":    ("Ember",    [(0, "#FFC15E"), (0.5, "#FF5F3A"), (1, "#C2185B")], "#FFFFFF", "#FFEDE6"),
    "midnight": ("Midnight", [(0, "#2B3A67"), (0.6, "#161C3A"), (1, "#0B0E1F")], "#FFC857", "#FFE29A"),
    # 1.3.0
    "sakura":   ("Sakura",   [(0, "#FFD1DC"), (0.5, "#FF8FB1"), (1, "#E0569B")], "#FFFFFF", "#FFF0F5"),
    "mint":     ("Mint",     [(0, "#B8F5D5"), (0.5, "#3DDC97"), (1, "#0F9D8A")], "#FFFFFF", "#F0FFF8"),
    "lavender": ("Lavender", [(0, "#E3D2FF"), (0.5, "#B18CFF"), (1, "#6F4BD8")], "#FFFFFF", "#F6F0FF"),
    "neon":     ("Neon",     [(0, "#2A0845"), (0.55, "#16052B"), (1, "#050014")], "#FF4FD8", "#4FF3FF"),
    "snow":     ("Snow",     [(0, "#FFFFFF"), (0.6, "#F4F1F8"), (1, "#E6E1EF")], "#FF5A6E", "#FFB547"),
    "graphite": ("Graphite", [(0, "#5B5F6B"), (0.55, "#3A3D45"), (1, "#1F2126")], "#FFFFFF", "#D9DCE3"),
}

def svg_icon(palette, shape="squircle", size=512, with_bg=True, mono=False):
    title, stops, fg, fg2 = PALETTES[palette]
    if mono:
        fg = fg2 = "#000000"
    defs = ""
    body = ""
    if with_bg:
        st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
        defs = (f'<defs><linearGradient id="g_{palette}_{shape}" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">{st}</linearGradient>'
                f'<radialGradient id="w_{palette}_{shape}" cx="54" cy="54" r="40" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>')
        if shape == "circle":
            clip = f'<clipPath id="c_{palette}_{shape}"><circle cx="54" cy="54" r="54"/></clipPath>'
        elif shape == "squircle":
            clip = f'<clipPath id="c_{palette}_{shape}"><rect x="0" y="0" width="108" height="108" rx="30" ry="30"/></clipPath>'
        else:
            clip = f'<clipPath id="c_{palette}_{shape}"><rect x="0" y="0" width="108" height="108"/></clipPath>'
        defs = defs.replace("</defs>", clip + "</defs>")
        body += f'<g clip-path="url(#c_{palette}_{shape})"><rect width="108" height="108" fill="url(#g_{palette}_{shape})"/><circle cx="54" cy="54" r="40" fill="url(#w_{palette}_{shape})"/></g>'
    body += (f'<path d="{RAYS_D}" stroke="{fg}" stroke-width="{RAY_W}" stroke-linecap="round" fill="none"/>'
             f'<path d="{LEFT}" fill="{fg}"/><path d="{RIGHT}" fill="{fg2}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">'
            f'<title>Solegram {title}</title>{defs}{body}</svg>')

def vd_foreground(palette=None, mono=False):
    fg, fg2 = ("#FFFFFFFF", "#FFFFFFFF") if mono else (PALETTES[palette][2], PALETTES[palette][3])
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/gen_icons.py - Solegram mark -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{RAYS_D}"
        android:strokeColor="{fg}"
        android:strokeWidth="{RAY_W}"
        android:strokeLineCap="round"/>
    <path
        android:pathData="{LEFT}"
        android:fillColor="{fg}"/>
    <path
        android:pathData="{RIGHT}"
        android:fillColor="{fg2}"/>
</vector>
'''

def vd_background(palette):
    stops = PALETTES[palette][1]
    items = "\n".join(f'                    <item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/gen_icons.py - Solegram {PALETTES[palette][0]} background -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
{items}
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M54,54m-40,0a40,40 0,1 1,80 0a40,40 0,1 1,-80 0">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="54"
                android:gradientRadius="40">
                <item android:offset="0" android:color="#38FFFFFF"/>
                <item android:offset="1" android:color="#00FFFFFF"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

if __name__ == "__main__":
    vd = os.path.join(OUT, "drawable")
    svg = os.path.join(OUT, "svg")
    os.makedirs(vd, exist_ok=True)
    os.makedirs(svg, exist_ok=True)
    for name in PALETTES:
        open(os.path.join(vd, f"sg_icon_bg_{name}.xml"), "w").write(vd_background(name))
        open(os.path.join(vd, f"sg_icon_fg_{name}.xml"), "w").write(vd_foreground(name))
        for shape in ("squircle", "circle", "square"):
            open(os.path.join(svg, f"solegram_{name}_{shape}.svg"), "w").write(svg_icon(name, shape))
    open(os.path.join(vd, "sg_icon_fg_mono.xml"), "w").write(vd_foreground(mono=True))
    open(os.path.join(svg, "solegram_mark.svg"), "w").write(svg_icon("sunset", with_bg=False))
    print("generated into", OUT)


def vd_notification():
    """24dp status-bar icon: the mark cropped to its bounds (rays reach radius ~32 around the centre)."""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/gen_icons.py - Solegram notification icon -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="66"
    android:viewportHeight="66">
    <group android:translateX="-21" android:translateY="-21">
        <path
            android:pathData="{RAYS_D}"
            android:strokeColor="#FFFFFFFF"
            android:strokeWidth="{RAY_W + 0.6}"
            android:strokeLineCap="round"/>
        <path android:pathData="{LEFT}" android:fillColor="#FFFFFFFF"/>
        <path android:pathData="{RIGHT}" android:fillColor="#FFFFFFFF"/>
    </group>
</vector>
'''


if __name__ == "__main__":
    open(os.path.join(OUT, "drawable", "notification.xml"), "w").write(vd_notification())
