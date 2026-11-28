"""Builds the 3D Solegram logo for the intro screen: TMessagesProj/src/main/assets/models/solegram_logo.binobj.

The logo's SVG (viewBox 34.9 36.295 38.2 35.41): a half-sun (radius 18.6, flat base with 1.24 rounded corners) over two
capsule stripes. Each part is extruded with a rounded bevel, centred on z, as the approved three.js preview did
(sun: depth 7, bevel 1.6 / 2.2; stripes: depth 5, bevel 1.1 / 1.6). Every shape is convex and its outline smooth, so
the caps are fans and the walls use the outline's own normals.

Format (Telegram's ObjLoader, big-endian): floats[positions] floats[uv] floats[normals] int corners, then per corner
three ints (position, uv, normal index). uv carries the colours: u = the part (0.1 sun, 0.5 stripe 1, 0.9 stripe 2),
v = 1 - gradient position (ObjLoader flips v back; 0 at the part's top, 1 at its bottom).
Run: python3 -I solegram/tools/make_logo_model.py
"""
import math, os, struct

SCALE = 1.65          # the logo to the size of Telegram's star (about ±33 wide)
CX, CY = 54.0, 54.0   # the logo's centre in SVG units

def to_model(x, y):
    return ((x - CX) * SCALE, -(y - CY) * SCALE)

def arc(cx, cy, r, a0, a1, n):
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / n), cy + r * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n)]

def sun_outline():
    # SVG y down. The half circle from the left base point over the top to the right one, then the small rounded
    # corners and the flat base back (counter-clockwise in model space, y up)
    pts = []
    pts += arc(54.0, 55.395, 18.6, math.pi, 2 * math.pi, 96)          # left → top → right (SVG angles, y down)
    pts += arc(71.36, 55.395, 1.24, 0, math.pi / 2, 6)                # right corner down to the base
    pts += [(71.36 - (71.36 - 36.64) * i / 24, 56.635) for i in range(4)]  # the flat base, right to left
    pts += arc(36.64, 55.395, 1.24, math.pi / 2, math.pi, 6)          # left corner up
    return pts

def capsule_outline(x0, x1, y_top, h):
    r = h / 2; cy = y_top + r
    pts = []
    pts += [(x0 + r + (x1 - x0 - 2 * r) * i / 4, y_top) for i in range(4)]      # top edge, left → right
    pts += arc(x1 - r, cy, r, -math.pi / 2, math.pi / 2, 16)                       # right cap
    pts += [(x1 - r - (x1 - x0 - 2 * r) * i / 4, y_top + h) for i in range(4)]  # bottom edge, right → left
    pts += arc(x0 + r, cy, r, math.pi / 2, 3 * math.pi / 2, 16)                    # left cap
    return pts

def model_outline(svg_pts):
    pts = [to_model(x, y) for x, y in svg_pts]
    # counter-clockwise in model space
    area = sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1] for i in range(len(pts)))
    if area < 0:
        pts.reverse()
    return pts

def outward_normals(pts):
    n = len(pts); out = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        e1 = (p1[0] - p0[0], p1[1] - p0[1]); e2 = (p2[0] - p1[0], p2[1] - p1[1])
        n1 = (e1[1], -e1[0]); n2 = (e2[1], -e2[0])   # right-hand normals of a CCW outline point outwards
        l1 = math.hypot(*n1) or 1; l2 = math.hypot(*n2) or 1
        nx = n1[0] / l1 + n2[0] / l2; ny = n1[1] / l1 + n2[1] / l2
        l = math.hypot(nx, ny) or 1
        out.append((nx / l, ny / l))
    return out

class Mesh:
    def __init__(self):
        self.pos = []; self.uv = []; self.nrm = []
    def tri(self, a, b, c):
        for p, n, t in (a, b, c):
            self.pos.append(p); self.nrm.append(n); self.uv.append(t)

