// Solegram themed launcher icons, set 6 (3.3): retro desktop, cosmos, manga, frost, jellyfish, spin, droid.
// Homages to styles only: no names, logos, characters or pictures of any particular work.

function clamp255(v) { return Math.max(0, Math.min(255, v)); }
function mix(a, b, t) { return a + (b - a) * t; }
function mixc(c1, c2, t) { return [mix(c1[0], c2[0], t), mix(c1[1], c2[1], t), mix(c1[2], c2[2], t)]; }
function smooth(e0, e1, x) { const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }
function shadowUnder(g, path, dx, dy, blur, color) { g.save(); g.filter = `blur(${blur * S}px)`; g.translate(dx, dy); g.fillStyle = color; g.fill(path); g.restore(); }
function gloss(g, path, top, alpha) { // the glossy upper half of a button
  within(g, path, () => {
    const gr = g.createLinearGradient(0, top, 0, top + 9); gr.addColorStop(0, `rgba(255,255,255,${alpha})`); gr.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = gr; g.fillRect(0, top, 108, 9);
  });
}

// ------------------------------------------------------------------------------------------------ retro desktop
// A desktop from the early 2000s: a deep blue sky with soft cumulus, a rolling green hill with grass; the mark as
// glossy plastic buttons of that era - the sun in luminous blue, the lines in start-button green.
THEMES.xp = {
  bg(g) {
    const n = noise2(101), n2 = noise2(131);
    const hill = (x) => 70 + 9 * Math.sin((x + 14) / 34 * Math.PI) - 0.004 * (x - 50) * (x - 50);
    pixels(g, (x, y) => {
      const h = hill(x);
      if (y < h) {
        // sky: deep blue at the top, pale at the horizon, cumulus from fbm
        const t = y / h;
        let c = mixc([24, 86, 200], [150, 196, 240], Math.pow(t, 1.4));
        const cl = fbm(n, x / 22, y / 13, 5);
        const cloud = smooth(0.52, 0.72, cl + (y - 30) * 0.002) * (1 - smooth(52, 66, y) * 0.6);
        const shade = fbm(n2, x / 9, y / 7, 3);
        c = mixc(c, [255 - 18 * shade, 255 - 14 * shade, 255 - 6 * shade], cloud * 0.95);
        return [c[0], c[1], c[2]];
      }
      // grass: lit green on top of the hill, deeper below, blades from stretched noise
      const d = y - h;
      let c = mixc([118, 190, 54], [38, 118, 24], Math.min(1, d / 34));
      const blades = n(x * 2.6, y * 0.55) - 0.5, patch = fbm(n2, x / 14, y / 10, 3) - 0.5;
      c = c.map((v, i) => v + blades * 26 + patch * 30 * (i === 1 ? 1.2 : 0.8));
      const rim = Math.exp(-d / 1.4) * 40; // light on the crest
      return [clamp255(c[0] + rim * 0.5), clamp255(c[1] + rim), clamp255(c[2] + rim * 0.2)];
    });
  },
  fg(g) {
    shadowUnder(g, both, 0.8, 1.6, 1.2, 'rgba(0,30,80,0.45)');
    within(g, sun, () => {
      const gr = g.createLinearGradient(0, sunTop, 0, sunBot); gr.addColorStop(0, '#5aa2ff'); gr.addColorStop(0.55, '#2563d8'); gr.addColorStop(1, '#173f9c');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
      const sh = g.createRadialGradient(cx, sunBot + 4, 2, cx, sunBot + 4, 18); sh.addColorStop(0, 'rgba(140,210,255,0.55)'); sh.addColorStop(1, 'rgba(140,210,255,0)');
      g.fillStyle = sh; g.fillRect(0, 0, 108, 108);
    });
    within(g, lines, () => {
      const gr = g.createLinearGradient(0, 59, 0, 71.2); gr.addColorStop(0, '#6fd257'); gr.addColorStop(0.5, '#3a9f2b'); gr.addColorStop(1, '#23701a');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
    });
    // gloss: a bright cap on the sun, a thin shine on each line
    within(g, sun, () => {
      const cap = new Path2D(); cap.ellipse(cx, sunTop + 6.2, sunR * 0.82, 6.6, 0, 0, Math.PI * 2);
      const gr = g.createLinearGradient(0, sunTop, 0, sunTop + 12); gr.addColorStop(0, 'rgba(255,255,255,0.85)'); gr.addColorStop(1, 'rgba(255,255,255,0.05)');
      g.fillStyle = gr; g.fill(cap);
    });
    gloss(g, lines, 59.2, 0.55); gloss(g, lines, 66.6, 0.55);
    g.lineJoin = 'round'; g.lineWidth = 0.7;
    g.strokeStyle = '#0f2f78'; g.stroke(sun);
    g.strokeStyle = '#1a5512'; g.stroke(lines);
  },
};

