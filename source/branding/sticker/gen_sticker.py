"""Solegram animated sticker: the logo (sun disk, rays, the S) as a Telegram .tgs (Lottie, 512x512, 60 fps, 3 s loop).

The disk hops with squash and stretch, the S spins once at the top of the hop, the rays turn slowly (a seamless
45 degrees per loop) and flare when it lands, sparkles pop around it, then a little happy wiggle.
"""
import gzip
import json
import math
import re
import sys

FR = 60
OP = 180
W = H = 512
SRC = '/home/user/Solegram/TMessagesProj/src/main/res/drawable/sg_icon_fg_sunset.xml'

R = 168            # (unused for size now)
K = 214 / 31.5     # icon units to px: the rays' tips reach 214 px from the centre
CX, CY = 256, 262  # logo centre at rest


def hexc(h, a=1.0):
    h = h.lstrip('#')
    return [int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255, a]


def st(v):
    return {'a': 0, 'k': v}


def ease(o=(0.33, 0.0), i=(0.67, 1.0)):
    return {'o': {'x': [o[0]], 'y': [o[1]]}, 'i': {'x': [i[0]], 'y': [i[1]]}}


def anim(keys, default_ease=None):
    """keys: [(t, value) or (t, value, ease_dict)]"""
    out = []
    for n, k in enumerate(keys):
        t, v = k[0], k[1]
        v = v if isinstance(v, list) else [v]
        kf = {'t': t, 's': v}
        if n < len(keys) - 1:
            e = k[2] if len(k) > 2 else (default_ease or ease())
            kf.update(e)
        out.append(kf)
    return {'a': 1, 'k': out}


def tr(p=(0, 0), a=(0, 0), s=(100, 100), r=0, o=100):
    return {'ty': 'tr', 'p': st(list(p)), 'a': st(list(a)), 's': st(list(s)), 'r': st(r), 'o': st(o),
            'sk': st(0), 'sa': st(0)}


def group(items, name='g', transform=None):
    return {'ty': 'gr', 'nm': name, 'it': items + [transform or tr()]}


def ellipse(w, h, p=(0, 0)):
    return {'ty': 'el', 'p': st(list(p)), 's': st([w, h]), 'd': 1}


def path(points, closed=True):
    return {'ty': 'sh', 'ks': st({'i': [[0, 0]] * len(points), 'o': [[0, 0]] * len(points),
                                   'v': [[round(x, 2), round(y, 2)] for x, y in points], 'c': closed})}


def smooth_path(points):
    """A closed path through the points with Catmull-Rom tangents (round shapes from few points)."""
    n = len(points)
    ins, outs = [], []
    for j in range(n):
        p0, p2 = points[(j - 1) % n], points[(j + 1) % n]
        tx, ty = (p2[0] - p0[0]) / 6, (p2[1] - p0[1]) / 6
        ins.append([round(-tx, 2), round(-ty, 2)])
        outs.append([round(tx, 2), round(ty, 2)])
    return {'ty': 'sh', 'ks': st({'i': ins, 'o': outs, 'v': [[round(x, 2), round(y, 2)] for x, y in points], 'c': True})}


def fill(color, o=100):
    return {'ty': 'fl', 'c': st(color), 'o': st(o) if not isinstance(o, dict) else o, 'r': 1}


def stroke(color, w, o=100):
    return {'ty': 'st', 'c': st(color), 'o': st(o), 'w': st(w), 'lc': 2, 'lj': 2}


def gfill(kind, s, e, stops, alphas=None):
    g = []
    for off, c in stops:
        cc = hexc(c)
        g += [off, cc[0], cc[1], cc[2]]
    if alphas:
        for off, a in alphas:
            g += [off, a]
    return {'ty': 'gf', 'o': st(100), 'r': 1, 's': st(list(s)), 'e': st(list(e)), 't': kind,
            'g': {'p': len(stops), 'k': st(g)}, 'h': st(0), 'a': st(0)}


