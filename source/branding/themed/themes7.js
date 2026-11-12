// Solegram themed launcher icons, set 7 (3.3): the retro desktop, manga, frost and spin icons redrawn closer to
// their themes. Homages to styles only: no names, logos, characters or pictures of any particular work.

function markAt(g, scale, x, y, fn) { // draws fn() with the mark scaled about its centre and moved to (x, y)
  g.save(); g.translate(x, y); g.scale(scale, scale); g.translate(-cx, -(T + B) / 2); fn(); g.restore();
}
function rrect(x, y, w, h, r) { const p = new Path2D(); p.roundRect(x, y, w, h, r); return p; }
function inkText(g, text, x, y, size, rot, fill, outline, ow) {
  g.save(); g.translate(x, y); g.rotate(rot); g.font = `900 ${size}px "WenQuanYi Zen Hei"`; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.lineJoin = 'round'; g.lineWidth = ow; g.strokeStyle = outline; g.strokeText(text, 0, 0); g.fillStyle = fill; g.fillText(text, 0, 0); g.restore();
}
function toneDots(g, path, x0, y0, x1, y1, step, rOf, color) { // a screentone inside path, dot radius from rOf(x, y)
  within(g, path, () => { g.fillStyle = color;
    for (let y = y0; y < y1; y += step) for (let x = x0; x < x1; x += step) { const r = rOf(x, y); if (r > 0.04) { g.beginPath(); g.arc(x + ((Math.round(y / step)) % 2) * step / 2, y, Math.min(r, step * 0.62), 0, 7); g.fill(); } } });
}
function hatch(g, path, x0, y0, x1, y1, gap, angle, width, color, mask) { // parallel ink strokes, mask(x, y) keeps them
  within(g, path, () => { g.strokeStyle = color; g.lineWidth = width; g.lineCap = 'round';
    const dx = Math.cos(angle), dy = Math.sin(angle), len = 120;
    for (let k = -120; k < 120; k += gap) { const ox = 54 - dy * k, oy = 54 + dx * k; g.beginPath(); let on = false;
      for (let t = -len / 2; t <= len / 2; t += 0.6) { const x = ox + dx * t, y = oy + dy * t; const keep = x > x0 && x < x1 && y > y0 && y < y1 && mask(x, y);
        if (keep && !on) { g.moveTo(x, y); on = true; } else if (keep) g.lineTo(x, y); else on = false; } g.stroke(); } });
}

// ------------------------------------------------------------------------------------------------ retro desktop
// An early 2000s desktop: the wallpaper's green hill under a blue sky, a blue taskbar with a green start button,
// and a window of that era - its blue title bar with the three buttons, the red close one - holding the mark.
THEMES.xp = {
  bg(g) {
    const n = noise2(101), n2 = noise2(131);
    const hill = (x) => 66 + 10 * Math.sin((x + 10) / 36 * Math.PI) - 0.0035 * (x - 54) * (x - 54);
    pixels(g, (x, y) => {
      const h = hill(x);
      if (y < h) {
        let c = mixc([18, 92, 214], [128, 184, 238], Math.pow(y / h, 1.3));
        const cloud = smooth(0.54, 0.74, fbm(n, x / 20, y / 11, 5)) * (1 - smooth(48, 62, y) * 0.5);
        const shade = fbm(n2, x / 8, y / 6, 3);
        c = mixc(c, [255 - 20 * shade, 255 - 16 * shade, 255 - 6 * shade], cloud);
        return [c[0], c[1], c[2]];
      }
      const d = y - h;
      let c = mixc([126, 196, 56], [44, 128, 26], Math.min(1, d / 30));
      const blades = n(x * 2.6, y * 0.6) - 0.5, patch = fbm(n2, x / 13, y / 9, 3) - 0.5;
      c = c.map((v, i) => v + blades * 22 + patch * 28 * (i === 1 ? 1.2 : 0.8));
      const rim = Math.exp(-d / 1.3) * 36;
      return [clamp255(c[0] + rim * 0.5), clamp255(c[1] + rim), clamp255(c[2] + rim * 0.2)];
    });
    // the taskbar along the bottom, the start button at its left, the tray at its right
    const bar = new Path2D(); bar.rect(0, 84, 108, 24);
    const tb = g.createLinearGradient(0, 84, 0, 92); tb.addColorStop(0, '#3c81f3'); tb.addColorStop(0.15, '#2a6ae6'); tb.addColorStop(0.6, '#2358d6'); tb.addColorStop(1, '#1941a5');
    g.fillStyle = tb; g.fill(bar);
    g.fillStyle = 'rgba(255,255,255,0.35)'; g.fillRect(0, 84, 108, 0.5);
    const tray = new Path2D(); tray.rect(76, 84.5, 32, 23.5);
    const tr = g.createLinearGradient(0, 84, 0, 92); tr.addColorStop(0, '#1aa2f0'); tr.addColorStop(1, '#0e72c9'); g.fillStyle = tr; g.fill(tray);
    g.fillStyle = 'rgba(0,30,90,0.6)'; g.fillRect(76, 84.5, 0.5, 23.5);
    const start = new Path2D(); start.moveTo(0, 84); start.lineTo(38, 84); start.bezierCurveTo(43, 84, 44.5, 87.5, 44.5, 90); start.bezierCurveTo(44.5, 92.5, 43, 96, 38, 96); start.lineTo(0, 96); start.closePath();
    const sg = g.createLinearGradient(0, 84, 0, 96); sg.addColorStop(0, '#5fc24d'); sg.addColorStop(0.45, '#379b27'); sg.addColorStop(1, '#2a7d1d');
    g.fillStyle = sg; g.fill(start);
    gloss(g, start, 84.2, 0.45);
    g.strokeStyle = 'rgba(10,50,10,0.6)'; g.lineWidth = 0.4; g.stroke(start);
    // a four-pane window mark on the start button (a plain grid, no logo) and a little clock in the tray
    for (const [x, y, c] of [[25.2, 86.6, '#ffffff'], [27.6, 86.6, '#ffffff'], [25.2, 89, '#ffffff'], [27.6, 89, '#ffffff']]) { g.fillStyle = c; g.globalAlpha = 0.9; g.fillRect(x, y, 2, 2); }
    g.globalAlpha = 1;
    g.fillStyle = 'rgba(255,255,255,0.95)'; g.font = '700 3.4px sans-serif'; g.fillText('12:00', 80, 89.4);
  },
  fg(g) {
    const x0 = 27, y0 = 26, w = 54, h = 50, tbh = 8;
    const win = rrect(x0, y0, w, h, [3, 3, 0.8, 0.8]);
    shadowUnder(g, win, 1.2, 2, 1.6, 'rgba(0,20,60,0.45)');
    // the frame and the title bar: deep blue with a light top edge
    const fr = g.createLinearGradient(0, y0, 0, y0 + tbh); fr.addColorStop(0, '#3d95ff'); fr.addColorStop(0.12, '#0a5fe8'); fr.addColorStop(0.55, '#0058ee'); fr.addColorStop(0.85, '#0347d2'); fr.addColorStop(1, '#0a3fb8');
    g.fillStyle = fr; g.fill(win);
    within(g, win, () => { g.fillStyle = '#0a50dc'; g.fillRect(x0, y0 + tbh, w, h - tbh); });
    gloss(g, rrect(x0, y0, w, tbh, [3, 3, 0, 0]), y0 + 0.2, 0.35);
    // the content: a pale pane
    const pane = new Path2D(); pane.rect(x0 + 1.6, y0 + tbh, w - 3.2, h - tbh - 1.6);
    g.fillStyle = '#f6f4ec'; g.fill(pane);
    // the title: a tiny icon and a line of "text"
    markAt(g, 0.12, x0 + 4.6, y0 + tbh / 2, () => { g.fillStyle = '#ffd54a'; g.fill(sun); g.fill(lines); });
    g.fillStyle = 'rgba(255,255,255,0.95)'; g.fillRect(x0 + 8, y0 + 3.4, 15, 1.3);
    // the three buttons: minimize, maximize, close
    const bw = 5.6, by = y0 + 1.2, bh = 5.6;
    const btn = (bx, top, bottom, glyph) => {
      const b = rrect(bx, by, bw, bh, 1.1);
      const gr = g.createLinearGradient(0, by, 0, by + bh); gr.addColorStop(0, top); gr.addColorStop(1, bottom); g.fillStyle = gr; g.fill(b);
      g.strokeStyle = 'rgba(255,255,255,0.85)'; g.lineWidth = 0.4; g.stroke(b);
      g.strokeStyle = '#ffffff'; g.fillStyle = '#ffffff'; g.lineWidth = 0.75; g.lineCap = 'round'; glyph(bx, by);
    };
    const xs = x0 + w - 1.8 - bw;
    btn(xs, '#f39c7a', '#d4411c', (bx, by) => { g.beginPath(); g.moveTo(bx + 1.7, by + 1.7); g.lineTo(bx + bw - 1.7, by + bh - 1.7); g.moveTo(bx + bw - 1.7, by + 1.7); g.lineTo(bx + 1.7, by + bh - 1.7); g.stroke(); });
    btn(xs - bw - 0.8, '#6fa6ff', '#2a62e2', (bx, by) => { g.lineWidth = 0.5; g.strokeRect(bx + 1.5, by + 1.5, bw - 3, bh - 3); g.fillRect(bx + 1.5, by + 1.5, bw - 3, 0.7); });
    btn(xs - 2 * bw - 1.6, '#6fa6ff', '#2a62e2', (bx, by) => { g.fillRect(bx + 1.5, by + bh - 2.1, 2.4, 0.7); });
    // the mark in the window, glossy as the buttons of that era
    markAt(g, 0.78, 54, y0 + tbh + (h - tbh - 1.6) / 2 + 0.6, () => {
      shadowUnder(g, both, 0.4, 0.9, 0.7, 'rgba(0,30,80,0.35)');
      within(g, sun, () => { const gr = g.createLinearGradient(0, sunTop, 0, sunBot); gr.addColorStop(0, '#62a8ff'); gr.addColorStop(0.55, '#2563d8'); gr.addColorStop(1, '#173f9c'); g.fillStyle = gr; g.fillRect(0, 0, 108, 108); });
      within(g, lines, () => { const gr = g.createLinearGradient(0, 59, 0, 71.2); gr.addColorStop(0, '#74d65b'); gr.addColorStop(0.5, '#3a9f2b'); gr.addColorStop(1, '#23701a'); g.fillStyle = gr; g.fillRect(0, 0, 108, 108); });
      within(g, sun, () => { const cap = new Path2D(); cap.ellipse(cx, sunTop + 6.2, sunR * 0.82, 6.6, 0, 0, Math.PI * 2);
        const gr = g.createLinearGradient(0, sunTop, 0, sunTop + 12); gr.addColorStop(0, 'rgba(255,255,255,0.85)'); gr.addColorStop(1, 'rgba(255,255,255,0.05)'); g.fillStyle = gr; g.fill(cap); });
      gloss(g, lines, 59.2, 0.55); gloss(g, lines, 66.6, 0.55);
      g.lineJoin = 'round'; g.lineWidth = 0.8; g.strokeStyle = '#0f2f78'; g.stroke(sun); g.strokeStyle = '#1a5512'; g.stroke(lines);
    });
  },
};

