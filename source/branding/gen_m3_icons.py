#!/usr/bin/env python3
"""
Solegram "Material You" icon set (3.3): the Solegram mark on tonal palettes in the manner of Material 3 Expressive -
a light container colour with large abstract shapes (scalloped "cookies", circles, rings, soft squares, waves) in
tones of the same hue, cut off by the edges of the icon, behind the mark in two tones (the sun in the palette's
primary tone, the message lines in a lighter accent tone). Each palette has its own composition; there is a dark
variant and a white one with a gradient sun.

The mark's geometry (a half sun rising over two message lines) is taken from the installed foreground
(res/drawable/sg_icon_fg_sunset.xml), so the set stays exactly the Solegram mark.

Shapes are composed for what a launcher shows of an adaptive icon: the middle 72 of the 108 units (18..90);
they run past those edges, so the icon's own mask cuts them. The PNG renders (legacy launchers, previews) show
that same middle part.

usage: python3 branding/gen_m3_icons.py   (writes res drawables, adaptive icons, mipmap PNGs and branding/png renders)
"""
import json
import math
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "TMessagesProj", "src", "main", "res")
OUT = os.path.join(HERE, "out", "m3")

src = open(os.path.join(RES, "drawable", "sg_icon_fg_sunset.xml")).read()
paths = re.findall(r'android:pathData="([^"]+)"', src)
LINES, SUN = paths[0], paths[1]


# region Shapes (path data in the 108-unit canvas)

def _poly(points):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"


def circle(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f} A{r:.2f},{r:.2f} 0 1 0 {cx + r:.2f},{cy:.2f} A{r:.2f},{r:.2f} 0 1 0 {cx - r:.2f},{cy:.2f} Z"


def cookie(cx, cy, r, amp, n, rot=0.0, steps=240):
    """A scalloped circle (Material 3's "cookie" shapes): n soft bumps of the given amplitude."""
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        rr = r + amp * math.cos(n * (t - math.radians(rot)))
        pts.append((cx + rr * math.cos(t), cy + rr * math.sin(t)))
    return _poly(pts)


def squircle(cx, cy, a, b, rot=0.0, p=4.0, steps=200):
    """A superellipse (a soft square or a pill), rotated by rot degrees."""
    pts = []
    c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    for i in range(steps):
        t = 2 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        x = a * math.copysign(abs(ct) ** (2 / p), ct)
        y = b * math.copysign(abs(st) ** (2 / p), st)
        pts.append((cx + x * c - y * s, cy + x * s + y * c))
    return _poly(pts)


def wave(y, amp, period, phase=0.0, bottom=108.0):
    """A band from a wavy edge at height y down to the bottom."""
    pts = [(x, y + amp * math.sin(2 * math.pi * (x / period) + phase)) for x in [i * 1.5 for i in range(73)]]
    return _poly(pts + [(108, bottom), (0, bottom)])


def ring(cx, cy, r, w):
    """A ring of width w (filled, as two circles of opposite winding)."""
    ro, ri = r + w / 2, r - w / 2
    return (f"M{cx - ro:.2f},{cy:.2f} A{ro:.2f},{ro:.2f} 0 1 0 {cx + ro:.2f},{cy:.2f} A{ro:.2f},{ro:.2f} 0 1 0 {cx - ro:.2f},{cy:.2f} Z "
            f"M{cx - ri:.2f},{cy:.2f} A{ri:.2f},{ri:.2f} 0 1 1 {cx + ri:.2f},{cy:.2f} A{ri:.2f},{ri:.2f} 0 1 1 {cx - ri:.2f},{cy:.2f} Z")

# endregion