def layer(ind, name, shapes, ks, parent=None, ty=4):
    lay = {'ddd': 0, 'ind': ind, 'ty': ty, 'nm': name, 'sr': 1, 'ks': ks, 'ao': 0, 'ip': 0, 'op': OP, 'st': 0, 'bm': 0}
    if ty == 4:
        lay['shapes'] = shapes
    if parent is not None:
        lay['parent'] = parent
    return lay


def ks(p=(0, 0)):
    return {'o': st(100), 'r': st(0), 'p': st([p[0], p[1], 0]), 'a': st([0, 0, 0]), 's': st([100, 100, 100])}


# --- the logo's shapes from the launcher icon ---------------------------------------------------------------
xml = open(SRC).read()
paths = re.findall(r'android:pathData="([^"]+)"', xml)
ray_data, s1_data, s2_data = paths[0], paths[1], paths[2]


def to_px(x, y):
    return ((float(x) - 54) * K, (float(y) - 54) * K)


rays = []
for seg in re.findall(r'M([\d.]+),([\d.]+) L([\d.]+),([\d.]+)', ray_data):
    rays.append([to_px(seg[0], seg[1]), to_px(seg[2], seg[3])])


def poly(d):
    return [to_px(x, y) for x, y in re.findall(r'([\d.]+),([\d.]+)', d)]


s_half_a = poly(s1_data)
s_half_b = poly(s2_data)

WHITE = hexc('#FFFFFF')
CREAM = hexc('#FFF1E6')

# --- timeline -----------------------------------------------------------------------------------------------
EIO = ease((0.42, 0), (0.58, 1))
EOUT = ease((0.2, 0.7), (0.4, 1))
EIN = ease((0.6, 0), (0.8, 0.4))

body_pos = anim([
    (0, [0, 0]), (10, [0, 0], EIO), (24, [0, -26], EOUT), (36, [0, -32], EIN), (48, [0, 0], EOUT),
    (58, [0, -5], EIO), (68, [0, 0], EIO), (180, [0, 0])])
body_scale = anim([
    (0, [100, 100]), (10, [109, 91], EOUT), (20, [93, 108], EIO), (34, [100, 100], EIO), (48, [113, 88], EOUT),
    (58, [96, 104], EIO), (70, [100, 100], EIO), (124, [100, 100], EIO), (132, [104, 96], EIO),
    (142, [98, 102], EIO), (152, [100, 100]), (180, [100, 100])])
body_rot = anim([
    (0, 0), (122, 0, EIO), (134, -8, EIO), (148, 7, EIO), (160, -3, EIO), (170, 0), (180, 0)])

s_rot = anim([
    (0, 0), (16, 0, ease((0.5, 0), (0.3, 1))), (54, 380, EIO), (68, 360), (180, 360)])
s_scale = anim([
    (0, [100, 100]), (16, [100, 100], EIO), (34, [112, 112], EIO), (56, [100, 100]), (180, [100, 100])])

rays_rot = anim([(0, 0, ease((0, 0), (1, 1))), (180, 45)])
rays_scale = anim([
    (0, [100, 100]), (46, [100, 100], EOUT), (54, [108, 108], EIO), (72, [100, 100], EIO), (132, [100, 100], EIO),
    (140, [104, 104], EIO), (152, [100, 100]), (180, [100, 100])])

shadow_scale = anim([
    (0, [100, 100]), (10, [108, 108], EIO), (36, [64, 64], EIN), (48, [112, 112], EOUT), (60, [100, 100]),
    (180, [100, 100])])
shadow_op = anim([(0, 100), (10, 100, EIO), (36, 55, EIN), (48, 100), (180, 100)])

layers = []
ind = 1
NULL = 1
layers_spec = []

# body: a null at the bottom of the disk, so squash and stretch rest on the ground
body = layer(NULL, 'body', None, {
    'o': st(0), 'r': body_rot, 'p': {'a': 1, 'k': [dict(k, s=[CX + k['s'][0], CY + 214 + k['s'][1], 0]) for k in body_pos['k']]},
    'a': st([0, 214, 0]), 's': body_scale}, ty=3)