// ------------------------------------------------------------------------------------------------ manga
// A black and white manga page: a tilted panel with focus lines rushing at the mark, screentone gradients in the
// gutters, a jagged shout balloon and a big sound effect breaking out over the panel's border.
THEMES.manga = {
  bg(g) {
    const n = noise2(301);
    // the page: paper with a screentone that darkens towards the bottom
    pixels(g, (x, y) => {
      let v = 248 - (fbm(n, x / 3, y / 3, 3) - 0.5) * 12;
      const p = 1.6, u = (x + y) / Math.SQRT2 / p, w = (x - y) / Math.SQRT2 / p;
      const du = u - Math.round(u), dw = w - Math.round(w), dd = Math.sqrt(du * du + dw * dw);
      if (dd < 0.08 + 0.42 * smooth(30, 100, y + x * 0.25)) v = 26;
      return [v, v, v];
    });
    // the panel, tilted, with a thick border; inside it focus lines leaving the middle white
    g.save(); g.translate(54, 54); g.rotate(-0.06);
    const panel = new Path2D(); panel.rect(-31, -28, 62, 58);
    g.fillStyle = '#fbfbf8'; g.fill(panel);
    within(g, panel, () => {
      const r = rng(311);
      for (let i = 0; i < 260; i++) {
        const a = r() * Math.PI * 2, inner = 21 + r() * 9, w = 0.18 + r() * 0.9;
        const ax = Math.cos(a), ay = Math.sin(a), nx = -ay, ny = ax;
        g.fillStyle = '#0d0d0d'; g.beginPath(); g.moveTo(ax * inner, ay * inner);
        g.lineTo(ax * 60 + nx * w * 2.4, ay * 60 + ny * w * 2.4); g.lineTo(ax * 60 - nx * w * 2.4, ay * 60 - ny * w * 2.4); g.closePath(); g.fill();
      }
    });
    g.lineWidth = 1.6; g.strokeStyle = '#0b0b0b'; g.stroke(panel);
    g.restore();
    // the shout balloon at the top right, cut by the edge
    const r2 = rng(17), pts = [], N = 14;
    for (let i = 0; i < N * 2; i++) { const a = i / (N * 2) * Math.PI * 2; const rr = i % 2 ? 7 + r2() * 1.5 : 11 + r2() * 2.5; pts.push([86 + Math.cos(a) * rr * 1.2, 24 + Math.sin(a) * rr * 0.85]); }
    const balloon = new Path2D(); balloon.moveTo(...pts[0]); for (const p of pts) balloon.lineTo(...p); balloon.closePath();
    g.fillStyle = '#ffffff'; g.fill(balloon); g.lineJoin = 'miter'; g.lineWidth = 1.1; g.strokeStyle = '#0b0b0b'; g.stroke(balloon);
    inkText(g, '!?', 84, 25, 8, 0.08, '#0b0b0b', '#ffffff', 0.01);
  },
  fg(g) {
    // the mark in ink: the sun in a tone that deepens down and to the right under a white highlight, the lines in tone
    const tone = (path, top, bottom) => toneDots(g, path, 30, top - 2, 78, bottom + 2, 1.05, (x, y) => 0.12 + 0.5 * smooth(top, bottom + 4, y + (x - cx) * 0.35), '#101010');
    g.fillStyle = '#fbfbf8'; g.fill(both);
    tone(sun, sunTop, sunBot);
    within(g, sun, () => { // a solid shadow band and the highlight
      g.fillStyle = '#101010'; g.beginPath(); g.ellipse(cx + 8, sunBot + 3, sunR * 0.95, 9, -0.2, 0, Math.PI * 2); g.fill();
      g.fillStyle = '#fbfbf8'; g.beginPath(); g.ellipse(cx - 6.5, sunTop + 6.5, 7, 3.6, -0.35, 0, Math.PI * 2); g.fill();
    });
    hatch(g, sun, 30, sunTop, 80, sunBot, 1.1, -0.9, 0.3, '#101010', (x, y) => x > cx + 4 && y < sunBot - 4);
    tone(lines, 59, 71.3);
    g.lineJoin = 'round'; g.strokeStyle = '#0a0a0a'; g.lineWidth = 1.7; g.stroke(sun); g.lineWidth = 1.25; g.stroke(lines);
    // a sparkle and ink flicks of impact
    star4(g, cx - 9.5, sunTop + 4.6, 2.2, '#ffffff', 1);
    g.strokeStyle = '#0a0a0a'; g.lineCap = 'round';
    for (const [a, r0, r1, w] of [[-2.2, 24, 29, 1.2], [-2.5, 23, 27, 0.8], [-0.7, 25, 30, 1.1], [-0.45, 23, 26.5, 0.8]]) {
      g.lineWidth = w; g.beginPath(); g.moveTo(54 + Math.cos(a) * r0, 54 + Math.sin(a) * r0); g.lineTo(54 + Math.cos(a) * r1, 54 + Math.sin(a) * r1); g.stroke();
    }
    // the sound effect breaking out over the panel's border
    inkText(g, 'ドドド', 40, 84, 10.5, -0.12, '#0b0b0b', '#ffffff', 2.6);
  },
};