// ------------------------------------------------------------------------------------------------ cosmos
// Deep space: a violet and teal nebula over dust, stars of every size with a few bright ones, a ringed planet
// rising at the corner; the mark as a star - a boiling plasma sun with its corona, the lines two light trails.
THEMES.cosmos = {
  bg(g) {
    const n1 = noise2(201), n2 = noise2(233), n3 = noise2(257), r = rng(271);
    pixels(g, (x, y) => {
      let c = mixc([6, 6, 22], [14, 10, 40], y / 108);
      const neb = fbm(n1, x / 26, y / 26, 6), wisp = fbm(n2, x / 9 + neb * 2, y / 9, 4);
      const v = smooth(0.45, 0.8, neb) * (0.6 + 0.6 * wisp);
      c = mixc(c, [132, 54, 200], v * 0.75);
      c = mixc(c, [32, 170, 190], smooth(0.55, 0.85, fbm(n3, x / 18 + 3, y / 18, 5)) * 0.55 * (1 - v * 0.5));
      const dark = smooth(0.55, 0.75, fbm(n2, x / 12 + 7, y / 12 + 3, 4)); // dust lanes
      c = c.map(k => k * (1 - dark * 0.55));
      return [c[0], c[1], c[2]];
    });
    // stars
    for (let i = 0; i < 420; i++) {
      const x = r() * 108, y = r() * 108, s = Math.pow(r(), 6) * 0.9 + 0.12, a = 0.35 + r() * 0.65;
      g.fillStyle = `rgba(${200 + r() * 55},${200 + r() * 55},255,${a})`; g.beginPath(); g.arc(x, y, s, 0, 7); g.fill();
    }
    for (const [x, y, s] of [[26, 30, 2.6], [84, 24, 1.8], [30, 82, 1.6]]) { glow(g, (() => { const p = new Path2D(); p.arc(x, y, s * 0.9, 0, 7); return p; })(), '#bfe6ff', 1.2, 0.7); star4(g, x, y, s * 1.6, '#ffffff', 0.95); }
    // a ringed planet at the lower right
    const px = 92, py = 96, pr = 22;
    const planet = new Path2D(); planet.arc(px, py, pr, 0, Math.PI * 2);
    g.save(); g.translate(px, py); g.rotate(-0.42); g.scale(1, 0.28);
    g.strokeStyle = 'rgba(220,200,255,0.5)'; g.lineWidth = 3.2; g.beginPath(); g.arc(0, 0, pr * 1.55, Math.PI, Math.PI * 2); g.stroke(); g.restore();
    within(g, planet, () => {
      const gr = g.createRadialGradient(px - 9, py - 10, 2, px, py, pr); gr.addColorStop(0, '#f4b07a'); gr.addColorStop(0.6, '#a24d6a'); gr.addColorStop(1, '#2a1238');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
      for (let i = 0; i < 9; i++) { g.strokeStyle = `rgba(255,220,190,${0.06 + (i % 3) * 0.05})`; g.lineWidth = 1.2 + (i % 2); g.beginPath(); g.ellipse(px, py + (i - 4) * 4.4, pr * 1.2, 2.2, -0.42, 0, 7); g.stroke(); }
    });
    g.save(); g.translate(px, py); g.rotate(-0.42); g.scale(1, 0.28);
    g.strokeStyle = 'rgba(235,220,255,0.75)'; g.lineWidth = 3.2; g.beginPath(); g.arc(0, 0, pr * 1.55, 0, Math.PI); g.stroke(); g.restore();
  },
  fg(g) {
    const n = noise2(281);
    glow(g, sun, '#ffb347', 5, 0.55); glow(g, sun, '#ff6a2a', 2, 0.6);
    within(g, sun, () => {
      pixels(g, (x, y) => {
        const d = Math.hypot(x - cx, y - sunCy) / sunR;
        const gran = fbm(n, x / 2.2, y / 2.2, 4), cell = fbm(n, x / 6 + 5, y / 6, 3);
        let c = mixc([255, 244, 200], [255, 150, 40], smooth(0.1, 1, d));
        c = mixc(c, [200, 60, 20], smooth(0.85, 1.02, d) * 0.8); // limb darkening
        const k = (gran - 0.5) * 70 + (cell - 0.5) * 50;
        return [clamp255(c[0] + k * 0.3), clamp255(c[1] + k), clamp255(c[2] + k * 0.6), 255];
      });
    });
    // the lines: two trails of light with a hot core
    glow(g, lines, '#7fe6ff', 2.4, 0.9);
    within(g, lines, () => {
      const gr = g.createLinearGradient(L, 0, R, 0); gr.addColorStop(0, '#5ab8ff'); gr.addColorStop(0.5, '#ffffff'); gr.addColorStop(1, '#a87bff');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
    });
    star4(g, R - 2, sunTop + 6, 3.4, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ manga
// Black and white manga: paper with a screentone that deepens to the edges and focus lines rushing to the mark;
// the mark inked with a brush outline, the sun in hatching under a highlight, the lines in screentone.
THEMES.manga = {
  bg(g) {
    const n = noise2(301);
    pixels(g, (x, y) => {
      const d = Math.hypot(x - 54, y - 54);
      let v = 250 - (fbm(n, x / 3, y / 3, 3) - 0.5) * 14; // paper
      // screentone: 45 degree dots growing outward
      const p = 1.7, u = (x + y) / Math.SQRT2 / p, w = (x - y) / Math.SQRT2 / p;
      const du = u - Math.round(u), dw = w - Math.round(w), dd = Math.sqrt(du * du + dw * dw);
      const rad = 0.1 + 0.4 * smooth(24, 56, d);
      if (dd < rad) v = 34;
      return [v, v, v];
    });
    // focus lines: dense black wedges from the edge towards the centre, leaving it white
    const r = rng(311);
    for (let i = 0; i < 230; i++) {
      const a = r() * Math.PI * 2, inner = 29 + r() * 12, w = 0.2 + r() * 1.0;
      const ax = Math.cos(a), ay = Math.sin(a), nx = -ay, ny = ax;
      g.fillStyle = `rgba(10,10,10,${0.7 + r() * 0.3})`; g.beginPath();
      g.moveTo(54 + ax * inner, 54 + ay * inner);
      g.lineTo(54 + ax * 90 + nx * w * 2.2, 54 + ay * 90 + ny * w * 2.2);
      g.lineTo(54 + ax * 90 - nx * w * 2.2, 54 + ay * 90 - ny * w * 2.2); g.closePath(); g.fill();
    }
    // a hand-drawn sound effect at the corner
    g.save(); g.translate(33, 82); g.rotate(-0.18); g.font = '900 10px "WenQuanYi Zen Hei"'; g.textAlign = 'center';
    g.lineJoin = 'round'; g.lineWidth = 2.4; g.strokeStyle = '#fafafa'; g.strokeText('ドン', 0, 0); g.fillStyle = '#0c0c0c'; g.fillText('ドン', 0, 0); g.restore();
  },
  fg(g) {
    // a white clearing behind the mark, as the panel's focus
    const clear = new Path2D(); clear.ellipse(54, 54, 27, 25, 0, 0, Math.PI * 2);
    g.save(); g.filter = `blur(${2.4 * S}px)`; g.fillStyle = '#fafafa'; g.fill(clear); g.restore();
    within(g, sun, () => {
      g.fillStyle = '#141414'; g.fillRect(0, 0, 108, 108);
      // highlight: a white crescent, cross-hatched towards the shade
      const hl = new Path2D(); hl.ellipse(cx - 5, sunTop + 7, 9, 5, -0.25, 0, Math.PI * 2);
      g.fillStyle = '#fafafa'; g.fill(hl);
      g.strokeStyle = '#fafafa'; g.lineWidth = 0.35;
      for (let i = -30; i < 40; i += 1.25) { g.globalAlpha = 0.7; g.beginPath(); g.moveTo(cx - 20 + i, sunTop); g.lineTo(cx - 32 + i, sunTop + 14); g.stroke(); }
      g.globalAlpha = 1;
      within(g, (() => { const p = new Path2D(); p.rect(0, sunTop + 9, 108, 30); return p; })(), () => { g.fillStyle = '#141414'; g.fill(sun); });
    });
    within(g, lines, () => {
      g.fillStyle = '#fafafa'; g.fillRect(0, 0, 108, 108);
      for (let y = 58; y < 72; y += 1.05) for (let x = L; x < R; x += 1.05) { g.fillStyle = '#141414'; g.beginPath(); g.arc(x + ((y / 1.05) % 2) * 0.52, y, 0.34, 0, 7); g.fill(); }
    });
    g.lineJoin = 'round'; g.strokeStyle = '#0c0c0c';
    g.lineWidth = 1.6; g.stroke(sun); g.lineWidth = 1.2; g.stroke(lines);
    // ink flicks
    g.strokeStyle = '#0c0c0c'; g.lineCap = 'round';
    for (const [x0, y0, x1, y1, w] of [[76, 34, 82, 29, 1.2], [79, 40, 86, 38, 0.9], [30, 33, 25, 28, 1.1]]) { g.lineWidth = w; g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1); g.stroke(); }
  },
};

// ------------------------------------------------------------------------------------------------ frost
// A New Year night: deep blue with snow falling in three depths and a few warm lights; the mark frozen into
// ice - frosted crystal with cracks and icicles - with a knitted-velvet Santa hat on the sun, fur trim and a pompom.
THEMES.frost = {
  bg(g) {
    const n = noise2(401), r = rng(409);
    pixels(g, (x, y) => {
      let c = mixc([14, 34, 86], [40, 92, 160], smooth(10, 108, y));
      const f = fbm(n, x / 20, y / 20, 4); c = mixc(c, [70, 120, 190], smooth(0.5, 0.8, f) * 0.35);
      return [c[0], c[1], c[2]];
    });
    // warm bokeh lights low behind
    for (let i = 0; i < 9; i++) { const p = new Path2D(); p.arc(10 + r() * 88, 78 + r() * 26, 2 + r() * 3.5, 0, 7); glow(g, p, ['#ffcf7a', '#ff8c6a', '#9fe0ff'][i % 3], 1.6, 0.35); }
    // snow: far (small, soft), mid, near (big, blurred)
    for (const [count, smin, smax, blur, alpha] of [[260, 0.2, 0.45, 0, 0.6], [90, 0.45, 0.9, 0.25, 0.8], [14, 1.2, 2.2, 0.9, 0.7]]) {
      for (let i = 0; i < count; i++) { const p = new Path2D(); p.arc(r() * 108, r() * 108, smin + r() * (smax - smin), 0, 7);
        g.save(); if (blur) g.filter = `blur(${blur * S}px)`; g.globalAlpha = alpha * (0.5 + r() * 0.5); g.fillStyle = '#ffffff'; g.fill(p); g.restore(); }
    }
  },
  fg(g) {
    const n = noise2(431), n2 = noise2(457);
    shadowUnder(g, both, 0, 1.4, 1.4, 'rgba(0,20,60,0.5)');
    // ice: translucent blue body, frost bloom at the edges, internal cracks
    for (const p of [sun, lines]) within(g, p, () => {
      pixels(g, (x, y) => {
        const f = fbm(n, x / 3.2, y / 3.2, 5), cr = Math.abs(fbm(n2, x / 5, y / 5, 4) - 0.5);
        let c = mixc([170, 222, 250], [96, 168, 228], smooth(0.3, 0.75, f));
        const crack = (1 - smooth(0.0, 0.03, cr)) * 120;
        return [clamp255(c[0] + crack), clamp255(c[1] + crack), clamp255(c[2] + crack * 0.6), 235];
      });
      g.save(); g.lineWidth = 3; g.strokeStyle = 'rgba(255,255,255,0.75)'; g.filter = `blur(${0.9 * S}px)`; g.stroke(p); g.restore(); // frost rim
    });
    g.strokeStyle = 'rgba(235,250,255,0.9)'; g.lineWidth = 0.5; g.stroke(sun); g.stroke(lines);
    // icicles under the lines
    const r = rng(463);
    for (const [y, x0, x1] of [[63.7, 40, 68], [71.1, 47, 61]]) for (let x = x0; x < x1; x += 2.2 + r() * 2) {
      const len = 1.8 + r() * 3.6, w = 0.55 + r() * 0.4;
      const ic = new Path2D(); ic.moveTo(x - w, y - 0.3); ic.quadraticCurveTo(x - w * 0.3, y + len * 0.6, x, y + len); ic.quadraticCurveTo(x + w * 0.3, y + len * 0.6, x + w, y - 0.3); ic.closePath();
      const gr = g.createLinearGradient(0, y, 0, y + len); gr.addColorStop(0, 'rgba(190,232,255,0.95)'); gr.addColorStop(1, 'rgba(255,255,255,0.9)');
      g.fillStyle = gr; g.fill(ic);
    }
    // the hat: sits on the sun's dome, tilted, its tip drooping to the right
    g.save(); g.translate(cx - 1, sunTop + 4.2); g.rotate(0.16);
    const hat = new Path2D();
    hat.moveTo(-15.5, 2.2); hat.bezierCurveTo(-13, -10, -4, -17, 6, -18.5); hat.bezierCurveTo(13, -19.5, 19, -15, 21.5, -8.5);
    hat.bezierCurveTo(18, -11, 14, -11, 11.5, -8); hat.bezierCurveTo(13, -4, 14.5, -0.5, 15.5, 2.2); hat.closePath();
    const nv = noise2(479);
    within(g, hat, () => {
      pixels(g, (x, y) => { // velvet: deep red with a soft sheen and a knit-like fine grain (in device units through the transform-less painter)
        const v = fbm(nv, x * 1.6, y * 0.7, 3), grain = nv(x * 9, y * 9) - 0.5;
        let c = mixc([214, 30, 44], [128, 10, 26], smooth(0.35, 0.8, v));
        return [clamp255(c[0] + grain * 30), clamp255(c[1] + grain * 10), clamp255(c[2] + grain * 12), 255];
      });
      // folds: a darker crease and a lit edge
      g.strokeStyle = 'rgba(70,0,10,0.55)'; g.lineWidth = 1.6; g.filter = `blur(${0.8 * S}px)`;
      g.beginPath(); g.moveTo(-6, 0); g.bezierCurveTo(-2, -8, 4, -13, 12, -14); g.stroke();
      g.strokeStyle = 'rgba(255,150,150,0.45)'; g.lineWidth = 1.2; g.beginPath(); g.moveTo(-12, 0); g.bezierCurveTo(-10, -9, -3, -15, 5, -16.5); g.stroke(); g.filter = 'none';
    });
    // fur: a fluffy band and a pompom made of many soft tufts
    const fur = (cxp, cyp, rx, ry, count, seed) => {
      const rr = rng(seed);
      for (let i = 0; i < count; i++) {
        const a = rr() * Math.PI * 2, d = Math.sqrt(rr());
        const x = cxp + Math.cos(a) * rx * d, y = cyp + Math.sin(a) * ry * d, s = 0.7 + rr() * 1.1;
        const shade = 205 + rr() * 50 - (y - cyp + ry) / (2 * ry) * 30;
        g.fillStyle = `rgba(${shade},${shade},${shade + 6},${0.85})`; g.beginPath(); g.arc(x, y, s, 0, 7); g.fill();
      }
    };
    g.save(); g.filter = `blur(${0.35 * S}px)`; g.fillStyle = 'rgba(0,10,40,0.35)'; g.beginPath(); g.ellipse(0, 3.4, 17.5, 3.6, 0, 0, 7); g.fill(); g.restore();
    fur(0, 1.9, 17.2, 3.2, 520, 487);
    fur(21.8, -7.4, 3.6, 3.6, 170, 491);
    g.restore();
    // a few sparkles of frost
    for (const [x, y, s] of [[34, 50, 2], [74, 61, 1.7], [69, 44, 1.4]]) star4(g, x, y, s, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ jellyfish
// The deep ocean: near black blue with faint light from far above and drifting marine snow; the sun is the
// glowing bell of a jellyfish with its canals, the lines two bioluminescent bands, tentacles trailing below.
THEMES.jelly = {
  bg(g) {
    const n = noise2(501), r = rng(509);
    pixels(g, (x, y) => {
      let c = mixc([10, 46, 96], [1, 8, 24], smooth(0, 100, y));
      const ray = Math.pow(Math.max(0, Math.sin((x * 0.9 - y * 0.35) / 5.5)), 6) * (1 - smooth(10, 70, y)) * 26;
      const haze = (fbm(n, x / 16, y / 16, 4) - 0.5) * 18;
      return [clamp255(c[0] + ray * 0.4 + haze * 0.3), clamp255(c[1] + ray * 0.8 + haze * 0.6), clamp255(c[2] + ray + haze)];
    });
    for (let i = 0; i < 160; i++) { const p = new Path2D(); p.arc(r() * 108, r() * 108, 0.15 + r() * 0.45, 0, 7); g.fillStyle = `rgba(170,220,255,${0.15 + r() * 0.45})`; g.fill(p); }
    // a far jellyfish, small and dim
    const far = new Path2D(); far.ellipse(84, 30, 6, 4.4, 0, Math.PI, Math.PI * 2); far.closePath(); glow(g, far, '#6fd8ff', 1.6, 0.35);
    g.strokeStyle = 'rgba(120,220,255,0.18)'; g.lineWidth = 0.4; for (let i = 0; i < 5; i++) { g.beginPath(); g.moveTo(79.5 + i * 2.3, 30); g.bezierCurveTo(79 + i * 2.3, 36, 81 + i * 2.3, 40, 80 + i * 2.2, 46); g.stroke(); }
  },
  fg(g) {
    // tentacles first, behind: long translucent wavy strands and frilled oral arms
    const r = rng(531);
    for (let i = 0; i < 13; i++) {
      const x0 = L + 2 + i * (R - L - 4) / 12, len = 22 + r() * 18, ph = r() * 6;
      g.strokeStyle = `rgba(${150 + r() * 60},${200 + r() * 40},255,${0.22 + r() * 0.25})`; g.lineWidth = 0.35 + r() * 0.35;
      g.beginPath(); g.moveTo(x0, sunBot);
      for (let t = 0; t <= 1.0001; t += 0.05) g.lineTo(x0 + Math.sin(t * 7 + ph) * 2.2 * t + (x0 - cx) * 0.18 * t, sunBot + t * len);
      g.stroke();
    }
    for (const sx of [-5, 0, 5]) { // oral arms: soft ribbons
      g.save(); g.filter = `blur(${0.3 * S}px)`; g.strokeStyle = 'rgba(255,140,220,0.35)'; g.lineWidth = 1.6; g.lineCap = 'round';
      g.beginPath(); g.moveTo(cx + sx, sunBot); for (let t = 0; t <= 1.0001; t += 0.05) g.lineTo(cx + sx + Math.sin(t * 5 + sx) * 2.4, sunBot + t * 30); g.stroke(); g.restore();
    }
    // the bell
    glow(g, sun, '#5fe0ff', 4, 0.55);
    within(g, sun, () => {
      const gr = g.createRadialGradient(cx, sunCy - 4, 2, cx, sunCy, sunR + 1);
      gr.addColorStop(0, 'rgba(255,190,240,0.55)'); gr.addColorStop(0.55, 'rgba(120,200,255,0.45)'); gr.addColorStop(1, 'rgba(150,255,255,0.9)');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
      g.strokeStyle = 'rgba(255,200,250,0.6)'; g.lineWidth = 0.5;
      for (let i = 0; i < 9; i++) { const a = Math.PI + (i + 0.5) / 9 * Math.PI; g.beginPath(); g.moveTo(cx, sunCy); g.lineTo(cx + Math.cos(a) * sunR, sunCy + Math.sin(a) * sunR); g.stroke(); } // radial canals
      g.fillStyle = 'rgba(255,170,230,0.5)'; for (let i = 0; i < 4; i++) { const a = Math.PI * (1.2 + i * 0.2); const p = new Path2D(); p.ellipse(cx + Math.cos(a) * 6, sunCy + Math.sin(a) * 6, 2.6, 1.4, a, 0, 7); g.fill(p); } // gonads
      const rim = g.createLinearGradient(0, sunTop, 0, sunTop + 6); rim.addColorStop(0, 'rgba(255,255,255,0.7)'); rim.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = rim; g.fillRect(0, sunTop, 108, 6);
    });
    g.strokeStyle = 'rgba(190,250,255,0.95)'; g.lineWidth = 0.6; g.stroke(sun);
    // the lines: two bioluminescent bands
    glow(g, lines, '#43f0ff', 2.2, 0.85);
    within(g, lines, () => { const gr = g.createLinearGradient(L, 0, R, 0); gr.addColorStop(0, '#5ae8ff'); gr.addColorStop(0.5, '#e9fdff'); gr.addColorStop(1, '#ff8ce0'); g.fillStyle = gr; g.fillRect(0, 0, 108, 108); });
    for (let i = 0; i < 40; i++) { const x = L + r() * (R - L), y = sunTop + r() * 40; g.fillStyle = `rgba(160,255,255,${0.3 + r() * 0.5})`; g.beginPath(); g.arc(x, y, 0.15 + r() * 0.3, 0, 7); g.fill(); }
  },
};

// ------------------------------------------------------------------------------------------------ spin
// A homage to a cross-country race of the old West in a famous manga style: a blazing sunset desert, a golden
// spiral of the golden ratio, menacing sound effects; the sun a grooved steel ball, the lines two gold bars.
THEMES.spin = {
  bg(g) {
    const n = noise2(601);
    pixels(g, (x, y) => {
      let c;
      if (y < 74) c = mixc(mixc([92, 40, 160], [238, 70, 120], smooth(0, 50, y)), [255, 170, 70], smooth(44, 74, y));
      else c = mixc([150, 70, 60], [70, 30, 50], smooth(74, 108, y)); // dunes
      const dune = 74 + 3.5 * Math.sin(x / 9) + 2 * Math.sin(x / 4.3 + 1);
      if (y >= dune) { c = mixc([120, 50, 70], [44, 18, 40], smooth(dune, 108, y)); const ripple = Math.sin((y - dune) * 3 + Math.sin(x / 5) * 2) * 6; c = c.map(v => v + ripple); }
      const grain = (n(x * 12, y * 12) - 0.5) * 16;
      return [clamp255(c[0] + grain), clamp255(c[1] + grain), clamp255(c[2] + grain)];
    });
    // the golden spiral (quarter arcs in Fibonacci squares), centred on the mark
    g.save(); g.translate(54, 54); g.rotate(-0.3); g.strokeStyle = 'rgba(255,214,110,0.8)'; g.lineWidth = 0.9;
    let a = 0.9, x = 0, y = 0, dir = 0;
    const fib = [1, 1, 2, 3, 5, 8, 13, 21, 34, 55];
    for (let i = 0; i < fib.length; i++) {
      const s = fib[i] * a; const ang = dir * Math.PI / 2;
      const ccx = x - Math.cos(ang + Math.PI / 2) * s, ccy = y - Math.sin(ang + Math.PI / 2) * s;
      g.beginPath(); g.arc(ccx, ccy, s, ang + Math.PI / 2, ang + Math.PI, false); g.stroke();
      x = ccx + Math.cos(ang + Math.PI) * s; y = ccy + Math.sin(ang + Math.PI) * s; dir++;
    }
    g.restore();
    // menacing sound effects
    const sfx = (x, y, rot, size) => { g.save(); g.translate(x, y); g.rotate(rot); g.font = `900 ${size}px "WenQuanYi Zen Hei"`; g.textAlign = 'center';
      g.lineJoin = 'round'; g.lineWidth = size * 0.26; g.strokeStyle = '#1a0726'; g.strokeText('ゴ', 0, 0); g.fillStyle = '#b25cff'; g.fillText('ゴ', 0, 0);
      g.lineWidth = size * 0.07; g.strokeStyle = '#f2d6ff'; g.strokeText('ゴ', 0, 0); g.restore(); };
    sfx(30, 33, -0.2, 10); sfx(38, 25, -0.1, 7); sfx(80, 82, 0.15, 9); sfx(73, 89, 0.25, 6);
  },
  fg(g) {
    shadowUnder(g, both, 1.2, 1.8, 1.0, 'rgba(30,0,40,0.55)');
    // the steel ball: chrome with the sky in it, and its spiral grooves
    within(g, sun, () => {
      const gr = g.createLinearGradient(0, sunTop, 0, sunBot); gr.addColorStop(0, '#f6f4ff'); gr.addColorStop(0.35, '#9a9cb6'); gr.addColorStop(0.55, '#ffd7c2'); gr.addColorStop(0.75, '#5a5574'); gr.addColorStop(1, '#2d2840');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
      g.strokeStyle = 'rgba(30,24,44,0.75)'; g.lineWidth = 0.75;
      for (let k = 0; k < 7; k++) { g.beginPath(); for (let t = 0; t <= 1.0001; t += 0.02) { const a = Math.PI + t * Math.PI, rr = sunR * (0.25 + 0.75 * t) * (0.4 + k * 0.1);
        const px = cx + Math.cos(a + k * 0.9) * rr * 1.1, py = sunCy + Math.sin(a + k * 0.9) * rr; t === 0 ? g.moveTo(px, py) : g.lineTo(px, py); } g.stroke(); }
      g.strokeStyle = 'rgba(255,255,255,0.35)'; g.lineWidth = 0.4;
      for (let k = 1; k < 5; k++) { g.beginPath(); g.arc(cx, sunCy, sunR * k / 5, Math.PI, Math.PI * 2); g.stroke(); }
      const sh = new Path2D(); sh.ellipse(cx - 6, sunTop + 5.5, 6, 2.6, -0.3, 0, 7); g.fillStyle = 'rgba(255,255,255,0.85)'; g.fill(sh);
    });
    within(g, lines, () => {
      const gr = g.createLinearGradient(0, 59, 0, 71.3); gr.addColorStop(0, '#fff2b0'); gr.addColorStop(0.4, '#f0b52e'); gr.addColorStop(0.7, '#a66a12'); gr.addColorStop(1, '#ffd36a');
      g.fillStyle = gr; g.fillRect(0, 0, 108, 108);
    });
    g.lineJoin = 'round'; g.strokeStyle = '#1a0726'; g.lineWidth = 1.2; g.stroke(sun); g.stroke(lines);
    // motion: arcs of the spin around the ball
    g.strokeStyle = 'rgba(255,240,200,0.85)'; g.lineCap = 'round';
    for (const [rr, a0, a1, w] of [[sunR + 4, 1.05, 1.35, 1.0], [sunR + 6.5, 1.1, 1.28, 0.7], [sunR + 4, 1.62, 1.92, 1.0]]) { g.lineWidth = w; g.beginPath(); g.arc(cx, sunCy, rr, Math.PI * a0, Math.PI * a1); g.stroke(); }
  },
};

// ------------------------------------------------------------------------------------------------ droid
// In the manner of a famous green robot mascot (its look, not the robot): the sun is a green robot head with two
// antennae and eyes, the lines a green body and arms, on a deep navy with a faint grid.
THEMES.droid = {
  bg(g) {
    pixels(g, (x, y) => {
      let c = mixc([7, 48, 66], [4, 30, 44], smooth(0, 108, y + x * 0.3));
      const gx = Math.abs(((x + 3) % 6) - 3), gy = Math.abs(((y + 3) % 6) - 3);
      if (Math.hypot(gx, gy) < 0.32) c = c.map((v, i) => v + [20, 60, 40][i]);
      return [c[0], c[1], c[2]];
    });
    const halo = new Path2D(); halo.arc(54, 56, 30, 0, 7); glow(g, halo, '#3ddc84', 10, 0.18);
  },
  fg(g) {
    const green = '#3DDC84';
    shadowUnder(g, both, 0, 1.4, 1.2, 'rgba(0,0,0,0.35)');
    // antennae
    g.strokeStyle = green; g.lineCap = 'round'; g.lineWidth = 2.1;
    for (const s of [-1, 1]) { const a = -Math.PI / 2 + s * 0.52; const x0 = cx + Math.cos(a) * (sunR - 2), y0 = sunCy + Math.sin(a) * (sunR - 2);
      g.beginPath(); g.moveTo(x0, y0); g.lineTo(x0 + Math.cos(a) * 6.2, y0 + Math.sin(a) * 6.2); g.stroke(); }
    g.fillStyle = green; g.fill(sun); g.fill(lines);
    // eyes
    g.fillStyle = '#073042'; for (const s of [-1, 1]) { g.beginPath(); g.arc(cx + s * 7.2, sunCy - 7.5, 1.75, 0, 7); g.fill(); }
    // a soft light on the head
    within(g, sun, () => { const gr = g.createLinearGradient(0, sunTop, 0, sunBot); gr.addColorStop(0, 'rgba(255,255,255,0.18)'); gr.addColorStop(1, 'rgba(0,0,0,0.08)'); g.fillStyle = gr; g.fillRect(0, 0, 108, 108); });
  },
};