def extrude(mesh, svg_pts, depth, bevel_size, bevel_thick, part_u, bevel_steps=6):
    pts = model_outline(svg_pts)
    nrm = outward_normals(pts)
    s = SCALE
    depth *= s; bevel_size *= s; bevel_thick *= s
    ys = [p[1] for p in pts]
    y_top, y_bot = max(ys) + bevel_size, min(ys) - bevel_size
    def uv(y):
        t = (y_top - y) / (y_top - y_bot)
        return (part_u, 1.0 - max(0.0, min(1.0, t)))
    # rings from the front cap's edge round the bevel, down the wall, round the back bevel
    rings = []   # (offset, z, nz-weight a) per ring
    for j in range(bevel_steps + 1):
        a = (math.pi / 2) * j / bevel_steps
        rings.append((bevel_size * math.sin(a), depth / 2 + bevel_thick * math.cos(a), a))
    for j in range(bevel_steps, -1, -1):
        a = (math.pi / 2) * j / bevel_steps
        rings.append((bevel_size * math.sin(a), -depth / 2 - bevel_thick * math.cos(a), math.pi - a))
    def ring_vertex(i, r):
        off, z, a = r
        p = pts[i]; n = nrm[i]
        x, y = p[0] + n[0] * off, p[1] + n[1] * off
        # the normal of an elliptic quarter: outward part ∝ thickness·sin, z part ∝ size·cos
        nx, ny, nz = n[0] * bevel_thick * math.sin(a), n[1] * bevel_thick * math.sin(a), bevel_size * math.cos(a)
        l = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
        return ((x, y, z), (nx / l, ny / l, nz / l), uv(y))
    N = len(pts)
    for k in range(len(rings) - 1):
        for i in range(N):
            i2 = (i + 1) % N
            a = ring_vertex(i, rings[k]); b = ring_vertex(i2, rings[k])
            c = ring_vertex(i2, rings[k + 1]); d = ring_vertex(i, rings[k + 1])
            mesh.tri(a, b, c); mesh.tri(a, c, d)
    # caps: fans from the centroid (all shapes are convex)
    cxm = sum(p[0] for p in pts) / N; cym = sum(p[1] for p in pts) / N
    zf = depth / 2 + bevel_thick; zb = -zf
    for i in range(N):
        i2 = (i + 1) % N
        p, q = pts[i], pts[i2]
        mesh.tri(((cxm, cym, zf), (0, 0, 1), uv(cym)), ((p[0], p[1], zf), (0, 0, 1), uv(p[1])), ((q[0], q[1], zf), (0, 0, 1), uv(q[1])))
        mesh.tri(((cxm, cym, zb), (0, 0, -1), uv(cym)), ((q[0], q[1], zb), (0, 0, -1), uv(q[1])), ((p[0], p[1], zb), (0, 0, -1), uv(p[1])))

mesh = Mesh()
extrude(mesh, sun_outline(), 7, 1.6, 2.2, 0.1)
extrude(mesh, capsule_outline(38.345, 69.655, 59.115, 4.65), 5, 1.1, 1.6, 0.5)
extrude(mesh, capsule_outline(45.165, 62.835, 66.555, 4.65), 5, 1.1, 1.6, 0.9)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "TMessagesProj", "src", "main", "assets", "models", "solegram_logo.binobj")
def index(values):
    table = {}; flat = []; idx = []
    for v in values:
        key = tuple(round(c, 4) for c in v)
        i = table.get(key)
        if i is None:
            i = table[key] = len(table); flat.extend(key)
        idx.append(i)
    return flat, idx
pos_f, pos_i = index(mesh.pos); uv_f, uv_i = index(mesh.uv); n_f, n_i = index(mesh.nrm)
with open(out, "wb") as f:
    def floats(values):
        f.write(struct.pack(">i", len(values)))
        f.write(struct.pack(">%df" % len(values), *values))
    floats(pos_f); floats(uv_f); floats(n_f)
    f.write(struct.pack(">i", len(pos_i)))
    f.write(b"".join(struct.pack(">iii", a, b, c) for a, b, c in zip(pos_i, uv_i, n_i)))
xs = [p[0] for p in mesh.pos]; ys = [p[1] for p in mesh.pos]; zs = [p[2] for p in mesh.pos]
print("corners", len(mesh.pos), "x %.1f..%.1f y %.1f..%.1f z %.1f..%.1f" % (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)),
      "bytes", os.path.getsize(out))