// ------------------------------------------------------------------------------------------------ frost
// A New Year night: a fir branch with glass baubles over the corner, a garland of warm lights, real six-armed
// snowflakes and a snowdrift; the mark frozen into ice with snow on it and icicles, a velvet Santa hat on the sun.
function snowflake(g, x, y, r, rot, alpha) {
  g.save(); g.translate(x, y); g.rotate(rot); g.globalAlpha = alpha; g.strokeStyle = '#ffffff'; g.lineCap = 'round'; g.lineWidth = r * 0.12;
  for (let i = 0; i < 6; i++) { g.rotate(Math.PI / 3); g.beginPath(); g.moveTo(0, 0); g.lineTo(0, -r);
    for (const k of [0.45, 0.72]) { g.moveTo(0, -r * k); g.lineTo(r * 0.24, -r * (k + 0.18)); g.moveTo(0, -r * k); g.lineTo(-r * 0.24, -r * (k + 0.18)); } g.stroke(); }
  g.restore();
}
function bauble(g, x, y, r, base, light) {
  g.strokeStyle = 'rgba(230,210,150,0.8)'; g.lineWidth = 0.3; g.beginPath(); g.moveTo(x, y - r - 1.2); g.lineTo(x, y - r - 5); g.stroke();
  const p = new Path2D(); p.arc(x, y, r, 0, 7);
  const gr = g.createRadialGradient(x - r * 0.35, y - r * 0.4, r * 0.1, x, y, r); gr.addColorStop(0, light); gr.addColorStop(0.45, base); gr.addColorStop(1, 'rgba(20,0,10,0.95)');
  g.fillStyle = gr; g.fill(p);
  g.fillStyle = '#d9b860'; g.fillRect(x - r * 0.32, y - r - 1.2, r * 0.64, 1.3);
  g.fillStyle = 'rgba(255,255,255,0.85)'; g.beginPath(); g.ellipse(x - r * 0.38, y - r * 0.42, r * 0.22, r * 0.12, -0.7, 0, 7); g.fill();
}
THEMES.frost = {
  bg(g) {
    const n = noise2(401), r = rng(409);
    pixels(g, (x, y) => {
      let c = mixc([12, 30, 80], [36, 82, 150], smooth(10, 100, y));
      c = mixc(c, [64, 112, 182], smooth(0.5, 0.8, fbm(n, x / 20, y / 20, 4)) * 0.3);
      return [c[0], c[1], c[2]];
    });
    for (const [count, smin, smax, blur, alpha] of [[220, 0.2, 0.45, 0, 0.55], [70, 0.45, 0.85, 0.25, 0.75]]) {
      for (let i = 0; i < count; i++) { const p = new Path2D(); p.arc(r() * 108, r() * 108, smin + r() * (smax - smin), 0, 7);
        g.save(); if (blur) g.filter = `blur(${blur * S}px)`; g.globalAlpha = alpha * (0.5 + r() * 0.5); g.fillStyle = '#ffffff'; g.fill(p); g.restore(); }
    }
    for (const [x, y, s, rot] of [[80, 34, 3.6, 0.2], [26, 60, 2.6, 0.5], [86, 58, 2.2, 0.1], [36, 36, 1.8, 0.3]]) snowflake(g, x, y, s, rot, 0.85);
    // the snowdrift with blue shadows
    const drift = new Path2D(); drift.moveTo(0, 88); drift.bezierCurveTo(24, 80, 44, 86, 62, 84); drift.bezierCurveTo(80, 82, 96, 87, 108, 83); drift.lineTo(108, 108); drift.lineTo(0, 108); drift.closePath();
    const dg = g.createLinearGradient(0, 80, 0, 100); dg.addColorStop(0, '#f6fbff'); dg.addColorStop(1, '#a9c6e8'); g.fillStyle = dg; g.fill(drift);
    // the garland: a sagging wire with warm bulbs
    g.strokeStyle = 'rgba(20,40,30,0.9)'; g.lineWidth = 0.5; g.beginPath(); g.moveTo(14, 72); g.quadraticCurveTo(54, 86, 96, 70); g.stroke();
    const cols = ['#ffcf5a', '#ff6a5a', '#7fe08a', '#7fc8ff'];
    for (let i = 0; i <= 9; i++) { const t = i / 9, x = (1 - t) * (1 - t) * 14 + 2 * (1 - t) * t * 54 + t * t * 96, y = (1 - t) * (1 - t) * 72 + 2 * (1 - t) * t * 86 + t * t * 70 + 1.6;
      const p = new Path2D(); p.ellipse(x, y, 0.9, 1.4, 0, 0, 7); glow(g, p, cols[i % 4], 1.4, 0.8); g.fillStyle = cols[i % 4]; g.fill(p); g.fillStyle = 'rgba(255,255,255,0.8)'; g.fillRect(x - 0.25, y - 1, 0.5, 0.7); }
    // a fir branch over the top-left corner, needles drawn one by one
    const rb = rng(419);
    const branch = (x0, y0, x1, y1) => { g.strokeStyle = '#3b2a1c'; g.lineWidth = 1.1; g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1); g.stroke();
      const len = Math.hypot(x1 - x0, y1 - y0), ux = (x1 - x0) / len, uy = (y1 - y0) / len;
      for (let t = 0; t < len; t += 0.55) for (const side of [-1, 1]) { const px = x0 + ux * t, py = y0 + uy * t; const a = Math.atan2(uy, ux) + side * (0.9 + rb() * 0.3); const nl = 3.4 + rb() * 1.6 - t * 0.02;
        g.strokeStyle = `rgb(${18 + rb() * 22},${70 + rb() * 50},${40 + rb() * 30})`; g.lineWidth = 0.32; g.beginPath(); g.moveTo(px, py); g.lineTo(px + Math.cos(a) * nl, py + Math.sin(a) * nl); g.stroke(); } };
    branch(-4, 16, 36, 30); branch(10, 21, 22, 36);
    bauble(g, 30, 39, 3.6, '#d0182c', '#ff8a8a'); bauble(g, 18, 41, 2.6, '#d8a52a', '#fff0b0');
  },
  fg(g) {
    const n = noise2(431), n2 = noise2(457);
    shadowUnder(g, both, 0, 1.4, 1.4, 'rgba(0,20,60,0.5)');
    for (const p of [sun, lines]) within(g, p, () => {
      pixels(g, (x, y) => {
        const f = fbm(n, x / 3.2, y / 3.2, 5), cr = Math.abs(fbm(n2, x / 5, y / 5, 4) - 0.5);
        let c = mixc([176, 226, 252], [92, 164, 226], smooth(0.3, 0.75, f));
        const crack = (1 - smooth(0.0, 0.03, cr)) * 120;
        return [clamp255(c[0] + crack), clamp255(c[1] + crack), clamp255(c[2] + crack * 0.6), 238];
      });
      g.save(); g.lineWidth = 3; g.strokeStyle = 'rgba(255,255,255,0.75)'; g.filter = `blur(${0.9 * S}px)`; g.stroke(p); g.restore();
    });
    g.strokeStyle = 'rgba(235,250,255,0.9)'; g.lineWidth = 0.5; g.stroke(sun); g.stroke(lines);
    // snow lying on the lines
    const rs = rng(471);
    for (const [y, x0, x1] of [[59.2, 38.5, 69.5], [66.6, 45.3, 62.7]]) { const cap = new Path2D(); cap.moveTo(x0, y + 0.4);
      for (let x = x0; x <= x1; x += 1.2) cap.lineTo(x, y - 0.9 - rs() * 0.9); cap.lineTo(x1, y + 0.4); cap.closePath(); g.fillStyle = '#ffffff'; g.fill(cap); }
    const r = rng(463);
    for (const [y, x0, x1] of [[63.7, 40, 68], [71.1, 47, 61]]) for (let x = x0; x < x1; x += 2.2 + r() * 2) {
      const len = 1.8 + r() * 3.6, w = 0.55 + r() * 0.4;
      const ic = new Path2D(); ic.moveTo(x - w, y - 0.3); ic.quadraticCurveTo(x - w * 0.3, y + len * 0.6, x, y + len); ic.quadraticCurveTo(x + w * 0.3, y + len * 0.6, x + w, y - 0.3); ic.closePath();
      const gr = g.createLinearGradient(0, y, 0, y + len); gr.addColorStop(0, 'rgba(190,232,255,0.95)'); gr.addColorStop(1, 'rgba(255,255,255,0.9)'); g.fillStyle = gr; g.fill(ic);
    }
    // the hat: wider, sitting down over the dome, its tip and pompom falling to the right
    g.save(); g.translate(cx - 0.5, sunTop + 4.6); g.rotate(0.12); g.scale(0.84, 0.84);
    const hat = new Path2D();
    hat.moveTo(-19, 2.6); hat.bezierCurveTo(-17, -11, -6, -20, 6, -21.5); hat.bezierCurveTo(15, -22.5, 23, -18, 26, -9.5);
    hat.bezierCurveTo(22, -12, 17.5, -12.5, 14.5, -9); hat.bezierCurveTo(16.5, -5, 18.2, -1, 19, 2.6); hat.closePath();
    const nv = noise2(479);
    within(g, hat, () => {
      pixels(g, (x, y) => { const v = fbm(nv, x * 1.5, y * 0.7, 3), grain = nv(x * 9, y * 9) - 0.5;
        const c = mixc([222, 34, 48], [126, 8, 24], smooth(0.35, 0.82, v));
        return [clamp255(c[0] + grain * 30), clamp255(c[1] + grain * 10), clamp255(c[2] + grain * 12), 255]; });
      g.filter = `blur(${0.8 * S}px)`; g.lineWidth = 1.8; g.strokeStyle = 'rgba(70,0,10,0.55)';
      g.beginPath(); g.moveTo(-6, 1); g.bezierCurveTo(-1, -9, 6, -15, 15, -16.5); g.stroke();
      g.strokeStyle = 'rgba(255,160,160,0.45)'; g.lineWidth = 1.3; g.beginPath(); g.moveTo(-14, 0); g.bezierCurveTo(-12, -11, -4, -18, 5, -19.5); g.stroke(); g.filter = 'none';
    });
    const fur = (cxp, cyp, rx, ry, count, seed) => { const rr = rng(seed);
      for (let i = 0; i < count; i++) { const a = rr() * Math.PI * 2, d = Math.sqrt(rr()); const x = cxp + Math.cos(a) * rx * d, y = cyp + Math.sin(a) * ry * d, s = 0.75 + rr() * 1.15;
        const shade = 208 + rr() * 47 - (y - cyp + ry) / (2 * ry) * 34; g.fillStyle = `rgba(${shade},${shade},${shade + 6},0.88)`; g.beginPath(); g.arc(x, y, s, 0, 7); g.fill(); } };
    g.save(); g.filter = `blur(${0.4 * S}px)`; g.fillStyle = 'rgba(0,10,40,0.35)'; g.beginPath(); g.ellipse(0, 4, 21, 4.2, 0, 0, 7); g.fill(); g.restore();
    fur(0, 2.4, 20.5, 3.7, 680, 487);
    fur(27, -8.4, 4.4, 4.4, 220, 491);
    g.restore();
    for (const [x, y, s] of [[33, 52, 2], [75, 62, 1.7]]) star4(g, x, y, s, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ spin
// A homage to a famous manga's cross-country horse race of the old West: loud two-tone colours with halftone,
// saguaro cacti on the horizon, a horseshoe, a whole grooved steel ball spinning in the corner and menacing sound
// effects; the sun the top of a steel ball in heavy ink, the lines gold bars, hatched shadows.
THEMES.spin = {
  bg(g) {
    const n = noise2(601);
    pixels(g, (x, y) => {
      const d = (x - y) * 0.7 + 8; // a diagonal split, magenta above, teal below
      let c = d > 0 ? mixc([255, 76, 150], [196, 40, 120], smooth(0, 60, d)) : mixc([40, 206, 196], [18, 120, 150], smooth(0, 60, -d));
      if (y > 78) c = mixc([250, 196, 92], [214, 120, 52], smooth(78, 108, y)); // sand
      const p = 2, u = (x + y) / Math.SQRT2 / p, w = (x - y) / Math.SQRT2 / p, du = u - Math.round(u), dw = w - Math.round(w);
      if (Math.sqrt(du * du + dw * dw) < 0.12 + 0.24 * smooth(10, 50, Math.abs(d))) c = c.map(v => v * 0.82);
      const grain = (n(x * 12, y * 12) - 0.5) * 12;
      return [clamp255(c[0] + grain), clamp255(c[1] + grain), clamp255(c[2] + grain)];
    });
    g.fillStyle = '#1b0b1e'; g.fillRect(0, 77.6, 108, 1.2);
    // saguaro cacti in ink on the horizon
    const cactus = (x, h, s) => { g.fillStyle = '#1b0b1e'; const c = new Path2D(); c.roundRect(x - 1.4 * s, 78 - h, 2.8 * s, h, 1.4 * s);
      c.roundRect(x - 4.4 * s, 78 - h * 0.62, 1.8 * s, h * 0.32, 0.9 * s); c.roundRect(x - 4.4 * s, 78 - h * 0.36, 4.4 * s, 1.6 * s, 0.8 * s);
      c.roundRect(x + 2.6 * s, 78 - h * 0.78, 1.8 * s, h * 0.4, 0.9 * s); c.roundRect(x, 78 - h * 0.44, 4.4 * s, 1.6 * s, 0.8 * s); g.fill(c); };
    cactus(89, 12, 0.8);
    // a horseshoe, gold with nail holes, at the top right
    g.save(); g.translate(80, 30); g.rotate(0.35);
    const hs = new Path2D(); hs.arc(0, 0, 6.4, Math.PI * 0.85, Math.PI * 2.15); hs.arc(0, 0, 3.6, Math.PI * 2.15, Math.PI * 0.85, true); hs.closePath();
    const hg = g.createLinearGradient(-6, -6, 6, 6); hg.addColorStop(0, '#fff1a8'); hg.addColorStop(0.5, '#e0a52a'); hg.addColorStop(1, '#8a5a10');
    g.fillStyle = hg; g.fill(hs); g.lineWidth = 0.7; g.strokeStyle = '#1b0b1e'; g.stroke(hs);
    for (let i = 0; i < 6; i++) { const a = Math.PI * (0.98 + i * 0.21); g.fillStyle = '#1b0b1e'; g.beginPath(); g.arc(Math.cos(a) * 5, Math.sin(a) * 5, 0.42, 0, 7); g.fill(); }
    g.restore();
    // a whole steel ball spinning at the lower left
    const bx = 27, byy = 83, br = 11.5;
    const ball = new Path2D(); ball.arc(bx, byy, br, 0, 7);
    within(g, ball, () => { const gr = g.createRadialGradient(bx - 4, byy - 5, 1, bx, byy, br); gr.addColorStop(0, '#ffffff'); gr.addColorStop(0.35, '#b9bed0'); gr.addColorStop(0.75, '#5b5f78'); gr.addColorStop(1, '#262838');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108); g.strokeStyle = 'rgba(25,20,40,0.85)'; g.lineWidth = 0.6;
      for (let k = -3; k <= 3; k++) { g.beginPath(); g.ellipse(bx, byy, br * Math.abs(Math.cos(k * 0.45)) + 0.01, br, 0.5, 0, 7); g.stroke(); } });
    g.lineWidth = 1.1; g.strokeStyle = '#1b0b1e'; g.stroke(ball);
    g.strokeStyle = 'rgba(255,255,255,0.9)'; g.lineCap = 'round';
    for (const [rr, a0, a1, w] of [[br + 3, -0.1, 0.35, 1], [br + 5.4, -0.05, 0.2, 0.7]]) { g.lineWidth = w; g.beginPath(); g.arc(bx, byy, rr, Math.PI * (1 + a0), Math.PI * (1 + a1)); g.stroke(); }
    // menacing sound effects
    for (const [x, y, s, rot] of [[29, 30, 10, -0.22], [37, 22, 7, -0.12], [78, 70, 9, 0.18], [86, 63, 6.5, 0.24]])
      inkText(g, 'ゴ', x, y, s, rot, '#a24cff', '#1b0b1e', s * 0.3);
  },
  fg(g) {
    shadowUnder(g, both, 1.2, 1.8, 0.9, 'rgba(30,0,40,0.6)');
    within(g, sun, () => { // chrome with the scene's colours in it, then its grooves
      const gr = g.createLinearGradient(0, sunTop, 0, sunBot); gr.addColorStop(0, '#ffffff'); gr.addColorStop(0.3, '#c8ccdc'); gr.addColorStop(0.55, '#ff9ccb'); gr.addColorStop(0.7, '#6a6f8a'); gr.addColorStop(1, '#2a2c3e');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
      g.strokeStyle = 'rgba(20,16,36,0.9)'; g.lineWidth = 0.7;
      for (let k = -3; k <= 3; k++) { g.beginPath(); g.ellipse(cx, sunCy, sunR * Math.abs(Math.cos(k * 0.42)) + 0.01, sunR, 0, Math.PI, Math.PI * 2); g.stroke(); }
      g.beginPath(); g.ellipse(cx, sunCy, sunR, sunR * 0.42, 0, Math.PI, Math.PI * 2); g.stroke();
      g.fillStyle = 'rgba(255,255,255,0.9)'; g.beginPath(); g.ellipse(cx - 6, sunTop + 5.5, 5.5, 2.4, -0.3, 0, 7); g.fill();
    });
    hatch(g, sun, 30, sunTop, 80, sunBot, 1.0, 0.85, 0.32, '#140c20', (x, y) => (x - cx) * 0.6 + (sunBot - y) * -0.2 > 6);
    within(g, lines, () => { const gr = g.createLinearGradient(0, 59, 0, 71.3); gr.addColorStop(0, '#fff3b5'); gr.addColorStop(0.4, '#f2b32a'); gr.addColorStop(0.75, '#9b6410'); gr.addColorStop(1, '#ffd36a'); g.fillStyle = gr; g.fillRect(0, 0, 108, 108); });
    hatch(g, lines, 30, 58, 80, 72, 0.9, 0.85, 0.28, '#3b1f05', (x, y) => x > cx + 6);
    g.lineJoin = 'round'; g.strokeStyle = '#140c20'; g.lineWidth = 1.5; g.stroke(sun); g.lineWidth = 1.2; g.stroke(lines);
    g.strokeStyle = 'rgba(255,248,220,0.95)'; g.lineCap = 'round';
    for (const [rr, a0, a1, w] of [[sunR + 4, 1.05, 1.33, 1.1], [sunR + 6.8, 1.1, 1.26, 0.75], [sunR + 4, 1.64, 1.92, 1.1]]) { g.lineWidth = w; g.beginPath(); g.arc(cx, sunCy, rr, Math.PI * a0, Math.PI * a1); g.stroke(); }
  },
};

// ------------------------------------------------------------------------------------------------ spin (v3)
// A golden hour desert: deep blue sky warming to peach at the horizon, a low sun's glow, mesas in layers of haze and
// lit dunes with ripples; the golden ratio's spiral and rectangles in gold lines converge on the mark, which is a
// green steel ball - polished green metal with engraved grooves and the desert's light in it.
const PHI = (1 + Math.sqrt(5)) / 2;
THEMES.spin = {
  bg(g) {
    const n = noise2(611), n2 = noise2(617);
    const far = (x) => 66 - 6 * smooth(10, 26, x) + 6 * smooth(30, 34, x) - 4 * smooth(70, 74, x) + 4 * smooth(88, 92, x) + (n(x / 3, 1) - 0.5) * 1.4; // mesas
    const near = (x) => 74 + 4 * Math.sin(x / 13 + 0.6) + 2 * Math.sin(x / 5.3);
    const dune = (x) => 84 + 3.4 * Math.sin(x / 11 - 0.8) + 1.4 * Math.sin(x / 4.1 + 1);
    pixels(g, (x, y) => {
      // sky
      let c = mixc(mixc([36, 54, 128], [214, 120, 140], smooth(8, 50, y)), [255, 196, 130], smooth(44, 70, y));
      const sunGlow = Math.exp(-((x - 74) * (x - 74) + (y - 64) * (y - 64)) / 260);
      c = mixc(c, [255, 236, 190], sunGlow * 0.85);
      const cl = smooth(0.58, 0.78, fbm(n2, x / 18, y / 5, 4)) * (1 - smooth(30, 52, y)); // thin streaks of cloud
      c = mixc(c, [255, 176, 150], cl * 0.5);
      if (y > far(x)) c = mixc([176, 106, 132], [150, 88, 120], smooth(60, 74, y)); // far mesas in haze
      if (y > near(x)) { c = mixc([188, 92, 64], [140, 60, 52], smooth(70, 84, y)); c = c.map(v => v + (fbm(n, x / 4, y / 2, 3) - 0.5) * 20); }
      const d = dune(x);
      if (y > d) {
        const t = smooth(d, 108, y);
        c = mixc([248, 176, 104], [196, 108, 62], t);
        const ripple = Math.sin((y - d) * 2.4 + Math.sin(x / 4.5) * 1.8 + x * 0.08) * 0.5 + 0.5;
        c = c.map(v => v - ripple * 16 * (1 - t * 0.5));
        c = mixc(c, [255, 226, 170], Math.exp(-(y - d) / 0.9) * 0.8); // the lit crest
      }
      const grain = (n(x * 14, y * 14) - 0.5) * 8;
      return [clamp255(c[0] + grain), clamp255(c[1] + grain), clamp255(c[2] + grain)];
    });
    // the golden ratio: Fibonacci squares around the mark and the spiral through them, converging on the mark
    const fib = [1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144], u = 0.78;
    const sq = [], arcs = [];
    let rx = 0, ry = 0, rw = 1, rh = 1;
    sq.push([0, 0, 1]); arcs.push([1, 1, 1, Math.PI, Math.PI * 1.5]);
    for (let i = 1; i < fib.length; i++) {
      const f = fib[i], dir = (i - 1) % 4;
      if (dir === 0) { sq.push([rx + rw, ry, f]); arcs.push([rx + rw, ry + f, f, Math.PI * 1.5, Math.PI * 2]); rw += f; }
      else if (dir === 1) { sq.push([rx + rw - f, ry + rh, f]); arcs.push([rx + rw - f, ry + rh, f, 0, Math.PI * 0.5]); rh += f; }
      else if (dir === 2) { sq.push([rx - f, ry + rh - f, f]); arcs.push([rx, ry + rh - f, f, Math.PI * 0.5, Math.PI]); rx -= f; rw += f; }
      else { sq.push([rx, ry - f, f]); arcs.push([rx + f, ry, f, Math.PI, Math.PI * 1.5]); ry -= f; rh += f; }
    }
    // the spiral's eye: where the squares close in (between the first ones)
    const eyeX = 0.72, eyeY = 0.62;
    g.save(); g.translate(54, (T + B) / 2); g.rotate(-0.12); g.scale(u, u); g.translate(-eyeX, -eyeY);
    g.lineJoin = 'miter';
    g.strokeStyle = 'rgba(255,226,150,0.5)'; g.lineWidth = 0.42 / u;
    for (const [x, y, f] of sq) g.strokeRect(x, y, f, f);
    const spiral = new Path2D(); for (const [ax, ay, r, a0, a1] of arcs) spiral.arc(ax, ay, r, a0, a1);
    g.save(); g.filter = `blur(${0.8 * S}px)`; g.strokeStyle = 'rgba(255,214,120,0.7)'; g.lineWidth = 1.6 / u; g.stroke(spiral); g.restore();
    g.strokeStyle = 'rgba(255,240,190,0.95)'; g.lineWidth = 0.7 / u; g.stroke(spiral);
    g.restore();
  },
  fg(g) {
    const n = noise2(631);
    shadowUnder(g, both, 1.2, 2, 1.4, 'rgba(60,20,10,0.5)');
    // polished green metal: a sphere's light (bright top left, the desert's warm light below), then the grooves
    const metal = (path, top, bottom) => within(g, path, () => {
      pixels(g, (x, y) => {
        const nx = (x - cx) / (sunR + 2), ny = (y - sunCy) / (sunR + 2);
        const lit = Math.max(0, -0.5 * nx - 0.75 * ny + 0.45 * Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny)));
        let c = mixc([14, 66, 38], [70, 182, 96], Math.min(1, lit * 1.25));
        c = mixc(c, [205, 255, 200], smooth(0.78, 1.0, lit));                 // highlight
        c = mixc(c, [255, 170, 100], smooth(0.1, 0.9, (y - top) / (bottom - top)) * 0.18); // the desert in it
        const brushed = (n(x * 1.4, y * 8) - 0.5) * 14;
        return [clamp255(c[0] + brushed), clamp255(c[1] + brushed), clamp255(c[2] + brushed * 0.7), 255];
      });
    });
    metal(sun, sunTop, sunBot); metal(lines, 59, 71.3);
    // the grooves: engraved curves, a dark cut with a lit lip
    const groove = (draw) => { g.save(); g.lineCap = 'round';
      g.strokeStyle = 'rgba(4,30,14,0.85)'; g.lineWidth = 0.75; draw(); g.stroke();
      g.translate(0.35, 0.45); g.strokeStyle = 'rgba(200,255,200,0.55)'; g.lineWidth = 0.35; draw(); g.stroke(); g.restore(); };
    within(g, sun, () => {
      for (const k of [-2, -1, 0, 1, 2]) groove(() => { g.beginPath(); g.ellipse(cx, sunCy, sunR * Math.abs(Math.sin((k + 2.5) * 0.55)) + 0.01, sunR, 0, Math.PI, Math.PI * 2); });
      groove(() => { g.beginPath(); g.ellipse(cx, sunCy, sunR, sunR * 0.45, 0, Math.PI, Math.PI * 2); });
      groove(() => { g.beginPath(); g.arc(cx, sunTop + 6.2, 2.1, 0, Math.PI * 2); }); // the ball's dimple
    });
    within(g, lines, () => { for (const y of [61.44, 68.88]) groove(() => { g.beginPath(); g.moveTo(30, y); g.lineTo(80, y); }); });
    // a crisp outline and the specular glints
    g.lineJoin = 'round'; g.strokeStyle = '#06301a'; g.lineWidth = 0.9; g.stroke(sun); g.stroke(lines);
    within(g, sun, () => { g.fillStyle = 'rgba(255,255,255,0.9)'; g.beginPath(); g.ellipse(cx - 7, sunTop + 5.2, 4.6, 1.9, -0.35, 0, 7); g.fill(); });
    star4(g, cx - 9.5, sunTop + 4.5, 2.4, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ frost (v3)
// Only a snowfall in a winter night, and the mark frozen through: ice with cracks and frost, icicles, glints.
const FROST_FG_V2 = THEMES.frost.fg;
THEMES.frost = {
  bg(g) {
    const n = noise2(401), r = rng(409);
    pixels(g, (x, y) => {
      let c = mixc([10, 26, 72], [44, 92, 160], smooth(0, 108, y));
      c = mixc(c, [70, 118, 186], smooth(0.5, 0.82, fbm(n, x / 22, y / 22, 4)) * 0.3);
      return [c[0], c[1], c[2]];
    });
    // snow in four depths: far fine dust, small flakes, mid flakes, a few big soft ones near
    for (const [count, smin, smax, blur, alpha] of [[380, 0.15, 0.3, 0, 0.45], [220, 0.3, 0.55, 0, 0.7], [80, 0.6, 1.0, 0.3, 0.8], [16, 1.4, 2.6, 1.0, 0.6]]) {
      for (let i = 0; i < count; i++) { const x = r() * 108, y = r() * 108, s = smin + r() * (smax - smin);
        const p = new Path2D(); p.ellipse(x, y, s, s * (1.1 + r() * 0.3), r() * 3, 0, 7);
        g.save(); if (blur) g.filter = `blur(${blur * S}px)`; g.globalAlpha = alpha * (0.55 + r() * 0.45); g.fillStyle = '#ffffff'; g.fill(p); g.restore(); }
    }
  },
  fg(g) {
    const n = noise2(431), n2 = noise2(457), n3 = noise2(443);
    shadowUnder(g, both, 0, 1.4, 1.4, 'rgba(0,20,60,0.5)');
    for (const p of [sun, lines]) within(g, p, () => {
      pixels(g, (x, y) => {
        const f = fbm(n, x / 3.2, y / 3.2, 5), cr = Math.abs(fbm(n2, x / 5, y / 5, 4) - 0.5), fine = Math.abs(fbm(n3, x / 1.6, y / 1.6, 3) - 0.5);
        let c = mixc([186, 230, 252], [88, 160, 224], smooth(0.3, 0.75, f));
        const crack = (1 - smooth(0.0, 0.028, cr)) * 120 + (1 - smooth(0.0, 0.02, fine)) * 45;
        return [clamp255(c[0] + crack), clamp255(c[1] + crack), clamp255(c[2] + crack * 0.6), 240];
      });
      g.save(); g.lineWidth = 3.2; g.strokeStyle = 'rgba(255,255,255,0.8)'; g.filter = `blur(${0.9 * S}px)`; g.stroke(p); g.restore();
      // frost feathers creeping in from the edge
      const rf = rng(447); g.strokeStyle = 'rgba(255,255,255,0.7)'; g.lineWidth = 0.25; g.lineCap = 'round';
      for (let i = 0; i < 26; i++) { const x = 34 + rf() * 40, y = sunTop + rf() * 36, a = rf() * Math.PI * 2, l = 1.2 + rf() * 2.2;
        g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(a) * l, y + Math.sin(a) * l);
        for (const s of [-1, 1]) { g.moveTo(x + Math.cos(a) * l * 0.5, y + Math.sin(a) * l * 0.5); g.lineTo(x + Math.cos(a + s * 0.7) * l * 0.9, y + Math.sin(a + s * 0.7) * l * 0.9); } g.stroke(); }
    });
    g.strokeStyle = 'rgba(235,250,255,0.9)'; g.lineWidth = 0.5; g.stroke(sun); g.stroke(lines);
    // a light crust of rime on top of the sun
    within(g, sun, () => { const gr = g.createLinearGradient(0, sunTop, 0, sunTop + 5); gr.addColorStop(0, 'rgba(255,255,255,0.75)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = gr; g.fillRect(0, sunTop, 108, 5); });
    const r = rng(463);
    for (const [y, x0, x1] of [[56.6, 37, 71], [63.7, 40, 68], [71.1, 47, 61]]) for (let x = x0; x < x1; x += 2 + r() * 2) {
      const len = 1.8 + r() * 3.8, w = 0.5 + r() * 0.45;
      const ic = new Path2D(); ic.moveTo(x - w, y - 0.3); ic.quadraticCurveTo(x - w * 0.3, y + len * 0.6, x, y + len); ic.quadraticCurveTo(x + w * 0.3, y + len * 0.6, x + w, y - 0.3); ic.closePath();
      const gr = g.createLinearGradient(0, y, 0, y + len); gr.addColorStop(0, 'rgba(190,232,255,0.95)'); gr.addColorStop(1, 'rgba(255,255,255,0.92)'); g.fillStyle = gr; g.fill(ic);
    }
    for (const [x, y, s] of [[44, 42, 2.4], [70, 47, 1.6], [62, 67, 1.4]]) star4(g, x, y, s, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ spin (v4)
// The golden ratio, plainly: a golden rectangle cut into its squares, the spiral through them, its eye on the mark
// (a little smaller here so the spiral's turns wrap it closely), a phi in the corner; the desert of v3 behind.
const SPIN_V3_BG = THEMES.spin.bg;
function goldenParts(W, ox, oy) { // a golden rectangle W wide, cut inwards; returns squares, arcs and the eye, moved so the eye is at (ox, oy)
  const H = W / PHI; let x = 0, y = 0, w = W, h = H; const sq = [], arcs = [];
  for (let i = 0; i < 16; i++) {
    const d = i % 4; let s;
    if (d === 0) { s = h; sq.push([x, y, s]); arcs.push([x + s, y + s, s, Math.PI, Math.PI * 1.5]); x += s; w -= s; }
    else if (d === 1) { s = w; sq.push([x, y, s]); arcs.push([x, y + s, s, Math.PI * 1.5, Math.PI * 2]); y += s; h -= s; }
    else if (d === 2) { s = h; sq.push([x + w - s, y, s]); arcs.push([x + w - s, y, s, 0, Math.PI * 0.5]); w -= s; }
    else { s = w; sq.push([x, y + h - s, s]); arcs.push([x + s, y + h - s, s, Math.PI * 0.5, Math.PI]); h -= s; }
  }
  const ex = x + w / 2, ey = y + h / 2, dx = ox - ex, dy = oy - ey;
  return { W, H, dx, dy, sq: sq.map(([a, b, s]) => [a + dx, b + dy, s]), arcs: arcs.map(([a, b, r, a0, a1]) => [a + dx, b + dy, r, a0, a1]) };
}
const SPIN_K = 0.62, SPIN_EX = 57, SPIN_EY = 55, SPIN_GOLD = goldenParts(86, SPIN_EX, SPIN_EY);
THEMES.spin.bg = function (g) {
  SPIN_V3_BG.call(this, g === undefined ? g : g); // the desert, its spiral replaced below
};
THEMES.spin = {
  bg(g) {
    // the desert of v3 without its spiral: draw it, then paint the new geometry over a fresh copy of the sky
    const n = noise2(611), n2 = noise2(617);
    const far = (x) => 66 - 6 * smooth(10, 26, x) + 6 * smooth(30, 34, x) - 4 * smooth(70, 74, x) + 4 * smooth(88, 92, x) + (n(x / 3, 1) - 0.5) * 1.4;
    const near = (x) => 74 + 4 * Math.sin(x / 13 + 0.6) + 2 * Math.sin(x / 5.3);
    const dune = (x) => 84 + 3.4 * Math.sin(x / 11 - 0.8) + 1.4 * Math.sin(x / 4.1 + 1);
    pixels(g, (x, y) => {
      let c = mixc(mixc([36, 54, 128], [214, 120, 140], smooth(8, 50, y)), [255, 196, 130], smooth(44, 70, y));
      c = mixc(c, [255, 236, 190], Math.exp(-((x - 74) * (x - 74) + (y - 64) * (y - 64)) / 260) * 0.85);
      c = mixc(c, [255, 176, 150], smooth(0.58, 0.78, fbm(n2, x / 18, y / 5, 4)) * (1 - smooth(30, 52, y)) * 0.5);
      if (y > far(x)) c = mixc([176, 106, 132], [150, 88, 120], smooth(60, 74, y));
      if (y > near(x)) { c = mixc([188, 92, 64], [140, 60, 52], smooth(70, 84, y)); c = c.map(v => v + (fbm(n, x / 4, y / 2, 3) - 0.5) * 20); }
      const d = dune(x);
      if (y > d) { const t = smooth(d, 108, y); c = mixc([248, 176, 104], [196, 108, 62], t);
        const ripple = Math.sin((y - d) * 2.4 + Math.sin(x / 4.5) * 1.8 + x * 0.08) * 0.5 + 0.5; c = c.map(v => v - ripple * 16 * (1 - t * 0.5));
        c = mixc(c, [255, 226, 170], Math.exp(-(y - d) / 0.9) * 0.8); }
      const grain = (n(x * 14, y * 14) - 0.5) * 8;
      return [clamp255(c[0] + grain), clamp255(c[1] + grain), clamp255(c[2] + grain)];
    });
    const G = SPIN_GOLD;
    // the rectangle and its squares: gold rules with a soft dark edge so they read on the sky and the sand
    g.lineJoin = 'miter';
    const rule = (draw, w, a) => { g.save(); g.strokeStyle = 'rgba(60,20,10,0.35)'; g.lineWidth = w + 0.5; g.translate(0.25, 0.3); draw(); g.restore();
      g.strokeStyle = `rgba(255,222,140,${a})`; g.lineWidth = w; draw(); };
    rule(() => g.strokeRect(G.dx, G.dy, G.W, G.H), 0.75, 0.95);
    for (const [x, y, s] of G.sq.slice(0, 11)) rule(() => g.strokeRect(x, y, s, s), 0.5, 0.8);
    // the spiral, glowing
    const spiral = new Path2D(); for (const [ax, ay, r, a0, a1] of G.arcs) spiral.arc(ax, ay, r, a0, a1);
    g.save(); g.filter = `blur(${1.0 * S}px)`; g.strokeStyle = 'rgba(255,200,90,0.85)'; g.lineWidth = 2.2; g.stroke(spiral); g.restore();
    g.strokeStyle = 'rgba(255,246,206,1)'; g.lineWidth = 0.95; g.lineCap = 'round'; g.stroke(spiral);
    // phi in the corner
    g.save(); g.font = 'italic 700 9px serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.lineWidth = 1.2; g.strokeStyle = 'rgba(60,20,10,0.5)'; g.strokeText('φ', 79, 30); g.fillStyle = '#ffe08a'; g.fillText('φ', 79, 30); g.restore();
  },
  fg(g) {
    const n = noise2(631), k = SPIN_K, oy = (T + B) / 2;
    const toMark = (x, y) => [54 + (x - SPIN_EX) / k, oy + (y - SPIN_EY) / k];
    g.save(); g.translate(SPIN_EX, SPIN_EY); g.scale(k, k); g.translate(-54, -oy);
    shadowUnder(g, both, 1.2, 2, 1.4, 'rgba(60,20,10,0.5)');
    const metal = (path, top, bottom) => within(g, path, () => {
      pixels(g, (px, py) => {
        const [x, y] = toMark(px, py);
        const nx = (x - cx) / (sunR + 2), ny = (y - sunCy) / (sunR + 2);
        const lit = Math.max(0, -0.5 * nx - 0.75 * ny + 0.45 * Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny)));
        let c = mixc([14, 66, 38], [70, 182, 96], Math.min(1, lit * 1.25));
        c = mixc(c, [205, 255, 200], smooth(0.78, 1.0, lit));
        c = mixc(c, [255, 170, 100], smooth(0.1, 0.9, (y - top) / (bottom - top)) * 0.18);
        const brushed = (n(x * 1.4, y * 8) - 0.5) * 14;
        return [clamp255(c[0] + brushed), clamp255(c[1] + brushed), clamp255(c[2] + brushed * 0.7), 255];
      });
    });
    metal(sun, sunTop, sunBot); metal(lines, 59, 71.3);
    const groove = (draw) => { g.save(); g.lineCap = 'round';
      g.strokeStyle = 'rgba(4,30,14,0.85)'; g.lineWidth = 0.8; draw(); g.stroke();
      g.translate(0.4, 0.5); g.strokeStyle = 'rgba(200,255,200,0.55)'; g.lineWidth = 0.4; draw(); g.stroke(); g.restore(); };
    within(g, sun, () => {
      for (const kk of [-2, -1, 0, 1, 2]) groove(() => { g.beginPath(); g.ellipse(cx, sunCy, sunR * Math.abs(Math.sin((kk + 2.5) * 0.55)) + 0.01, sunR, 0, Math.PI, Math.PI * 2); });
      groove(() => { g.beginPath(); g.ellipse(cx, sunCy, sunR, sunR * 0.45, 0, Math.PI, Math.PI * 2); });
      groove(() => { g.beginPath(); g.arc(cx, sunTop + 6.2, 2.1, 0, Math.PI * 2); });
    });
    within(g, lines, () => { for (const y of [61.44, 68.88]) groove(() => { g.beginPath(); g.moveTo(30, y); g.lineTo(80, y); }); });
    g.lineJoin = 'round'; g.strokeStyle = '#06301a'; g.lineWidth = 1.0; g.stroke(sun); g.stroke(lines);
    within(g, sun, () => { g.fillStyle = 'rgba(255,255,255,0.9)'; g.beginPath(); g.ellipse(cx - 7, sunTop + 5.2, 4.6, 1.9, -0.35, 0, 7); g.fill(); });
    star4(g, cx - 9.5, sunTop + 4.5, 2.6, '#ffffff', 0.95);
    g.restore();
  },
};