# name -> (title, background top, background bottom, sun, lines, sun gradient end or None, [(shape path, colour)])
# Tones follow Material 3's tonal palettes: the container (tones 95/90) with shapes in tones 80-88 behind the
# primary (tone 40) and a lighter accent (tone 60-70); the dark one turns them round (tone 10 behind tone 80).
PALETTES = {
    "m3sky": ("Sky", "#F1F4FF", "#DDE4FF", "#2D57C8", "#7F9CF0", None, [
        (cookie(84, 88, 30, 3.0, 9, 10), "#BED0FF"),
        (circle(20, 20, 18), "#CCD9FF"),
        (circle(80, 30, 3.4), "#93ADF5"),
    ]),
    "m3sage": ("Sage", "#EFF8EB", "#D6ECCE", "#2F6A2E", "#76AE6C", None, [
        (circle(20, 94, 32), "#BCDDAE"),
        (ring(90, 22, 20, 6), "#A8D39A"),
        (circle(90, 22, 9), "#BFE0B3"),
    ]),
    "m3rose": ("Rose", "#FFF1F3", "#FFDCE2", "#9A2C4B", "#DE7590", None, [
        (cookie(54, 55, 30, 2.2, 12, 0), "#FFCCD6"),
        (squircle(16, 90, 28, 8, -38, 2.0), "#FFB3C3"),
        (circle(88, 22, 4), "#F59AB0"),
    ]),
    "m3apricot": ("Apricot", "#FFF4EB", "#FFDFC8", "#8C4513", "#DE8D4E", None, [
        (ring(18, 92, 42, 8), "#FFDCC1"),
        (ring(18, 92, 29, 8), "#FFC9A0"),
        (circle(18, 92, 16), "#FFB57E"),
        (circle(88, 22, 9), "#FFD3B2"),
    ]),
    "m3lilac": ("Lilac", "#F7F1FF", "#E8DEFF", "#5B3FAE", "#A18AEF", None, [
        (squircle(88, 22, 24, 24, 22, 4.0), "#D3C0FF"),
        (squircle(20, 88, 20, 20, -16, 4.0), "#C8B1FF"),
        (circle(30, 30, 3.4), "#B39BF5"),
    ]),
    "m3teal": ("Teal", "#EAF8F7", "#CDEDEA", "#00676A", "#4DAFAC", None, [
        (wave(77, 3.6, 26, 0.6), "#A6DDD8"),
        (wave(85, 3.2, 22, 2.4), "#80CCC6"),
        (circle(88, 24, 13), "#BCE6E2"),
    ]),
    "m3night": ("Night", "#272833", "#15161C", "#B4C4FF", "#6E80CB", None, [
        (ring(90, 92, 30, 10), "#343B60"),
        (circle(20, 22, 14), "#2E3354"),
        (circle(80, 28, 3), "#4F5B99"),
        (circle(32, 84, 2.2), "#424B7C"),
    ]),
    "m3paper": ("Paper", "#FFFFFF", "#EFF2F9", "#1D63F2", "#94AEFF", "#7A5CF6", [
        (cookie(90, 18, 26, 2.6, 8, 0), "#DAE4FF"),
        (circle(16, 92, 24), "#E6DEFF"),
        (circle(26, 28, 2.8), "#AFC1FF"),
    ]),
}

# launcher slots (activity-alias names in the manifest, mipmap names)
SLOTS = [
    ("m3sky", "icon_29_launcher", "MaterialSkyIcon"),
    ("m3sage", "icon_30_launcher", "MaterialSageIcon"),
    ("m3rose", "icon_31_launcher", "MaterialRoseIcon"),
    ("m3apricot", "icon_32_launcher", "MaterialApricotIcon"),
    ("m3lilac", "icon_33_launcher", "MaterialLilacIcon"),
    ("m3teal", "icon_34_launcher", "MaterialTealIcon"),
    ("m3night", "icon_35_launcher", "MaterialNightIcon"),
    ("m3paper", "icon_36_launcher", "MaterialPaperIcon"),
]


def vd_background(name):
    title, top, bottom, _, _, _, shapes = PALETTES[name]
    body = "\n".join(
        f'''    <path
        android:pathData="{d}"
        android:fillType="evenOdd"
        android:fillColor="{color}"/>''' for d, color in shapes)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/gen_m3_icons.py - Solegram Material You {title} background -->
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
                android:startX="54"
                android:startY="0"
                android:endX="54"
                android:endY="108">
                <item android:offset="0" android:color="{top}"/>
                <item android:offset="1" android:color="{bottom}"/>
            </gradient>
        </aapt:attr>
    </path>
{body}
</vector>
'''


def vd_foreground(name):
    title, _, _, sun, lines, sun_end, _ = PALETTES[name]
    if sun_end:
        sun_fill = f'''>
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="35.4"
                android:startY="36.8"
                android:endX="72.6"
                android:endY="56.6">
                <item android:offset="0" android:color="{sun}"/>
                <item android:offset="1" android:color="{sun_end}"/>
            </gradient>
        </aapt:attr>
    </path>'''
    else:
        sun_fill = f'''
        android:fillColor="{sun}"/>'''
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/gen_m3_icons.py - Solegram Material You {title} mark -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{LINES}"
        android:fillColor="{lines}"/>
    <path
        android:pathData="{SUN}"{sun_fill}
</vector>
'''


