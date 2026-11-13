#!/usr/bin/env python3
"""Solegram "Retro desktop" launcher icon: a window of an old desktop operating system on flat teal, drawn pixel
for pixel at 2 px per dp the way screens of the time did it: grey bevels, a navy-to-blue title bar with the app's
name and its three buttons, a menu bar, and the Solegram mark in the white client area, with the arrow pointer
resting on the window.

    python3 retro_window.py <out dir>   ->  bg.png fg.png (432 px: the 216 px art scaled 2x, nearest neighbour)
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

G = 216
FONT_BOLD = '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf'
FONT = '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf'

TEAL = (0, 128, 128)
FACE = (192, 192, 192)
WHITE = (255, 255, 255)
LIGHT = (223, 223, 223)
SHADE = (128, 128, 128)
BLACK = (0, 0, 0)
NAVY = (0, 0, 128)
BLUE = (16, 132, 208)
ORANGE = (242, 70, 44)
ORANGE_HI = (255, 120, 92)
PEACH = (255, 160, 132)


def bevel(d, x0, y0, x1, y1, raised=True):
    """The two-line bevel of the time round a rectangle (inclusive corners)."""
    if raised:
        o_tl, o_br, i_tl, i_br = LIGHT, BLACK, WHITE, SHADE
    else:
        o_tl, o_br, i_tl, i_br = SHADE, WHITE, BLACK, LIGHT
    d.line((x0, y0, x1 - 1, y0), fill=o_tl)
    d.line((x0, y0, x0, y1 - 1), fill=o_tl)
    d.line((x0, y1, x1, y1), fill=o_br)
    d.line((x1, y0, x1, y1), fill=o_br)
    d.line((x0 + 1, y0 + 1, x1 - 2, y0 + 1), fill=i_tl)
    d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 2), fill=i_tl)
    d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=i_br)
    d.line((x1 - 1, y0 + 1, x1 - 1, y1 - 1), fill=i_br)


def mark_pixels(w, h):
    """The mark rasterised to w x h pixels: (dome, lines) masks, threshold at half coverage."""
    s = 16
    bw, bh = w * s, h * s
    sx, sy = bw / 37.2, bh / 34.41

    def p(x, y):
        return (x - 35.4) * sx, (y - 36.795) * sy
    dome = Image.new('L', (bw, bh), 0)
    d = ImageDraw.Draw(dome)
    cx, cy = p(54, 55.395)
    d.pieslice((cx - 18.6 * sx, cy - 18.6 * sy, cx + 18.6 * sx, cy + 18.6 * sy), 180, 360, fill=255)
    x0, y0 = p(35.4, 55.395 - 1.24)
    x1, y1 = p(72.6, 56.635)
    d.rectangle((x0, y0, x1, y1), fill=255)
    lines = Image.new('L', (bw, bh), 0)
    d = ImageDraw.Draw(lines)
    for a, b, top, bot in ((36.02, 71.98, 59.115, 63.765), (42.84, 65.16, 66.555, 71.205)):
        q0, q1 = p(a, top), p(b, bot)
        d.rounded_rectangle((q0[0], q0[1], q1[0], q1[1]), 2.325 * sy, fill=255)
    return [m.resize((w, h), Image.BOX).point(lambda v: 255 if v >= 128 else 0) for m in (dome, lines)]


def paint_mark(im, ox, oy, w, h, small=False):
    px = im.load()
    dome, lines = mark_pixels(w, h)
    dp, lp = dome.load(), lines.load()
    for y in range(h):
        for x in range(w):
            if dp[x, y]:
                px[ox + x, oy + y] = ORANGE + (255,)
            elif lp[x, y]:
                px[ox + x, oy + y] = PEACH + (255,)
    if not small:
        # a highlight along the top left of the dome and a darker edge at its right, as icons of the time had
        for y in range(h):
            for x in range(w):
                if not dp[x, y]:
                    continue
                if (y > 0 and not dp[x, y - 1] or x > 0 and not dp[x - 1, y]) and x < w * 0.5:
                    px[ox + x, oy + y] = ORANGE_HI + (255,)
                elif x < w - 1 and not dp[x + 1, y] and x > w * 0.55:
                    px[ox + x, oy + y] = (190, 40, 24, 255)


ARROW = [
    "#",
    "##",
    "#.#",
    "#..#",
    "#...#",
    "#....#",
    "#.....#",
    "#......#",
    "#.......#",
    "#........#",
    "#.....#####",
    "#..#..#",
    "#.# #..#",
    "##  #..#",
    "#    #..#",
    "     #..#",
    "      ##",
]


def background():
    return Image.new('RGB', (G, G), TEAL)


def foreground():
    im = Image.new('RGBA', (G, G), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.fontmode = '1'                                   # crisp text, no smoothing, as on the screens of the time
    x0, y0, x1, y1 = 43, 50, 172, 162
    d.rectangle((x0, y0, x1, y1), fill=FACE)
    bevel(d, x0, y0, x1, y1, True)
    # the title bar
    tx0, ty0, tx1, ty1 = x0 + 3, y0 + 3, x1 - 3, y0 + 17
    for x in range(tx0, tx1 + 1):
        t = (x - tx0) / (tx1 - tx0)
        d.line((x, ty0, x, ty1), fill=tuple(round(NAVY[i] + (BLUE[i] - NAVY[i]) * t) for i in range(3)))
    paint_mark(im, tx0 + 3, ty0 + 2, 12, 11, small=True)
    d.text((tx0 + 18, ty0 + 1), 'Solegram', font=ImageFont.truetype(FONT_BOLD, 11), fill=WHITE)
    # its buttons: minimise, maximise, close
    bw, bh = 13, 12
    by0 = ty0 + 1
    for i, kind in enumerate(('close', 'max', 'min')):
        bx1 = tx1 - 2 - i * (bw + 1) - (2 if i else 0)
        bx0 = bx1 - bw + 1
        d.rectangle((bx0, by0, bx1, by0 + bh - 1), fill=FACE)
        bevel(d, bx0, by0, bx1, by0 + bh - 1, True)
        cx, cy = (bx0 + bx1) // 2, by0 + bh // 2 - 1
        if kind == 'close':
            for k in range(-3, 4):
                for dx in (0, 1):
                    d.point((cx + k + dx, cy + k), fill=BLACK)
                    d.point((cx - k + dx, cy + k), fill=BLACK)
        elif kind == 'max':
            d.rectangle((cx - 4, cy - 3, cx + 4, cy + 3), outline=BLACK)
            d.line((cx - 4, cy - 2, cx + 4, cy - 2), fill=BLACK)
        else:
            d.rectangle((cx - 3, cy + 2, cx + 2, cy + 3), fill=BLACK)
    # the menu bar
    font = ImageFont.truetype(FONT, 11)
    mx = tx0 + 3
    my = ty1 + 2
    for word in ('File', 'Edit', 'View', 'Help'):
        d.text((mx, my), word, font=font, fill=BLACK)
        w = int(d.textlength(word, font=font))
        d.line((mx, my + 12, mx + 5, my + 12), fill=BLACK)   # the access key, underlined
        mx += w + 8
    # the client area: sunken and white, the mark in the middle
    cx0, cy0, cx1, cy1 = x0 + 3, my + 16, x1 - 3, y1 - 3
    d.rectangle((cx0, cy0, cx1, cy1), fill=WHITE)
    bevel(d, cx0, cy0, cx1, cy1, False)
    mw, mh = 58, 54
    paint_mark(im, (cx0 + cx1 + 1) // 2 - mw // 2, (cy0 + cy1 + 1) // 2 - mh // 2, mw, mh)
    # the pointer, resting on the window's lower right
    px = im.load()
    ax, ay = 146, 136
    for j, row in enumerate(ARROW):
        for i, ch in enumerate(row):
            if ch == '#':
                px[ax + i, ay + j] = BLACK + (255,)
            elif ch == '.':
                px[ax + i, ay + j] = WHITE + (255,)
    # a hard drop shadow to the lower right of the window, like the pop-ups of the time
    shadow = Image.new('RGBA', (G, G), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rectangle((x0 + 4, y0 + 4, x1 + 4, y1 + 4), fill=(0, 64, 64, 255))
    shadow.alpha_composite(im)
    return shadow


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    background().resize((432, 432), Image.NEAREST).save(os.path.join(out, 'bg.png'))
    foreground().resize((432, 432), Image.NEAREST).save(os.path.join(out, 'fg.png'))


if __name__ == '__main__':
    main()