GRAD = [(0, '#FFB547'), (0.48, '#FF5A6E'), (1, '#7A3CF5')]
GRAD_DEEP = [(0, '#FF7A59'), (0.5, '#F0477F'), (1, '#6A34E8')]
SPAN = 214


def gstroke(stops, w, s0, e0):
    g = []
    for off, c in stops:
        cc = hexc(c)
        g += [off, cc[0], cc[1], cc[2]]
    return {'ty': 'gs', 'o': st(100), 'w': st(w), 'lc': 2, 'lj': 2, 'ml': 4, 's': st(list(s0)), 'e': st(list(e0)), 't': 1,
            'g': {'p': len(stops), 'k': st(g)}, 'h': st(0), 'a': st(0)}


def ray_items(shadow=False):
    items = [path([a, b], closed=False) for a, b in rays]
    if shadow:
        items.append(stroke(hexc('#5A1E7A'), 3.6 * K, 18))
    else:
        items.append(gstroke(GRAD, 3.6 * K, (-SPAN, -SPAN), (SPAN, SPAN)))
    return items


def half(points, stops, light_at):
    """One half of the S: the gradient, then a soft light on its upper side for volume."""
    return [
        group([path(points), gfill(1, (-SPAN * 0.6, -SPAN * 0.6), (SPAN * 0.6, SPAN * 0.6), stops)], 'fill'),
        group([path(points), gfill(2, light_at, (light_at[0] + SPAN * 0.42, light_at[1]), [(0, '#FFFFFF'), (1, '#FFFFFF')],
                                   [(0, 0.5), (1, 0)])], 'light'),
    ]


s_items = [group(half(s_half_a, GRAD, (-SPAN * 0.22, -SPAN * 0.32)), 'S left'),
           group(half(s_half_b, GRAD_DEEP, (SPAN * 0.12, -SPAN * 0.22)), 'S right')]
s_shadow_items = [group([path(s_half_a), fill(hexc('#5A1E7A'), 16)], 'S left', tr(p=(0, 8))),
                  group([path(s_half_b), fill(hexc('#5A1E7A'), 16)], 'S right', tr(p=(0, 8)))]

rays_shadow = layer(5, 'rays shadow', [group(ray_items(True), 'rays', tr(p=(0, 7)))],
                    {'o': st(100), 'r': rays_rot, 'p': st([0, 0, 0]), 'a': st([0, 0, 0]), 's': rays_scale}, parent=NULL)
rays_layer = layer(6, 'rays', [group(ray_items(), 'rays')],
                   {'o': st(100), 'r': rays_rot, 'p': st([0, 0, 0]), 'a': st([0, 0, 0]), 's': rays_scale}, parent=NULL)
s_shadow = layer(7, 'S shadow', s_shadow_items,
                 {'o': st(100), 'r': s_rot, 'p': st([0, 0, 0]), 'a': st([0, 0, 0]), 's': s_scale}, parent=NULL)
s_layer = layer(8, 'S', s_items,
                {'o': st(100), 'r': s_rot, 'p': st([0, 0, 0]), 'a': st([0, 0, 0]), 's': s_scale}, parent=NULL)

# lottie draws the first layer on top
doc = {
    'tgs': 1, 'v': '5.7.4', 'fr': FR, 'ip': 0, 'op': OP, 'w': W, 'h': H, 'nm': 'Solegram', 'ddd': 0, 'assets': [],
    'layers': [s_layer, s_shadow, rays_layer, rays_shadow, body],
}

out = sys.argv[1] if len(sys.argv) > 1 else 'solegram'
raw = json.dumps(doc, separators=(',', ':'))
open(out + '.json', 'w').write(raw)
with gzip.open(out + '.tgs', 'wb', compresslevel=9) as f:
    f.write(raw.encode())
import os
print('json', len(raw), 'tgs', os.path.getsize(out + '.tgs'))