def svg_icon(name, shape):
    """The icon as a launcher shows it: the middle 72 units of the canvas, in the given mask."""
    title, top, bottom, sun, lines, sun_end, shapes = PALETTES[name]
    gid = f"{name}_{shape}"
    if shape == "circle":
        clip = '<circle cx="54" cy="54" r="36"/>'
    else:
        clip = '<rect x="18" y="18" width="72" height="72" rx="20" ry="20"/>'
    sun_grad = (f'<linearGradient id="s_{gid}" x1="35.4" y1="36.8" x2="72.6" y2="56.6" gradientUnits="userSpaceOnUse">'
                f'<stop offset="0" stop-color="{sun}"/><stop offset="1" stop-color="{sun_end}"/></linearGradient>') if sun_end else ""
    sun_fill = f"url(#s_{gid})" if sun_end else sun
    shapes_svg = "".join(f'<path d="{d}" fill="{color}" fill-rule="evenodd"/>' for d, color in shapes)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">'
            f'<title>Solegram {title}</title>'
            f'<defs><linearGradient id="b_{gid}" x1="54" y1="18" x2="54" y2="90" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient>{sun_grad}'
            f'<clipPath id="c_{gid}">{clip}</clipPath></defs>'
            f'<g clip-path="url(#c_{gid})"><rect width="108" height="108" fill="url(#b_{gid})"/>{shapes_svg}'
            f'<path d="{LINES}" fill="{lines}"/><path d="{SUN}" fill="{sun_fill}"/></g></svg>')


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Solegram Material You {title} launcher icon (generated by branding/gen_m3_icons.py) -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/sg_icon_bg_{name}" />
    <foreground android:drawable="@drawable/sg_icon_fg_{name}" />
    <monochrome android:drawable="@drawable/sg_icon_fg_mono" />
</adaptive-icon>
'''

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(os.path.join(HERE, "png"), exist_ok=True)
    jobs = []
    DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, slot, _alias in SLOTS:
        title = PALETTES[name][0]
        open(os.path.join(RES, "drawable", f"sg_icon_bg_{name}.xml"), "w").write(vd_background(name))
        open(os.path.join(RES, "drawable", f"sg_icon_fg_{name}.xml"), "w").write(vd_foreground(name))
        for suffix in ("", "_round"):
            open(os.path.join(RES, "mipmap-anydpi-v26", f"{slot}{suffix}.xml"), "w").write(ADAPTIVE.format(title=title, name=name))
        for shape in ("squircle", "circle"):
            open(os.path.join(OUT, f"solegram_{name}_{shape}.svg"), "w").write(svg_icon(name, shape))
        for d, size in DENS.items():
            jobs.append({"svg": os.path.join(OUT, f"solegram_{name}_squircle.svg"),
                         "out": os.path.join(RES, f"mipmap-{d}", f"{slot}.png"), "size": size, "pad": 0.06})
            jobs.append({"svg": os.path.join(OUT, f"solegram_{name}_circle.svg"),
                         "out": os.path.join(RES, f"mipmap-{d}", f"{slot}_round.png"), "size": size, "pad": 0.04})
        jobs.append({"svg": os.path.join(OUT, f"solegram_{name}_squircle.svg"),
                     "out": os.path.join(HERE, "png", f"solegram_{name}_512.png"), "size": 512, "pad": 0})
    jf = os.path.join(OUT, "jobs.json")
    json.dump(jobs, open(jf, "w"))
    subprocess.check_call(["node", os.path.join(HERE, "render_batch.js"), jf])
    print(f"installed {len(SLOTS)} Material You icons, rendered {len(jobs)} PNGs")
