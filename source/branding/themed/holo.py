#!/usr/bin/env python3
"""Solegram "Holo" launcher icons, dark and light.

The mark (the exact geometry of sg_icon_fg_mono) as a piece of glossy white enamel: a bright, nearly flat body,
an oil-slick film of pink, peach, cyan and violet running just inside its edge, soft ink-blue shade pooling in
places along the rim, and a crisp light outline. Behind it, on the dark icon, sweeping violet bands of light.

    python3 holo.py <out dir>   ->  bg_dark.png fg_dark.png bg_light.png fg_light.png (432 px, 108 dp)
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W = 432            # 108 dp at xxxhdpi
SS = 3             # supersampling
N = W * SS
U = N / 108.0      # px per dp
MARK_SCALE = 1.12  # the mark a little larger than on the plain icon


def to_px(x, y):
    return ((x - 54) * MARK_SCALE + 54) * U, ((y - 54) * MARK_SCALE + 54) * U


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def gauss(a, radius):
    """Gaussian blur of a float field in full precision."""
    pad = int(radius * 3) + 1
    big = np.pad(a, pad, mode='edge')
    fy = np.fft.fftfreq(big.shape[0])[:, None]
    fx = np.fft.rfftfreq(big.shape[1])[None, :]
    k = np.exp(-2 * (math.pi * radius) ** 2 * (fx * fx + fy * fy))
    out = np.fft.irfft2(np.fft.rfft2(big) * k, big.shape)
    return out[pad:pad + a.shape[0], pad:pad + a.shape[1]].astype(np.float32)


def softmin(a, b, k):
    m = np.minimum(a, b)
    return m - k * np.log(np.exp(-(a - m) / k) + np.exp(-(b - m) / k))


def distance():
    """Signed distance to the outline of the mark in px (positive inside), from its exact shapes."""
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32) + 0.5
    k = MARK_SCALE * U
    # the dome: inside the circle and above its rounded base
    cx, cy = to_px(54, 55.395)
    R = 18.6 * k
    base = to_px(54, 56.635)[1]
    d = softmin(R - np.hypot(xx - cx, yy - cy), base - yy, 0.4 * k)
    # the two lines: round-ended bars
    for x0, x1, yc in ((38.345, 69.655, 61.44), (45.165, 62.835, 68.88)):
        ax, ay = to_px(x0, yc)
        bx, _ = to_px(x1, yc)
        qx = np.clip(xx, ax, bx)
        d = np.maximum(d, 2.325 * k - np.hypot(xx - qx, yy - ay))
    return d


PALETTE = [  # the film round the edge, by the direction the edge faces
    (0.36, 0.40, 1.00),   # blue
    (0.98, 0.50, 0.80),   # pink
    (1.00, 0.72, 0.52),   # peach
    (0.98, 0.52, 0.86),   # pink
    (0.62, 0.48, 1.00),   # violet
    (0.42, 0.88, 1.00),   # cyan
    (0.36, 0.40, 1.00),
]


def palette(t):
    t = (t % 1.0) * (len(PALETTE) - 1)
    i = np.floor(t).astype(int)
    f = (t - i)[..., None]
    f = f * f * (3 - 2 * f)
    p = np.array(PALETTE)
    return p[i] * (1 - f) + p[np.minimum(i + 1, len(PALETTE) - 1)] * f


def enamel(d, dark):
    """The colour of the mark from the distance to its outline."""
    k = MARK_SCALE * U
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32) / N
    ds = gauss(d, 0.6 * k)
    gy, gx = np.gradient(ds)
    ang = np.arctan2(gy, gx) / (2 * math.pi)            # which way the edge faces (towards the inside)
    # the film's colour wanders a little along the edge, like oil on water
    t = ang + 0.08 * np.sin(xx * 11 + 1.3) + 0.06 * np.sin(yy * 13 + 0.4) + 0.15
    film = palette(t)
    dd = d / k                                         # in dp, scaled with the mark
    # the body: bright white, a faint cool shade to the bottom right
    body = np.array([0.985, 0.985, 1.0]) - ((xx + yy - 0.9) * 0.10)[..., None] * np.array([1, 1, 0.6])
    if not dark:
        body = body * np.array([0.95, 0.94, 0.99])
    col = body
    # soft ink-blue shade pooling inside the rim where the edge faces right or down-left
    face = 0.5 + 0.5 * np.cos((ang - 0.5) * 2 * math.pi)
    face2 = 0.5 + 0.5 * np.cos((ang - 0.88) * 2 * math.pi)
    pool = np.maximum(face ** 3, 0.9 * face2 ** 5) * smooth(-0.3, 0.9, np.sin(yy * 15 + xx * 6 + 0.5))
    ink = (np.exp(-((dd - 1.6) / 1.4) ** 2) * pool * 1.0)[..., None]
    ink_col = np.array([0.14, 0.15, 0.52])
    col = col * (1 - ink) + ink_col * ink
    # the oil-slick band just inside the edge
    # in patches, not as an even line: here and there the white runs right up to the rim
    patch = 0.5 + 0.5 * np.sin(xx * 21 + yy * 6 + 1.0) * np.sin(yy * 17 - xx * 7 + 2.2)
    patch = smooth(0.25, 0.75, patch)
    wide = 0.45 + 0.5 * patch
    band = (np.exp(-((dd - 0.5 - wide * 0.5) / (0.35 + wide * 0.45)) ** 2) * (0.2 + 0.75 * patch))[..., None]
    col = col * (1 - band) + film * band
    # a crisp light outline
    rim = (smooth(0.35, 0.0, dd) * 0.95)[..., None]
    col = col * (1 - rim) + np.array([1.0, 1.0, 1.0]) * rim
    # a gloss to the top left of the dome
    gl = np.exp(-(((xx - 0.42) / 0.07) ** 2 + ((yy - 0.42) / 0.035) ** 2))[..., None] * 0.12
    col = col + gl
    return np.clip(col, 0, 1)


def background(dark):
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32) / N
    t = (xx + yy) / 2
    if dark:
        c0, c1, c2 = np.array([24, 18, 54]), np.array([11, 9, 26]), np.array([5, 4, 12])
    else:
        c0, c1, c2 = np.array([251, 250, 255]), np.array([234, 230, 246]), np.array([214, 207, 236])
    a = np.clip(t / 0.5, 0, 1)[..., None]
    b = np.clip((t - 0.5) / 0.5, 0, 1)[..., None]
    base = np.where((t < 0.5)[..., None], c0 * (1 - a) + c1 * a, c1 * (1 - b) + c2 * b)
    img = Image.fromarray(base.astype(np.uint8)).convert('RGBA')
    # sweeping bands of violet light
    bands = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(bands)
    s = N / 432

    def arc(cx, cy, r, a0, a1, col, w):  # angles in degrees, clockwise from the right
        d.arc(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), a0, a1, fill=col, width=int(w * s))
    # curving round the corners, in the same direction: soft rings of light, not a stroke
    if dark:
        arc(-40, -40, 290, 0, 90, (110, 80, 230, 150), 30)
        arc(-40, -40, 245, 0, 90, (70, 52, 175, 110), 14)
        arc(472, 472, 270, 180, 270, (90, 66, 205, 120), 34)
    else:
        arc(-40, -40, 290, 0, 90, (175, 155, 245, 125), 30)
        arc(-40, -40, 245, 0, 90, (255, 190, 230, 95), 14)
        arc(472, 472, 270, 180, 270, (165, 145, 240, 100), 34)
    bands = bands.filter(ImageFilter.GaussianBlur(12 * s))
    img = Image.alpha_composite(img, bands).convert('RGB')
    arr = np.asarray(img, np.float32)
    rng = np.random.default_rng(5)
    arr += (rng.random((N, N, 1)) - 0.5) * (6 if dark else 5)
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).resize((W, W), Image.LANCZOS)


def foreground(dark):
    d = distance()
    mask = np.clip(d / (0.6 * SS) + 0.5, 0, 1)            # anti-aliased edge
    rgb = enamel(d, dark)
    out = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    m8 = Image.fromarray((mask * 255).astype(np.uint8))
    if dark:
        # a soft white-violet glow round it
        glow = m8.filter(ImageFilter.GaussianBlur(3.5 * U))
        out.alpha_composite(Image.merge('RGBA', [Image.new('L', (N, N), v) for v in (190, 175, 255)] + [glow.point(lambda v: v * 0.5)]))
    else:
        sh = Image.new('L', (N, N), 0)
        sh.paste(m8, (0, int(2.0 * U)))
        sh = sh.filter(ImageFilter.GaussianBlur(3.0 * U))
        out.alpha_composite(Image.merge('RGBA', [Image.new('L', (N, N), v) for v in (50, 38, 110)] + [sh.point(lambda v: v * 0.5)]))
        # a faint violet line round it, so the white holds on the pale ground
        line = m8.filter(ImageFilter.MaxFilter(5))
        out.alpha_composite(Image.merge('RGBA', [Image.new('L', (N, N), v) for v in (120, 100, 200)] + [line.point(lambda v: v * 0.35)]))
    out.alpha_composite(Image.fromarray(np.dstack([rgb * 255, mask * 255]).astype(np.uint8), 'RGBA'))
    return out.resize((W, W), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    for v in ('dark', 'light'):
        background(v == 'dark').save(os.path.join(out, 'bg_%s.png' % v))
        foreground(v == 'dark').save(os.path.join(out, 'fg_%s.png' % v))


if __name__ == '__main__':
    main()
