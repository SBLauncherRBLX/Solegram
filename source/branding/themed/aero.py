#!/usr/bin/env python3
"""Solegram "Aero" launcher icon in the glossy nature-and-glass style of the late 2000s: a deep blue sky with soft
clouds, luminous ribbons of light sweeping across it, a sun with a lens flare and bokeh, clear water with ripples
and sparkles, glossy green leaves with dew drops, soap bubbles, and the mark as a piece of aqua glass with a big
gloss, a glowing rim and drops of water on it.

    python3 aero.py <out dir>   ->  bg.png fg.png (432 px, 108 dp)
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

W = 432
SS = 3
N = W * SS
U = N / 108.0
MARK_SCALE = 1.08


def to_px(x, y):
    return ((x - 54) * MARK_SCALE + 54) * U, ((y - 54) * MARK_SCALE + 54) * U


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    t = np.asarray(t, np.float32)
    if t.ndim:
        t = t[..., None]
    return np.asarray(a, np.float32) * (1 - t) + np.asarray(b, np.float32) * t


_G = None


def grid():
    global _G
    if _G is None:
        yy, xx = np.mgrid[0:N, 0:N].astype(np.float32) + 0.5
        _G = (xx / U, yy / U)          # in dp
    return _G


def softmin(a, b, k):
    m = np.minimum(a, b)
    return m - k * np.log(np.exp(-(a - m) / k) + np.exp(-(b - m) / k))


def blur(a, r_dp):
    im = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r_dp * U)), np.float32) / 255


def mark_distance():
    """Signed distance to the outline of the mark, in dp (positive inside)."""
    x, y = grid()
    k = MARK_SCALE
    cx, cy = [v / U for v in to_px(54, 55.395)]
    base = to_px(54, 56.635)[1] / U
    d = softmin(18.6 * k - np.hypot(x - cx, y - cy), base - y, 0.4 * k)
    for x0, x1, yc in ((38.345, 69.655, 61.44), (45.165, 62.835, 68.88)):
        ax, ay = to_px(x0, yc)
        bx, _ = to_px(x1, yc)
        qx = np.clip(x, ax / U, bx / U)
        d = np.maximum(d, 2.325 * k - np.hypot(x - qx, y - ay / U))
    return d


def over(rgb, col, a):
    """Paints colour col with coverage a over rgb (in place)."""
    a = np.clip(np.asarray(a, np.float32), 0, 1)[..., None]
    rgb[:] = rgb * (1 - a) + np.asarray(col, np.float32) * a


def add(rgb, col, a):
    a = np.clip(np.asarray(a, np.float32), 0, None)[..., None]
    rgb[:] = np.minimum(rgb + np.asarray(col, np.float32) * a, 255)


def bubble(rgb, alpha, cx, cy, r, strength=1.0):
    """A soap bubble: a clear body, an iridescent rim and two highlights. alpha (optional) collects coverage."""
    x, y = grid()
    dist = np.hypot(x - cx, y - cy)
    inside = smooth(r + 0.12, r - 0.12, dist)
    rim = np.exp(-((dist - r * 0.92) / (r * 0.08)) ** 2) * inside
    ang = np.arctan2(y - cy, x - cx)
    tint = mix((255, 255, 255), (150, 235, 255), 0.5 + 0.5 * np.sin(ang * 2 + 1))
    tint = mix(tint, (255, 205, 245), smooth(0.3, 1.0, np.sin(ang - 2.2)) * 0.7)
    tint = mix(tint, (210, 255, 190), smooth(0.5, 1.0, np.sin(ang + 0.9)) * 0.5)
    hl = np.exp(-(((x - (cx - r * 0.4)) / (r * 0.28)) ** 2 + ((y - (cy - r * 0.45)) / (r * 0.16)) ** 2)) * inside
    hl2 = np.exp(-(((x - (cx + r * 0.45)) / (r * 0.12)) ** 2 + ((y - (cy + r * 0.5)) / (r * 0.1)) ** 2)) * inside * 0.6
    over(rgb, (255, 255, 255), inside * 0.10 * strength)
    over(rgb, tint, rim * 0.85 * strength)
    over(rgb, (255, 255, 255), (hl + hl2) * strength)
    if alpha is not None:
        np.maximum(alpha, np.clip((inside * 0.10 + rim * 0.85 + hl + hl2) * strength, 0, 1), out=alpha)


def drop(rgb, cx, cy, r):
    """A drop of water resting on a surface: a darker lower rim, a light body, a bright point of light."""
    x, y = grid()
    dist = np.hypot((x - cx) / r, (y - cy) / (r * 0.92))
    inside = smooth(1.06, 0.94, dist)
    shade = smooth(0.4, 1.0, dist) * smooth(-0.2, 0.8, ((y - cy) / r - (x - cx) / r * 0.3))
    over(rgb, (255, 255, 255), inside * 0.28)
    over(rgb, (10, 70, 140), inside * shade * 0.45)
    lift = np.exp(-(((x - (cx - r * 0.35)) / (r * 0.24)) ** 2 + ((y - (cy - r * 0.38)) / (r * 0.2)) ** 2))
    over(rgb, (255, 255, 255), lift * inside)
    glow = np.exp(-(((x - (cx + r * 0.25)) / (r * 0.5)) ** 2 + ((y - (cy + r * 0.55)) / (r * 0.25)) ** 2)) * inside
    add(rgb, (120, 160, 120), glow * 0.5)


def leaf(rgb, base, tip, width, bend):
    """A glossy green leaf from base to tip: two curved edges, a lighter midrib, veins and a gloss."""
    x, y = grid()
    bx, by = base
    tx, ty = tip
    L = math.hypot(tx - bx, ty - by)
    ux, uy = (tx - bx) / L, (ty - by) / L
    vx, vy = -uy, ux
    s = ((x - bx) * ux + (y - by) * uy) / L          # 0 at the base, 1 at the tip
    t = ((x - bx) * vx + (y - by) * vy)              # across
    t = t - bend * L * np.sin(np.clip(s, 0, 1) * math.pi) * 0.12
    half = width * np.clip(np.sin(np.clip(s, 0, 1) * math.pi), 0, 1) ** 0.8 * (1 - 0.25 * s)
    inside = smooth(0.3, -0.3, np.abs(t) - half) * (s > 0) * (s < 1)
    side = np.clip(t / np.maximum(half, 1e-3), -1, 1)
    col = mix((60, 175, 40), (150, 230, 60), 0.5 + 0.5 * side)
    col = mix(col, (25, 110, 30), smooth(0.4, 1.0, np.abs(side)) * 0.5)
    rib = np.exp(-(t / 0.45) ** 2)
    col = mix(col, (200, 250, 140), rib * 0.75)
    veins = (0.5 + 0.5 * np.cos((s * 9 - np.abs(side) * 1.6) * 2 * math.pi)) ** 10 * smooth(0.1, 0.4, np.abs(side)) * 0.35
    col = mix(col, (190, 245, 130), veins)
    gloss = np.exp(-((side + 0.45) / 0.22) ** 2) * smooth(0.1, 0.4, s) * smooth(0.95, 0.6, s)
    col = mix(col, (245, 255, 220), gloss * 0.7)
    over(rgb, col, inside)
    return inside


def background():
    x, y = grid()
    horizon = 66.0
    # the sky: deep azure up top, glowing pale near the horizon
    rgb = mix((6, 88, 200), (70, 180, 255), smooth(0, 50, y))
    rgb = mix(rgb, (215, 245, 255), smooth(42, horizon, y))
    # soft cumulus clouds: blobs of light, shaded blue-grey underneath
    def cloud(blobs):
        dens = np.zeros_like(x)
        for cx, cy, r in blobs:
            dens = dens + np.exp(-(((x - cx) / r) ** 2 + ((y - cy) / (r * 0.7)) ** 2))
        return dens
    c1 = cloud([(4, 50, 7), (12, 46, 6), (20, 48, 5.5), (10, 53, 6), (27, 52, 4.5), (17, 55, 5)])
    c2 = cloud([(66, 32, 5), (74, 28, 6), (83, 31, 5.5), (92, 33, 5), (78, 35, 5.5), (100, 36, 4.5)])
    for c in (c1, c2):
        cov = smooth(0.35, 0.9, c)
        shade = smooth(1.4, 0.5, c) * 0.8
        over(rgb, mix((255, 255, 255), (175, 205, 238), shade), cov * 0.85)
    # luminous ribbons of light sweeping across the sky
    for (y0, amp, ph, w, col, a) in ((40, 9, 0.6, 2.2, (170, 255, 230), 0.55), (44, 11, 1.2, 1.2, (255, 255, 255), 0.7),
                                     (34, 7, 2.4, 3.2, (120, 230, 255), 0.35)):
        cy = y0 - amp * np.sin(x / 108 * math.pi * 1.4 + ph) - (x - 54) * 0.12
        band = np.exp(-((y - cy) / w) ** 2)
        add(rgb, col, band * a * 0.5)
    # the sun, high on the right, its rays, its flare and bokeh
    sx, sy = 84, 14
    sd = np.hypot(x - sx, y - sy)
    add(rgb, (255, 255, 240), np.exp(-(sd / 5) ** 2) * 1.6)
    add(rgb, (200, 240, 255), np.exp(-(sd / 20) ** 2) * 0.5)
    ang = np.arctan2(y - sy, x - sx)
    add(rgb, (255, 255, 255), (0.5 + 0.5 * np.cos(ang * 12)) ** 12 * smooth(60, 6, sd) * 0.35)
    for t, r, col in ((0.35, 3.0, (120, 255, 200)), (0.55, 1.8, (255, 200, 120)), (0.8, 4.5, (140, 200, 255)), (1.05, 2.4, (200, 160, 255))):
        fx, fy = sx + (40 - sx) * t, sy + (70 - sy) * t
        fd = np.hypot(x - fx, y - fy)
        add(rgb, col, smooth(r, r * 0.8, fd) * 0.16 + np.exp(-((fd - r * 0.9) / 0.35) ** 2) * 0.12)
    rng = np.random.default_rng(7)
    for _ in range(14):
        bx, by, br = rng.uniform(4, 104), rng.uniform(6, 60), rng.uniform(1.2, 3.4)
        bd = np.hypot(x - bx, y - by)
        add(rgb, (220, 245, 255), smooth(br, br * 0.7, bd) * rng.uniform(0.06, 0.14))
    # the water: a bright line at the horizon, clear aqua deepening forwards, ripples and sparkles
    water = y > horizon
    wcol = mix((60, 215, 235), (6, 110, 175), smooth(horizon, 108, y))
    ripple = np.sin((x * 0.9 + np.sin(y * 0.8) * 3) * 0.6 + y * 2.4) * np.sin(y * 1.7 + x * 0.15)
    wcol = mix(wcol, (210, 250, 255), smooth(0.55, 0.95, ripple) * 0.35 * smooth(108, horizon, y))
    # the sun's path on the water
    path = np.exp(-((x - sx + (y - horizon) * 0.25) / 6) ** 2) * smooth(0.2, 0.9, ripple)
    wcol = mix(wcol, (255, 255, 255), path * 0.5)
    rgb = np.where(water[..., None], wcol, rgb)
    add(rgb, (255, 255, 255), np.exp(-((y - horizon) / 0.5) ** 2) * 0.8)
    for _ in range(40):
        px_, py_ = rng.uniform(4, 104), rng.uniform(horizon + 2, 104)
        add(rgb, (255, 255, 255), np.exp(-(((x - px_) / 0.35) ** 2 + ((y - py_) / 0.15) ** 2)) * rng.uniform(0.6, 1.0))
    # glossy leaves in the corners, with dew
    leaf(rgb, (-6, 108), (30, 76), 7.5, 0.6)
    leaf(rgb, (2, 112), (40, 92), 6, -0.4)
    leaf(rgb, (114, 104), (78, 82), 7, -0.5)
    leaf(rgb, (110, 114), (84, 96), 5.5, 0.4)
    for cx, cy, r in ((16, 90, 1.5), (22, 84, 1.0), (96, 92, 1.3), (30, 97, 0.9)):
        drop(rgb, cx, cy, r)
    # bubbles
    for cx, cy, r in ((12, 28, 4.5), (100, 50, 3.6), (26, 62, 2.2), (93, 66, 2.6)):
        bubble(rgb, None, cx, cy, r, 0.9)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).resize((W, W), Image.LANCZOS)


def foreground():
    x, y = grid()
    d = mark_distance()
    mask = np.clip(d * U / (0.6 * SS) + 0.5, 0, 1)
    top, bot = 37.0, 72.0
    t = np.clip((y - top) / (bot - top), 0, 1)
    # the glass: deep azure at the top, aqua lower down, a fresh green glow at the very bottom (the grass below)
    gel = mix((0, 70, 190), (10, 170, 240), smooth(0, 0.85, t))
    gel = mix(gel, (120, 240, 170), smooth(0.75, 1.0, t) * 0.55)
    # light gathers inside along the lower edges
    gel = mix(gel, (200, 255, 255), np.exp(-((d - 1.2) / 1.0) ** 2) * smooth(0.3, 0.9, t) * 0.65)
    # a deep blue rim and a thin white edge
    gel = mix(gel, (0, 55, 150), np.exp(-(d / 0.55) ** 2) * 0.85)
    edge = np.exp(-((d - 0.25) / 0.18) ** 2)
    gel = mix(gel, (235, 252, 255), edge * smooth(60, 40, y) * 0.9)
    # the gloss: an arched highlight over the top of the dome, and a smaller one on each line
    k = MARK_SCALE
    cx, cy = [v / U for v in to_px(54, 55.395)]
    R = 18.6 * k
    rr = np.hypot((x - cx) / (R * 0.74), (y - (cy - R * 0.64)) / (R * 0.28))
    gloss_dome = smooth(1.0, 0.9, rr) * (0.25 + 0.75 * smooth(cy - R * 0.36, cy - R * 0.9, y)) * smooth(0.6, 1.4, d)
    gel = mix(gel, (255, 255, 255), gloss_dome * 0.8)
    for yc in (61.44, 68.88):
        ly = to_px(54, yc)[1] / U
        gl = smooth(0.3, 0.9, d) * smooth(ly + 0.2, ly - 1.6, y) * (y < ly + 0.2) * (y > ly - 2.7)
        gel = mix(gel, (255, 255, 255), gl * 0.55)
    # a diagonal streak of light across the dome
    streak = np.exp(-(((x - cx) * 0.6 + (y - cy) * 0.8 + 6) / 1.6) ** 2) * smooth(0.5, 2.0, d) * (y < cy)
    gel = mix(gel, (255, 255, 255), streak * 0.35)
    gel = np.clip(gel, 0, 255)
    # drops of water on the glass
    for dx, dy, r in ((61, 49, 1.5), (47, 52.5, 1.0), (66, 53, 0.8), (40.5, 61.4, 0.7)):
        drop(gel, dx, dy, r)
    out = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    m8 = Image.fromarray((mask * 255).astype(np.uint8))
    # a soft bright halo and a blue shadow, so it floats
    halo = m8.filter(ImageFilter.GaussianBlur(3.5 * U))
    out.alpha_composite(Image.merge('RGBA', [Image.new('L', (N, N), v) for v in (230, 250, 255)] + [halo.point(lambda v: v * 0.55)]))
    sh = Image.new('L', (N, N), 0)
    sh.paste(m8, (0, int(2.0 * U)))
    sh = sh.filter(ImageFilter.GaussianBlur(2.0 * U))
    out.alpha_composite(Image.merge('RGBA', [Image.new('L', (N, N), v) for v in (0, 50, 120)] + [sh.point(lambda v: v * 0.4)]))
    out.alpha_composite(Image.fromarray(np.dstack([gel, mask * 255]).astype(np.uint8), 'RGBA'))
    # bubbles rising past the mark
    b_rgb = np.zeros((N, N, 3), np.float32)
    b_a = np.zeros((N, N), np.float32)
    for bx, by, r in ((77, 41, 3.0), (81.5, 47.5, 1.7), (31, 44, 1.4)):
        bubble(b_rgb, b_a, bx, by, r, 1.0)
    rgb_b = np.where(b_a[..., None] > 0, b_rgb / np.maximum(b_a[..., None], 1e-3), 0)
    out.alpha_composite(Image.fromarray(np.dstack([np.clip(rgb_b, 0, 255), b_a * 255]).astype(np.uint8), 'RGBA'))
    return out.resize((W, W), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    background().save(os.path.join(out, 'bg.png'))
    foreground().save(os.path.join(out, 'fg.png'))


if __name__ == '__main__':
    main()
