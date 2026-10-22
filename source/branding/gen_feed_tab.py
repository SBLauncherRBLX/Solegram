#!/usr/bin/env python3
"""Generates the Feed tab icon (res/raw/tab_feed.json), a Lottie in the format of Telegram's
main tab icons: 512x512, 60 fps, 6 frames. Frame 0 is the outline card with a picture and two
text lines; while the tab gets selected the card's hole closes, so the card fills up and the
picture and lines turn into cut-outs (even-odd fill). The tab plays it backwards on deselect."""
import json
import os

K = 0.5523  # bezier circle constant

CENTER = (256.0, 256.0)
OUTER = (84, 92, 428, 420, 76)          # left, top, right, bottom, radius
HOLE = (120, 128, 392, 384, 42)
PICTURE = (164, 172, 348, 248, 22)
LINE1 = (164, 266, 348, 294, 14)
LINE2 = (164, 312, 290, 340, 14)


def rrect(l, t, r, b, rad):
    """Rounded rect as 8 vertices with in/out tangents (relative), clockwise from the top edge."""
    rad = min(rad, (r - l) / 2, (b - t) / 2)
    c = rad * K
    v = [(l + rad, t), (r - rad, t), (r, t + rad), (r, b - rad), (r - rad, b), (l + rad, b), (l, b - rad), (l, t + rad)]
    # segment k -> k+1 runs through v[k] + o[k] and v[k+1] + i[k+1]; odd segments are the corner arcs
    i = [(-c, 0), (0, 0), (0, -c), (0, 0), (c, 0), (0, 0), (0, c), (0, 0)]
    o = [(0, 0), (c, 0), (0, 0), (0, c), (0, 0), (-c, 0), (0, 0), (0, -c)]
    # shift so that every vertex is in canvas coordinates relative to the layer centre
    cx, cy = CENTER
    v = [[round(x - cx, 3), round(y - cy, 3)] for x, y in v]
    return {"i": [[round(x, 3), round(y, 3)] for x, y in i], "o": [[round(x, 3), round(y, 3)] for x, y in o], "v": v, "c": True}


def lerp_rect(a, b, t):
    return tuple(a[k] + (b[k] - a[k]) * t for k in range(5))


def static_path(name, shape):
    return {"ind": 0, "ty": "sh", "ix": 1, "ks": {"a": 0, "k": rrect(*shape), "ix": 2}, "nm": name, "mn": "ADBE Vector Shape - Group", "hd": False}


def animated_path(name, frames):
    keys = []
    for idx, (t, shape) in enumerate(frames):
        key = {"t": t, "s": [rrect(*shape)]}
        if idx < len(frames) - 1:
            key["i"] = {"x": 0.7, "y": 1}
            key["o"] = {"x": 0.3, "y": 0}
        keys.append(key)
    return {"ind": 0, "ty": "sh", "ix": 1, "ks": {"a": 1, "k": keys, "ix": 2}, "nm": name, "mn": "ADBE Vector Shape - Group", "hd": False}


def collapsed_hole():
    cx, cy = CENTER
    return (cx, cy, cx, cy, 0)


def build():
    hole_frames = [(0, HOLE), (5, collapsed_hole())]
    # the picture swells a little while the card fills, like the native icons do
    pic_big = (PICTURE[0] - 6, PICTURE[1] - 6, PICTURE[2] + 6, PICTURE[3] + 6, PICTURE[4] + 4)
    items = [
        static_path("Card", OUTER),
        animated_path("Hole", hole_frames),
        animated_path("Picture", [(0, PICTURE), (2, pic_big), (5, PICTURE)]),
        static_path("Line 1", LINE1),
        static_path("Line 2", LINE2),
        {"ty": "fl", "c": {"a": 0, "k": [1, 1, 1, 1], "ix": 4}, "o": {"a": 0, "k": 100, "ix": 5}, "r": 2, "bm": 0,
         "nm": "Fill 1", "mn": "ADBE Vector Graphic - Fill", "hd": False},
        {"ty": "tr", "p": {"a": 0, "k": [0, 0], "ix": 2}, "a": {"a": 0, "k": [0, 0], "ix": 1}, "s": {"a": 0, "k": [100, 100], "ix": 3},
         "r": {"a": 0, "k": 0, "ix": 6}, "o": {"a": 0, "k": 100, "ix": 7}, "sk": {"a": 0, "k": 0, "ix": 4}, "sa": {"a": 0, "k": 0, "ix": 5},
         "nm": "Transform"},
    ]
    for n, it in enumerate(items[:5]):
        it["ind"] = n
        it["ix"] = n + 1
    layer = {
        "ddd": 0, "ind": 1, "ty": 4, "nm": "Feed", "sr": 1,
        "ks": {
            "o": {"a": 0, "k": 100, "ix": 11}, "r": {"a": 0, "k": 0, "ix": 10},
            "p": {"a": 0, "k": [256, 256, 0], "ix": 2}, "a": {"a": 0, "k": [0, 0, 0], "ix": 1},
            "s": {"a": 1, "k": [
                {"i": {"x": [0.7, 0.7, 0.7], "y": [1, 1, 1]}, "o": {"x": [0.3, 0.3, 0.3], "y": [0, 0, 0]}, "t": 0, "s": [100, 100, 100]},
                {"i": {"x": [0.7, 0.7, 0.7], "y": [1, 1, 1]}, "o": {"x": [0.3, 0.3, 0.3], "y": [0, 0, 0]}, "t": 2, "s": [94, 94, 100]},
                {"t": 6, "s": [100, 100, 100]},
            ], "ix": 6},
        },
        "ao": 0,
        "shapes": [{"ty": "gr", "it": items, "nm": "Feed", "np": 6, "cix": 2, "bm": 0, "ix": 1, "mn": "ADBE Vector Group", "hd": False}],
        "ip": 0, "op": 180, "st": 0, "bm": 0,
    }
    return {"tgs": 1, "v": "5.5.2", "fr": 60, "ip": 0, "op": 6, "w": 512, "h": 512, "nm": "Feed", "ddd": 0, "assets": [], "layers": [layer]}


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "TMessagesProj", "src", "main", "res", "raw", "tab_feed.json")
    with open(out, "w") as f:
        json.dump(build(), f, separators=(",", ":"))
    print("wrote", os.path.normpath(out))
