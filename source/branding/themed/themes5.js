// Solegram themed icons, set 5: a space agency homage and four TV homages (no names, logos or characters).

function cloudPath(x, y, w, h) { // a puffy cartoon cloud of overlapping lobes
  const p = new Path2D(); const lobes = [[0, 0.62, 0.3], [0.22, 0.4, 0.36], [0.5, 0.3, 0.42], [0.76, 0.42, 0.34], [0.95, 0.64, 0.26]];
  for (const [u, v, r] of lobes) { p.moveTo(x + u * w + r * h, y + v * h); p.arc(x + u * w, y + v * h, r * h, 0, Math.PI * 2); }
  p.rect(x, y + 0.55 * h, w * 0.95, 0.38 * h);
  p.moveTo(x + w * 0.93, y + 0.6 * h); p.ellipse(x + w * 0.48, y + 0.6 * h, w * 0.45, h * 0.32, 0, 0, Math.PI * 2);
  return p;
}

// ------------------------------------------------------------------------------------------------ agency
// The classic space agency look: a royal blue disc full of white stars on deep space, a white orbit around the
// mark and a red flight trajectory (its own curved, tapering shape).
THEMES.agency = {
  bg(g) {
    const n = noise2(19), n2 = noise2(29);
    pixels(g, (x, y) => { // royal blue everywhere, lighter towards the upper left, with a printed fabric grain
      const k = Math.hypot(x - 42, y - 38) / 80;
      const grain = (n(x * 26, y * 26) - 0.5) * 10 + (fbm(n2, x / 3, y / 3, 3) - 0.5) * 12;
      return [26 - 16 * k + grain, 92 - 40 * k + grain, 204 - 76 * k + grain * 1.4];
    });
    const r = rng(3); g.fillStyle = '#ffffff';
    for (let i = 0; i < 160; i++) { g.globalAlpha = 0.25 + r() * 0.45; g.beginPath(); g.arc(r() * 108, r() * 108, 0.08 + r() * 0.18, 0, 7); g.fill(); }
    g.globalAlpha = 1;
    const stars = [[30, 33, 0.95], [37, 27, 0.45], [44, 23, 0.6], [63, 22, 0.5], [73, 27, 0.85], [80, 36, 0.45], [26, 46, 0.5], [84, 49, 0.55],
      [28, 67, 0.75], [36, 76, 0.45], [46, 83, 0.6], [58, 85, 0.4], [70, 82, 0.75], [79, 73, 0.5], [86, 62, 0.4], [52, 20, 0.35], [22, 56, 0.35]];
    for (const [x, y, s] of stars) { g.beginPath(); g.arc(x, y, s, 0, 7); g.fill(); if (s > 0.7) star4(g, x, y, s * 2.3, '#ffffff', 0.7); }
    const v = g.createRadialGradient(54, 50, 40, 54, 54, 80); v.addColorStop(0, 'rgba(0,10,40,0)'); v.addColorStop(1, 'rgba(0,10,40,0.35)'); g.fillStyle = v; g.fillRect(0, 0, 108, 108);
  },
  fg(g) {
    // the red chevron: two strokes sweeping in from the left and meeting in a sharp point at the upper right;
    // the upper one passes behind the mark, the lower one in front of its lower part
    const apex = [81.5, 35.5];
    const prong = (p0, p1, p2, wmax) => {
      const P = [p0, p1, p2, apex];
      const bez = t => { const u = 1 - t; return [0, 1].map(i => u * u * u * P[0][i] + 3 * u * u * t * P[1][i] + 3 * u * t * t * P[2][i] + t * t * t * P[3][i]); };
      const a = [], c = [];
      for (let i = 0; i <= 60; i++) {
        const t = i / 60, [x, y] = bez(t), [x2, y2] = bez(Math.min(1, t + 0.005)), [x1, y1] = bez(Math.max(0, t - 0.005));
        const l = Math.hypot(x2 - x1, y2 - y1) || 1, nx = -(y2 - y1) / l, ny = (x2 - x1) / l;
        const w = wmax * Math.pow(t, 0.55) * Math.pow(1 - t, 0.45) * 2.0;      // thin at the left tip, full, then sharp at the apex
        a.push([x + nx * w, y + ny * w]); c.push([x - nx * w, y - ny * w]);
      }
      const path = new Path2D(); path.moveTo(...a[0]); for (const q of a) path.lineTo(...q); for (let i = c.length - 1; i >= 0; i--) path.lineTo(...c[i]); path.closePath();
      return path;
    };
    const back = prong([12, 55], [30, 46], [58, 41], 2.5);
    const front = prong([15, 84], [42, 82], [68, 72], 3.3);
    const paint = (path) => {
      g.save(); g.filter = `blur(${0.8 * S}px)`; g.globalAlpha = 0.35; g.fillStyle = '#200'; g.translate(0.4, 0.8); g.fill(path); g.restore();
      const red = g.createLinearGradient(10, 84, apex[0], apex[1]); red.addColorStop(0, '#c41f19'); red.addColorStop(0.55, '#e8352a'); red.addColorStop(1, '#f24a36');
      g.fillStyle = red; g.fill(path);
      g.save(); g.clip(path); g.fillStyle = 'rgba(255,220,200,0.18)'; g.translate(0, -0.6); g.fill(path); g.restore();
    };
    paint(back);
    g.save(); g.filter = `blur(${1.2 * S}px)`; g.globalAlpha = 0.45; g.fillStyle = '#04122e'; g.translate(0.3, 1.2); g.fill(both); g.restore();
    within(g, both, () => {
      const base = g.createLinearGradient(0, T, 0, B); base.addColorStop(0, '#ffffff'); base.addColorStop(1, '#e6edf8'); g.fillStyle = base; g.fillRect(0, 0, 108, 108);
      const n = noise2(8); pixels(g, (x, y) => { const v = (n(x * 20, y * 20) - 0.5) * 18; return [128, 140, 170, Math.max(0, v * 2)]; });
    });
    bevel(g, 'rgba(255,255,255,1)', 'rgba(30,60,120,0.45)', 0.8);
    paint(front);
  },
};

// ------------------------------------------------------------------------------------------------ lab
// A chemistry lab: green vapour in the dark and a periodic table tile; Solegram's symbol Sg really is element 106
// (seaborgium, mass of its longest-lived isotope [269]). The mark in white crystal on the tile.
THEMES.lab = {
  bg(g) {
    const n = noise2(61), n2 = noise2(62);
    pixels(g, (x, y) => {
      const k = Math.hypot(x - 54, y - 50) / 80;
      let r = 10 + 16 * (1 - k), gg = 18 + 26 * (1 - k), b = 12 + 12 * (1 - k);
      const wisp = Math.pow(Math.max(0, fbm(n, x / 14 + fbm(n2, x / 10, y / 10, 3) * 2, y / 20, 6) - 0.4) * 2, 1.5);
      r += wisp * 70; gg += wisp * 78; b += wisp * 40;
      return [r, gg, b];
    });
    // the tile
    const x0 = 29, y0 = 27.5, w = 50, h = 52;
    g.save(); g.filter = `blur(${2.2 * S}px)`; g.fillStyle = 'rgba(0,0,0,0.6)'; g.beginPath(); g.roundRect(x0 + 1, y0 + 2.4, w, h, 5); g.fill(); g.restore();
    const tile = new Path2D(); tile.roundRect(x0, y0, w, h, 5);
    within(g, tile, () => {
      const tg = g.createLinearGradient(x0, y0, x0 + w * 0.4, y0 + h); tg.addColorStop(0, '#3e7a4a'); tg.addColorStop(0.5, '#285c36'); tg.addColorStop(1, '#173a23'); g.fillStyle = tg; g.fillRect(0, 0, 108, 108);
      const n3 = noise2(5); pixels(g, (x, y) => { const v = (n3(x * 18, y * 18) - 0.5); return [v > 0 ? 255 : 0, v > 0 ? 255 : 0, v > 0 ? 255 : 0, Math.abs(v) * 30]; });
      const sh = g.createLinearGradient(x0, y0, x0 + w, y0 + h); sh.addColorStop(0, 'rgba(255,255,255,0.28)'); sh.addColorStop(0.35, 'rgba(255,255,255,0.0)'); sh.addColorStop(1, 'rgba(0,30,10,0.3)'); g.fillStyle = sh; g.fillRect(0, 0, 108, 108);
    });
    g.strokeStyle = 'rgba(220,255,225,0.85)'; g.lineWidth = 0.5; g.beginPath(); g.roundRect(x0 + 2, y0 + 2, w - 4, h - 4, 3.6); g.stroke();
    g.strokeStyle = 'rgba(10,40,15,0.8)'; g.lineWidth = 0.6; g.stroke(tile);
    g.fillStyle = '#ffffff'; g.textBaseline = 'alphabetic';
    g.font = 'bold 5.6px "DejaVu Sans", Arial, sans-serif'; g.fillText('106', x0 + 4.2, y0 + 8.6);
    g.font = 'bold 6.4px "DejaVu Sans", Arial, sans-serif'; g.textAlign = 'right'; g.fillText('Sg', x0 + w - 4.2, y0 + 9);
    g.font = '4.2px "DejaVu Sans", Arial, sans-serif'; g.textAlign = 'center'; g.fillText('[269]', x0 + w / 2, y0 + h - 2.6);
    g.textAlign = 'left';
  },
  fg(g) {
    glow(g, both, '#cfe8d6', 2.2, 0.3);
    within(g, both, () => { // white crystal: facets in cool tints
      const base = g.createLinearGradient(0, T, 0, B); base.addColorStop(0, '#f4f7f2'); base.addColorStop(1, '#cfdcd2'); g.fillStyle = base; g.fillRect(0, 0, 108, 108);
      const r = rng(23);
      for (let i = 0; i < 80; i++) { const x = L - 2 + r() * (R - L + 4), y = T - 2 + r() * (B - T + 4), s = 2 + r() * 5;
        g.beginPath(); g.moveTo(x, y); g.lineTo(x + s * (r() - 0.2), y + s * r() * 0.8); g.lineTo(x - s * r(), y + s * (r() - 0.1)); g.closePath();
        g.fillStyle = r() < 0.55 ? `rgba(255,255,255,${0.3 + r() * 0.5})` : `rgba(150,215,190,${0.15 + r() * 0.25})`; g.fill(); }
    });
    bevel(g, 'rgba(255,255,255,1)', 'rgba(20,90,50,0.55)');
    for (const [x, y, s] of [[45, 43, 2.2], [67, 61, 1.5]]) star4(g, x, y, s, '#ffffff', 0.95);
  },
};


// ------------------------------------------------------------------------------------------------ backrooms
// The endless yellow rooms, as on the famous photo: a mono-yellow wall with patterned wallpaper and damp, a drop
// ceiling with fluorescent panels, a damp carpet, the room turning a corner on the right; an overexposed amateur
// photo. The mark is cut into the wall as openings into the next room: bright, with the wall's thickness at the
// edges and light spilling out.
THEMES.backrooms = {
  bg(g) {
    const n = noise2(111), n2 = noise2(112), n3 = noise2(113);
    const ceil = 29, floor = 82, corner = 88;      // the back wall between ceiling and carpet; the right wall from the corner
    pixels(g, (x, y) => {
      let r, gg, b;
      // the right wall turns towards us after the corner: its edges converge to a point far on the left
      const sideTop = ceil - (x - corner) * 0.55, sideBot = floor + (x - corner) * 0.45;
      const onSide = x > corner && y > sideTop && y < sideBot;
      const top = x > corner ? sideTop : ceil, bot = x > corner ? sideBot : floor;
      if (y < top) { // the drop ceiling: tiles, grid, two panels
        // a plane above the eye (the horizon at y = 56): depth and across from the pixel, tiles 1 x 1
        const hz = 56, z = (hz - top) / Math.max(0.5, hz - y), wx = (x - 54) / (hz - y) * 3.4, wz = 1 / z * 2.4;
        const fz = wz - Math.floor(wz), fx = wx - Math.floor(wx);
        const lw = 0.035 * (hz - y) / 14 + 0.02;
        const grid = fz < lw * 1.6 || fx < lw;
        r = 234; gg = 224; b = 170; if (grid) { r -= 40; gg -= 38; b -= 30; }
        const lamp = Math.floor(wz) % 2 === 0 && ((Math.floor(wx) % 3) + 3) % 3 === 1 && fz > 0.18 && fz < 0.82 && fx > 0.12 && fx < 0.88;
        if (lamp) { r = 255; gg = 253; b = 236; }
        const pock = (n3(x * 20, y * 20) - 0.5) * 16; r += pock; gg += pock; b += pock;
      } else if (y > bot) { // damp carpet, mottled, a darker wet patch
        const mot = (fbm(n2, x / 3.4, y / 1.6, 5) - 0.5) * 46 + (n3(x * 30, y * 30) - 0.5) * 22;
        const wet = Math.max(0, fbm(n, x / 9 + 3, y / 4, 4) - 0.56) * 110;
        r = 200 + mot - wet; gg = 176 + mot * 0.92 - wet; b = 96 + mot * 0.55 - wet * 0.6;
      } else { // wallpaper
        const wx = onSide ? (x - corner) * 3 + 200 : x, wy = onSide ? (y - sideTop) / (sideBot - sideTop) * 62 + ceil : y;
        // the pattern: thin vertical bands of small chevrons, printed a shade darker
        const band = ((wx % 7) + 7) % 7, chev = Math.abs(((wy + Math.abs(band - 3.5) * 0.8) % 3) - 1.5) < 0.18 && band > 1.2 && band < 5.8;
        const stripe = band < 0.25 ? -14 : 0;
        const damp = Math.max(0, fbm(n, wx / 8, wy / 6, 5) - 0.53) * (60 + Math.max(0, wy - 60) * 4);
        r = 228 - (chev ? 16 : 0) + stripe - damp; gg = 210 - (chev ? 16 : 0) + stripe - damp * 0.95; b = 118 - (chev ? 10 : 0) + stripe * 0.6 - damp * 0.7;
        if (onSide) { r *= 0.86; gg *= 0.85; b *= 0.82; }                         // the side wall is a little darker
        if (y > bot - 2.2) { r *= 0.72; gg *= 0.7; b *= 0.62; }                   // the baseboard
        const near = Math.abs(x - corner) < 0.5 && !onSide; if (near) { r *= 0.8; gg *= 0.78; b *= 0.72; }
      }
      // overexposed, warm, slightly washed: like a cheap camera
      const lightFall = 1 - Math.max(0, (Math.hypot(x - 40, y - 26) - 30) / 140);
      r = r * lightFall + 14; gg = gg * lightFall + 12; b = b * lightFall + 2;
      return [Math.min(255, r), Math.min(255, gg), Math.min(255, b)];
    });
    // bloom of the panels
    { const c2 = document.createElement('canvas'); c2.width = W; c2.height = W; const c2g = c2.getContext('2d'); c2g.drawImage(document.getElementById('c'), 0, 0);
      const id = c2g.getImageData(0, 0, W, W), d = id.data; for (let i = 0; i < d.length; i += 4) { if (d[i] < 250 || d[i + 2] < 225) d[i + 3] = 0; } c2g.putImageData(id, 0, 0);
      g.save(); g.setTransform(1, 0, 0, 1, 0, 0); g.globalCompositeOperation = 'lighter'; g.filter = `blur(${2 * S}px)`; g.globalAlpha = 0.6; g.drawImage(c2, 0, 0); g.restore(); }
    // sensor noise, a little chroma, a soft vignette
    const n4 = noise2(9); pixels(g, (x, y) => { const v = n4(x * 38, y * 38); return v > 0.5 ? [255, 248, 210, (v - 0.5) * 46] : [60, 40, 0, (0.5 - v) * 46]; });
    const vg = g.createRadialGradient(50, 46, 34, 54, 54, 82); vg.addColorStop(0, 'rgba(50,35,0,0)'); vg.addColorStop(1, 'rgba(50,35,0,0.4)'); g.fillStyle = vg; g.fillRect(0, 0, 108, 108);
  },
  fg(g) {
    // light spilling out of the openings onto the wall
    glow(g, both, '#fff3bf', 4.2, 0.8);
    const opening = (path) => within(g, path, () => {
      // the reveal: the wall's thickness, seen on the left and at the top of each opening
      g.fillStyle = '#c9b468'; g.fillRect(0, 0, 108, 108);
      const rv = g.createLinearGradient(L, 0, L + 4, 0); rv.addColorStop(0, 'rgba(120,100,40,0.55)'); rv.addColorStop(1, 'rgba(120,100,40,0)'); g.fillStyle = rv; g.fillRect(0, 0, 108, 108);
      g.save(); g.translate(1.5, 1.2);
      // the next room: brighter yellow, washed out by light, its carpet at the bottom, a far doorway
      const room = g.createLinearGradient(0, T, 0, B); room.addColorStop(0, '#fffdf0'); room.addColorStop(0.65, '#fff4c4'); room.addColorStop(1, '#f2df98');
      g.fillStyle = room; g.fill(path);
      g.save(); g.clip(path);
      g.fillStyle = 'rgba(214,190,120,0.75)'; g.fillRect(0, sunBot - 3.2, 108, 3.2);                 // the far carpet in the sun opening
      // a far doorway: a darker opening in the far wall with its own lit edge
      const dx0 = cx + 5.5, dy0 = sunTop + 7.5, dw = 3.6, dh = sunBot - 3.2 - dy0;
      g.fillStyle = 'rgba(196,170,96,0.85)'; g.fillRect(dx0, dy0, dw, dh);
      g.fillStyle = 'rgba(255,250,225,0.9)'; g.fillRect(dx0 + dw - 0.45, dy0, 0.45, dh);
      g.fillStyle = 'rgba(150,126,60,0.5)'; g.fillRect(dx0, dy0, 0.4, dh);
      g.restore();
      g.restore();
    });
    opening(sun); opening(lines);
    // a fine edge of the cut wallpaper
    g.strokeStyle = 'rgba(150,128,60,0.6)'; g.lineWidth = 0.3; g.stroke(sun); g.stroke(lines);
  },
};

// ------------------------------------------------------------------------------------------------ dalgona
// A sweet from the playground: mint green tracksuit fabric, a round tin and a honeycomb candy with the mark
// pressed into it.
THEMES.dalgona = {
  bg(g) {
    const n = noise2(65);
    pixels(g, (x, y) => {
      const k = Math.hypot(x - 46, y - 40) / 90;
      const twill = Math.sin((x + y) * 5.2) * 6 + Math.sin((x - y) * 0.9) * 2;
      const fib = (n(x * 24, y * 24) - 0.5) * 14;
      return [18 + 22 * (1 - k) + twill * 0.5 + fib, 132 + 40 * (1 - k) + twill + fib, 116 + 36 * (1 - k) + twill + fib];
    });
    // the tin lid
    g.save(); g.filter = `blur(${2 * S}px)`; g.fillStyle = 'rgba(0,40,30,0.55)'; g.beginPath(); g.arc(55, 56.5, 33.5, 0, 7); g.fill(); g.restore();
    const tin = g.createRadialGradient(46, 44, 4, 54, 54, 34); tin.addColorStop(0, '#f2f4f5'); tin.addColorStop(0.7, '#b9bfc4'); tin.addColorStop(1, '#7e868c');
    g.fillStyle = tin; g.beginPath(); g.arc(54, 54, 33, 0, 7); g.fill();
    g.strokeStyle = 'rgba(255,255,255,0.35)'; g.lineWidth = 0.12; for (let k = 0; k < 18; k++) { g.beginPath(); g.arc(54, 54, 30.5 + k * 0.13, 0, 7); g.stroke(); }
    g.strokeStyle = 'rgba(60,66,70,0.6)'; g.lineWidth = 0.5; g.beginPath(); g.arc(54, 54, 30.2, 0, 7); g.stroke();
    // the candy: caramel with bubbles, darker at the edge
    const candy = new Path2D(); candy.arc(54, 54, 28.5, 0, 7);
    g.save(); g.filter = `blur(${0.8 * S}px)`; g.fillStyle = 'rgba(60,30,0,0.5)'; g.translate(0.3, 0.8); g.fill(candy); g.restore();
    within(g, candy, () => {
      const n2 = noise2(66);
      pixels(g, (x, y) => {
        const d = Math.hypot(x - 54, y - 54) / 28.5;
        let r = 226 - d * d * 60, gg = 156 - d * d * 70, b = 62 - d * d * 40;
        const mot = (fbm(n2, x / 3, y / 3, 4) - 0.5) * 34; r += mot; gg += mot * 0.8; b += mot * 0.4;
        return [r, gg, b];
      });
      const r = rng(70);
      for (let i = 0; i < 420; i++) { // honeycomb bubbles
        const a = r() * 6.28, rad = Math.sqrt(r()) * 28, x = 54 + Math.cos(a) * rad, y = 54 + Math.sin(a) * rad, s = 0.25 + r() * r() * 1.1;
        const bub = g.createRadialGradient(x - s * 0.3, y - s * 0.3, s * 0.1, x, y, s);
        bub.addColorStop(0, 'rgba(255,230,170,0.75)'); bub.addColorStop(0.7, 'rgba(170,90,20,0.35)'); bub.addColorStop(1, 'rgba(110,50,5,0.55)');
        g.fillStyle = bub; g.beginPath(); g.arc(x, y, s, 0, 7); g.fill();
      }
      const gl = g.createLinearGradient(30, 30, 70, 70); gl.addColorStop(0.15, 'rgba(255,255,255,0)'); gl.addColorStop(0.3, 'rgba(255,248,225,0.32)'); gl.addColorStop(0.45, 'rgba(255,255,255,0)'); g.fillStyle = gl; g.fillRect(0, 0, 108, 108);
    });
    // the needle on the right, lying on the candy
    {
      const x0 = 78.2, y0 = 39.5, x1 = 83.6, y1 = 68.5, len = Math.hypot(x1 - x0, y1 - y0), ang = Math.atan2(y1 - y0, x1 - x0);
      g.save(); g.translate(x0, y0); g.rotate(ang);
      const needle = new Path2D(); // the eye end rounded, the body even, the point tapering
      needle.moveTo(0, -0.82); needle.lineTo(len - 6, -0.68); needle.quadraticCurveTo(len - 1.5, -0.32, len, 0); needle.quadraticCurveTo(len - 1.5, 0.32, len - 6, 0.68);
      needle.lineTo(0, 0.82); needle.arc(0, 0, 0.82, Math.PI / 2, Math.PI * 1.5); needle.closePath();
      g.save(); g.filter = `blur(${0.5 * S}px)`; g.globalAlpha = 0.55; g.fillStyle = '#3a1a00'; g.translate(0.5, 0.9); g.fill(needle); g.restore();
      const steel = g.createLinearGradient(0, -0.82, 0, 0.82); steel.addColorStop(0, '#6c737a'); steel.addColorStop(0.3, '#f7f9fb'); steel.addColorStop(0.55, '#b5bcc3'); steel.addColorStop(1, '#4b5258');
      g.fillStyle = steel; g.fill(needle);
      g.fillStyle = '#d18a3a'; g.beginPath(); g.ellipse(1.8, 0, 1.2, 0.32, 0, 0, 7); g.fill();                  // the eye, candy showing through
      g.strokeStyle = 'rgba(60,66,72,0.8)'; g.lineWidth = 0.1; g.beginPath(); g.ellipse(1.8, 0, 1.2, 0.32, 0, 0, 7); g.stroke();
      g.strokeStyle = 'rgba(255,255,255,0.9)'; g.lineWidth = 0.14; g.beginPath(); g.moveTo(3.4, -0.22); g.lineTo(len - 5, -0.18); g.stroke();
      g.restore();
    }
    // a hairline crack
    g.strokeStyle = 'rgba(90,40,5,0.6)'; g.lineWidth = 0.18; g.beginPath(); g.moveTo(79, 64); g.lineTo(75.5, 66); g.lineTo(76.4, 69.2); g.lineTo(73, 72); g.stroke();
  },
  fg(g) {
    // pressed in: a smooth, slightly lighter shape, shadowed along its upper edge and lit along its lower edge
    within(g, both, () => {
      g.fillStyle = 'rgba(236,170,80,0.55)'; g.fillRect(0, 0, 108, 108);
      g.save(); g.translate(0, 0.6); g.lineWidth = 1.1; g.strokeStyle = 'rgba(90,40,0,0.85)'; g.filter = `blur(${0.35 * S}px)`; g.stroke(sun); g.stroke(lines); g.restore();
      g.save(); g.translate(0, -0.6); g.lineWidth = 0.9; g.strokeStyle = 'rgba(255,236,190,0.75)'; g.filter = `blur(${0.3 * S}px)`; g.stroke(sun); g.stroke(lines); g.restore();
    });
    g.strokeStyle = 'rgba(80,36,0,0.75)'; g.lineWidth = 0.32; g.stroke(sun); g.stroke(lines);   // the pressed line itself
  },
};

// ------------------------------------------------------------------------------------------------ door
// An apartment door: purple paint on wood with grain, brush marks and worn edges, raised panels; the mark inside
// a yellow painted frame.
THEMES.door = {
  bg(g) {
    const n = noise2(83), n2 = noise2(84);
    pixels(g, (x, y) => {
      const grain = fbm(n, x / 1.4, y / 16, 5), streak = (n2(x * 3, y * 0.4) - 0.5) * 10;
      const k = Math.hypot(x - 44, y - 40) / 90;
      let r = 104 + (grain - 0.5) * 30 + streak, gg = 62 + (grain - 0.5) * 22 + streak * 0.7, b = 150 + (grain - 0.5) * 34 + streak;
      r *= 1 - k * 0.45; gg *= 1 - k * 0.45; b *= 1 - k * 0.4;
      return [r, gg, b];
    });
    // raised panels above and below, with bevelled edges
    const panel = (x, y, w, h) => {
      g.save(); g.strokeStyle = 'rgba(255,230,255,0.28)'; g.lineWidth = 0.9; g.strokeRect(x, y, w, h);
      g.strokeStyle = 'rgba(25,8,45,0.55)'; g.lineWidth = 0.9; g.strokeRect(x + 1.1, y + 1.1, w - 2.2, h - 2.2);
      g.restore();
    };
    panel(14, 2, 80, 14); panel(14, 92, 80, 14);
    // worn paint near the edges: lighter wood showing through
    const r = rng(12);
    for (let i = 0; i < 40; i++) { const x = r() < 0.5 ? r() * 10 : 98 + r() * 10, y = r() * 108; g.fillStyle = `rgba(200,170,130,${0.15 + r() * 0.25})`; g.beginPath(); g.ellipse(x, y, 0.4 + r() * 1.2, 0.2 + r() * 0.6, 0, 0, 7); g.fill(); }
    // the peephole above
    const ph = g.createRadialGradient(53.6, 22.6, 0.1, 54, 23, 2); ph.addColorStop(0, '#fff6d0'); ph.addColorStop(0.5, '#c79a3a'); ph.addColorStop(1, '#5a3e10');
    g.fillStyle = ph; g.beginPath(); g.arc(54, 23, 1.7, 0, 7); g.fill(); g.fillStyle = '#20140a'; g.beginPath(); g.arc(54, 23, 0.6, 0, 7); g.fill();
  },
  fg(g) {
    // the yellow frame: an oval of painted moulding with a notched rim
    const outer = new Path2D(), inner = new Path2D();
    outer.ellipse(54, 54.5, 28.5, 23.5, 0, 0, 7); inner.ellipse(54, 54.5, 23.2, 18.3, 0, 0, 7);
    const ring = new Path2D(); ring.addPath(outer); ring.addPath(inner);
    g.save(); g.filter = `blur(${1.4 * S}px)`; g.fillStyle = 'rgba(20,6,40,0.6)'; g.translate(0.5, 1.4); g.fill(ring, 'evenodd'); g.restore();
    g.save(); g.clip(ring, 'evenodd');
    const yg = g.createLinearGradient(0, 31, 0, 78); yg.addColorStop(0, '#ffe27a'); yg.addColorStop(0.5, '#f2c230'); yg.addColorStop(1, '#c9921a'); g.fillStyle = yg; g.fillRect(0, 0, 108, 108);
    const n = noise2(7); pixels(g, (x, y) => [255, 240, 200, Math.max(0, (n(x * 2, y * 9) - 0.5) * 60)]);
    // moulding: a raised bead in the middle of the ring
    g.strokeStyle = 'rgba(255,250,215,0.85)'; g.lineWidth = 0.7; g.beginPath(); g.ellipse(54, 54.0, 25.9, 20.9, 0, 0, 7); g.stroke();
    g.strokeStyle = 'rgba(140,90,0,0.6)'; g.lineWidth = 0.6; g.beginPath(); g.ellipse(54, 55.0, 25.9, 20.9, 0, 0, 7); g.stroke();
    g.restore();
    g.strokeStyle = 'rgba(110,70,0,0.8)'; g.lineWidth = 0.35; g.stroke(outer); g.stroke(inner);
    for (let k = 0; k < 24; k++) { const a = k / 24 * Math.PI * 2; g.fillStyle = 'rgba(150,100,10,0.55)';
      g.beginPath(); g.arc(54 + Math.cos(a) * 28.5, 54.5 + Math.sin(a) * 23.5, 0.45, 0, 7); g.fill(); }
    // the mark: cream paint, smaller inside the frame
    g.save(); g.translate(54, 54.5); g.scale(0.78, 0.78); g.translate(-54, -54.5);
    g.save(); g.filter = `blur(${0.9 * S}px)`; g.fillStyle = 'rgba(20,6,40,0.5)'; g.translate(0.3, 1.1); g.fill(both); g.restore();
    within(g, both, () => { const cg = g.createLinearGradient(0, T, 0, B); cg.addColorStop(0, '#fffaf0'); cg.addColorStop(1, '#efe3c8'); g.fillStyle = cg; g.fillRect(0, 0, 108, 108); });
    bevel(g, 'rgba(255,255,255,1)', 'rgba(80,50,90,0.45)', 0.8);
    g.restore();
  },
};

// ------------------------------------------------------------------------------------------------ comic (v2)
// A comic cover: a blue sky printed in large halftone, tapered ink speed lines, paper grain and an off-register
// print; behind the mark a two-layer explosion (red, then yellow with halftone shading); the mark in white with a
// heavy ink line that is thicker on its shadow side, cross-hatched shading, a cyan halftone and white glints.
THEMES.comic = {
  bg(g) {
    const n = noise2(4), ox = 54, oy = 52;
    pixels(g, (x, y) => {
      const d = Math.hypot(x - ox, y - oy);
      let r = 30 + 40 * Math.max(0, 1 - d / 70), gg = 120 + 70 * Math.max(0, 1 - d / 70), b = 220 + 30 * Math.max(0, 1 - d / 70);
      // halftone: bigger dots away from the centre, on a rotated grid
      const p = 2.6, c = Math.cos(0.26), si = Math.sin(0.26), u = (x * c + y * si) / p, v = (-x * si + y * c) / p;
      const dd = Math.hypot(u - Math.round(u), v - Math.round(v)), rad = 0.12 + 0.36 * Math.min(1, d / 60);
      if (dd < rad) { r *= 0.55; gg *= 0.6; b *= 0.78; }
      const gr = (n(x * 28, y * 28) - 0.5) * 18 + (fbm(n, x / 5, y / 5, 3) - 0.5) * 12;
      return [r + gr, gg + gr, b + gr * 0.8];
    });
    // tapered ink speed lines converging on the centre
    const r = rng(9);
    for (let i = 0; i < 70; i++) {
      const a = r() * Math.PI * 2, d0 = 36 + r() * 16, d1 = d0 + 10 + r() * 30, w = 0.25 + r() * 0.7;
      const px = -Math.sin(a), py = Math.cos(a);
      g.fillStyle = r() < 0.75 ? 'rgba(10,14,40,0.85)' : 'rgba(255,255,255,0.8)';
      g.beginPath(); g.moveTo(ox + Math.cos(a) * d0, oy + Math.sin(a) * d0);
      g.lineTo(ox + Math.cos(a) * d1 + px * w, oy + Math.sin(a) * d1 + py * w); g.lineTo(ox + Math.cos(a) * d1 - px * w, oy + Math.sin(a) * d1 - py * w); g.closePath(); g.fill();
    }
  },
  fg(g) {
    const burst = (rOut, rIn, n, seed, sx = 1, sy = 0.92) => {
      const r = rng(seed), p = new Path2D();
      for (let i = 0; i < n * 2; i++) { const a = i / (n * 2) * Math.PI * 2 - Math.PI / 2 + (r() - 0.5) * 0.08; const rr = i % 2 ? rIn + r() * 2 : rOut + r() * 4;
        const x = 54 + Math.cos(a) * rr * sx, y = 54 + Math.sin(a) * rr * sy; i ? p.lineTo(x, y) : p.moveTo(x, y); }
      p.closePath(); return p;
    };
    const outer = burst(34, 24, 16, 17), inner = burst(27, 20, 16, 23);
    g.lineJoin = 'miter';
    g.save(); g.translate(1.4, 1.6); g.fillStyle = '#0a0e28'; g.fill(outer); g.restore();                 // ink shadow
    g.fillStyle = '#e8202a'; g.fill(outer); g.strokeStyle = '#0a0e28'; g.lineWidth = 1.1; g.stroke(outer);
    within(g, inner, () => {
      g.fillStyle = '#ffd400'; g.fillRect(0, 0, 108, 108);
      for (let y = 20; y < 92; y += 1.3) for (let x = 20; x < 92; x += 1.3) {  // orange halftone towards the edge
        const k = Math.hypot(x - 54, (y - 54) / 0.92) / 27; if (k < 0.55) continue;
        g.fillStyle = '#ff7a00'; g.beginPath(); g.arc(x + ((y / 1.3) % 2) * 0.65, y, Math.min(0.62, (k - 0.55) * 1.6), 0, 7); g.fill();
      }
    });
    g.strokeStyle = '#0a0e28'; g.lineWidth = 0.8; g.stroke(inner);
    // the mark: off-register magenta, a heavy ink shadow side, white body
    g.save(); g.translate(-0.7, -0.5); g.fillStyle = 'rgba(236,0,140,0.55)'; g.fill(both); g.restore();
    g.save(); g.translate(1.0, 1.1); g.fillStyle = '#0a0e28'; g.fill(both); g.restore();
    within(g, both, () => {
      g.fillStyle = '#fffdf6'; g.fillRect(0, 0, 108, 108);
      // cyan halftone shading at the lower right
      for (let y = T; y < B + 2; y += 1.05) for (let x = L; x < R + 2; x += 1.05) {
        const k = ((x - cx) / (R - cx)) * 0.5 + ((y - T) / (B - T)) * 0.9 - 0.55; if (k <= 0) continue;
        g.fillStyle = '#29b6f6'; g.beginPath(); g.arc(x + ((y / 1.05) % 2) * 0.52, y, Math.min(0.5, k * 0.9), 0, 7); g.fill();
      }
      // cross-hatching in the deepest shade
      g.save(); g.clip(sun); g.strokeStyle = 'rgba(10,14,40,0.7)'; g.lineWidth = 0.16;
      for (let k = -30; k < 30; k += 0.85) { g.beginPath(); g.moveTo(cx + 6 + k, sunBot); g.lineTo(cx + 6 + k + 10, sunBot - 10); g.stroke(); }
      g.restore();
      g.save(); g.clip(sun); g.globalCompositeOperation = 'destination-in'; g.restore();
    });
    // keep the hatching only near the lower right of the sun: wipe it elsewhere with a white mask
    g.save(); g.clip(sun); g.fillStyle = '#fffdf6';
    g.beginPath(); g.arc(cx - 3, sunCy - 2.5, sunR - 1.5, 0, 7); g.fill();
    for (let y = T; y < sunBot + 1; y += 1.05) for (let x = L; x < R + 1; x += 1.05) {
      const k = ((x - cx) / (R - cx)) * 0.5 + ((y - T) / (B - T)) * 0.9 - 0.55; if (k <= 0) continue;
      g.fillStyle = '#29b6f6'; g.beginPath(); g.arc(x + ((y / 1.05) % 2) * 0.52, y, Math.min(0.5, k * 0.9), 0, 7); g.fill(); }
    g.restore();
    g.lineJoin = 'round'; g.strokeStyle = '#0a0e28'; g.lineWidth = 1.2; g.stroke(sun); g.stroke(lines);
    // glints: a white streak with an ink edge, a dot, and action ticks around
    g.save(); g.clip(sun); g.lineCap = 'round';
    g.strokeStyle = '#0a0e28'; g.lineWidth = 1.5; g.beginPath(); g.arc(cx, sunCy, sunR - 4, Math.PI * 1.16, Math.PI * 1.36); g.stroke();
    g.strokeStyle = '#ffffff'; g.lineWidth = 0.9; g.beginPath(); g.arc(cx, sunCy, sunR - 4, Math.PI * 1.16, Math.PI * 1.36); g.stroke(); g.restore();
    g.strokeStyle = '#0a0e28'; g.lineCap = 'round';
    for (const [a, d0, d1] of [[-2.4, 25, 29], [-2.0, 26, 31], [-0.75, 25, 29.5], [-0.4, 25.5, 30], [2.6, 24, 28]]) {
      g.lineWidth = 0.9; g.beginPath(); g.moveTo(54 + Math.cos(a) * d0, 54 + Math.sin(a) * d0); g.lineTo(54 + Math.cos(a) * d1, 54 + Math.sin(a) * d1); g.stroke(); }
  },
};

// ================================================================================================ games
// Homages to famous game styles (no names, characters or textures of any particular game).

// cells of a shape on a grid of `cell` units: 1 inside, 2 outline (outside, next to inside), 0 empty
function pixelCells(path, cell) {
  const n = Math.round(108 / cell), c = document.createElement('canvas').getContext('2d');
  const map = new Uint8Array(n * n);
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (c.isPointInPath(path, (i + 0.5) * cell, (j + 0.5) * cell)) map[j * n + i] = 1;
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (!map[j * n + i]) {
    for (const [di, dj] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const a = i + di, b = j + dj; if (a >= 0 && b >= 0 && a < n && b < n && map[b * n + a] === 1) { map[j * n + i] = 2; break; } }
  }
  return { n, map };
}

// ------------------------------------------------------------------------------------------------ pixel
// An 8-bit platformer: a bright sky, chunky pixel clouds and hills, a brick ground, coins; the mark drawn in pixels
// with a dark outline, shading and highlight pixels.
THEMES.pixel = {
  bg(g) {
    const P = 1.5;
    g.fillStyle = '#6ea9ff'; g.fillRect(0, 0, 108, 108);
    const px = (i, j, col) => { g.fillStyle = col; g.fillRect(i * P, j * P, P + 0.02, P + 0.02); };
    // clouds: rows of pixels with a light underside
    const cloud = (ci, cj, w) => {
      const rows = [[2, w - 2], [1, w - 1], [0, w], [0, w], [1, w - 1]];
      rows.forEach(([a, b], r) => { for (let i = a; i < b; i++) px(ci + i, cj + r, r === 4 ? '#cfe3ff' : '#ffffff'); px(ci + a - 1, cj + r, '#1d2b53'); px(ci + b, cj + r, '#1d2b53'); });
      for (let i = 2; i < w - 2; i++) { px(ci + i, cj - 1, '#1d2b53'); } for (let i = 1; i < w - 1; i++) px(ci + i, cj + 5, '#1d2b53');
    };
    cloud(9, 14, 12); cloud(42, 16, 9); cloud(18, 25, 7);
    // hills
    const hill = (cx0, base, r, col, dark) => { for (let i = -r; i <= r; i++) { const h = Math.round(Math.sqrt(r * r - i * i) * 0.9); for (let j = 0; j < h; j++) px(cx0 + i, base - j, j === h - 1 ? dark : col); }
      for (const [a, b] of [[-r * 0.3, 0.5], [r * 0.25, 0.35]]) px(Math.round(cx0 + a), Math.round(base - h0(r) * b), dark); };
    const h0 = r => r * 0.9;
    hill(16, 54, 13, '#3ec144', '#1a7a2a'); hill(56, 54, 9, '#5fd35a', '#1a7a2a');
    // the brick ground
    for (let j = 55; j < 72; j++) for (let i = 0; i < 72; i++) {
      const row = j - 55, brickH = 3, off = Math.floor(row / brickH) % 2 ? 3 : 0;
      const mortar = row % brickH === 0 || (i + off) % 6 === 0;
      px(i, j, mortar ? '#5a2408' : (row % brickH === 1 ? '#e2793f' : '#c4561c'));
    }
    for (let i = 0; i < 72; i++) px(i, 54, '#1d2b53');
  },
  fg(g) {
    const P = 1.5, { n, map } = pixelCells(both, P);
    const shadow = pixelCells(both, P);
    for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (shadow.map[j * n + i] === 1) { g.fillStyle = 'rgba(29,43,83,0.35)'; g.fillRect((i + 1) * P, (j + 1) * P, P, P); }
    for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
      const v = map[j * n + i]; if (!v) continue;
      const x = (i + 0.5) * P, y = (j + 0.5) * P; let col;
      if (v === 2) col = '#1d2b53';
      else if (y < sunBot) { // the sun: gold with darker right side and a highlight
        const k = (x - cx) / sunR + (y - sunTop) / (sunBot - sunTop) * 0.6;
        col = k > 0.75 ? '#e08a00' : k > 0.35 ? '#ffb800' : '#ffd84a';
        if (Math.abs(x - (L + 9)) < P && Math.abs(y - (sunTop + 6)) < P * 1.6) col = '#ffffff';
        if (Math.abs(x - (L + 11.5)) < P * 1.6 && Math.abs(y - (sunTop + 3.8)) < P * 0.6) col = '#ffffff';
      } else col = (y % 6 > 4.4) ? '#c4561c' : '#ff8a3d';
      g.fillStyle = col; g.fillRect(i * P, j * P, P + 0.02, P + 0.02);
    }
    // two spinning coins
    const coin = (ci, cj, w) => { const rows = w === 4 ? [[1, 3], [0, 4], [0, 4], [0, 4], [0, 4], [1, 3]] : [[0, 2], [0, 2], [0, 2], [0, 2], [0, 2], [0, 2]];
      rows.forEach(([a, b], r) => { for (let i = a; i < b; i++) { g.fillStyle = i === a ? '#fff2a8' : (i === b - 1 ? '#c98a00' : '#ffd000'); g.fillRect((ci + i) * P, (cj + r) * P, P + 0.02, P + 0.02); }
        g.fillStyle = '#1d2b53'; g.fillRect((ci + a - 1) * P, (cj + r) * P, P, P); g.fillRect((ci + b) * P, (cj + r) * P, P, P); });
      g.fillStyle = '#1d2b53'; for (let i = rows[0][0]; i < rows[0][1]; i++) { g.fillRect((ci + i) * P, (cj - 1) * P, P, P); g.fillRect((ci + i) * P, (cj + 6) * P, P, P); } };
    coin(48, 22, 4); coin(19, 32, 2);
  },
};

// ------------------------------------------------------------------------------------------------ voxel
// A block world: a blue sky with a square sun and flat blocky clouds, grass blocks and a blocky tree; the mark a
// voxel grass block (turf on top dripping over the dirt, pixel depth to the lower right) and a pixel pickaxe.
THEMES.voxel = {
  bg(g) {
    const sky = g.createLinearGradient(0, 0, 0, 108); sky.addColorStop(0, '#6f9fff'); sky.addColorStop(1, '#b8d6ff'); g.fillStyle = sky; g.fillRect(0, 0, 108, 108);
    g.fillStyle = 'rgba(255,250,200,0.5)'; g.fillRect(70.5, 22.5, 11, 11); g.fillStyle = '#fff6b0'; g.fillRect(72, 24, 8, 8);
    g.fillStyle = 'rgba(255,255,255,0.92)';
    for (const [x, y, w, h] of [[30, 20, 22, 4], [36, 16, 10, 4], [10, 40, 12, 3.5], [82, 42, 14, 3.5]]) g.fillRect(x, y, w, h);
    const p = 9 / 8, r = rng(41);
    const block = (bx, by, top) => {   // a grass block (or dirt when top is false), 8 x 8 texels of 9/8 units
      for (let j = 0; j < 8; j++) for (let i = 0; i < 8; i++) {
        const v = r(); let col;
        if (top && (j < 2 || (j === 2 && v > 0.45) || (j === 3 && v > 0.82))) col = v < 0.35 ? '#4f9a2c' : v < 0.75 ? '#62b33a' : '#79c94c';
        else col = v < 0.3 ? '#6b4426' : v < 0.7 ? '#86562f' : v < 0.93 ? '#9a6a3d' : '#7e7e7e';
        g.fillStyle = col; g.fillRect(bx + i * p, by + j * p, p + 0.02, p + 0.02);
      }
      g.fillStyle = 'rgba(0,0,0,0.12)'; g.fillRect(bx, by + 9 - p * 0.6, 9, p * 0.6);
    };
    const heights = [75, 75, 75, 84, 84, 84, 84, 75, 75, 75, 84, 84];
    for (let k = 0; k < 12; k++) { const x = k * 9, y = heights[k]; block(x, y, true); for (let yy = y + 9; yy < 108; yy += 9) block(x, yy, false); }
    // a blocky tree: trunk and leaves
    const tx = 17, ty = 75;
    for (let yy = ty - 18; yy < ty; yy += 2.25) for (let i = 0; i < 2; i++) { g.fillStyle = i ? '#5a3c1e' : '#6e4a26'; g.fillRect(tx + 3.4 + i * 1.12, yy, 1.13, 2.26); }
    for (let j = 0; j < 10; j++) for (let i = 0; i < 14; i++) {
      const x = tx - 6 + i * 1.5, y = ty - 30 + j * 1.5; if ((j < 2 || j > 7) && (i < 2 || i > 11)) continue;
      const v = r(); if (v > 0.93) continue; g.fillStyle = v < 0.3 ? '#2f6e1e' : v < 0.7 ? '#3d8a26' : '#4ea232'; g.fillRect(x, y, 1.52, 1.52);
    }
  },
  fg(g) {
    const P = 1.6, { n, map } = pixelCells(both, P), r = rng(5);
    const inside = (i, j) => i >= 0 && j >= 0 && i < n && j < n && map[j * n + i] === 1;
    // the first cell from the top in each column: where the turf starts
    const topOf = []; for (let i = 0; i < n; i++) { topOf[i] = -1; for (let j = 0; j < n; j++) if (inside(i, j)) { topOf[i] = j; break; } }
    const turf = (i, j) => { let t = j - topOf[i]; for (let k = j; k >= 0 && inside(i, k); k--) t = j - k; return t; };
    // depth to the lower right: darker dirt sides, darker grass on top edges
    for (let d = 3; d >= 1; d--) for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (inside(i, j)) {
      const t = turf(i, j); g.fillStyle = t < 1 ? '#3d7a22' : '#5a3a20'; g.fillRect(i * P + d * 0.62, j * P + d * 0.62, P + 0.02, P + 0.02);
    }
    for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (inside(i, j)) {
      const v = r(), t = turf(i, j); let col;
      if (t < 1 || (t === 1 && v > 0.35) || (t === 2 && v > 0.82)) col = v < 0.3 ? '#4f9a2c' : v < 0.75 ? '#62b33a' : '#7fd052';
      else col = v < 0.28 ? '#6b4426' : v < 0.7 ? '#8a5a31' : v < 0.94 ? '#a0703f' : '#828282';
      g.fillStyle = col; g.fillRect(i * P, j * P, P + 0.02, P + 0.02);
    }
    // a pixel pickaxe, lower right
    const q = 1.7, ox = 70, oy = 60;
    const pick = ['....HHH.', '...H..HH', '......H.', '.....H.H', '....S..H', '...S....', '..S.....', '.S......'];
    pick.forEach((row, j) => [...row].forEach((c, i) => { if (c === '.') return;
      g.fillStyle = '#2a2a2a'; g.fillRect(ox + i * q - 0.25, oy + j * q - 0.25, q + 0.5, q + 0.5); }));
    pick.forEach((row, j) => [...row].forEach((c, i) => { if (c === '.') return;
      g.fillStyle = c === 'H' ? (i + j) % 2 ? '#7fe7e0' : '#4fc7c0' : (i % 2 ? '#7a5228' : '#9a6a36'); g.fillRect(ox + i * q, oy + j * q, q + 0.02, q + 0.02); }));
  },
};

// ------------------------------------------------------------------------------------------------ candy
// Match three: a board of glossy candies in a frosted grid on purple; the mark a big jelly candy with stripes.
THEMES.candy = {
  bg(g) {
    const bgG = g.createRadialGradient(54, 50, 10, 54, 54, 80); bgG.addColorStop(0, '#8e3fd4'); bgG.addColorStop(1, '#3a0f6e'); g.fillStyle = bgG; g.fillRect(0, 0, 108, 108);
    const C = 13.5, r = rng(31), cols = [['#ff3b5c', '#a3001e'], ['#ffb020', '#b35d00'], ['#2ee86a', '#0b8a33'], ['#2fa6ff', '#0050b0'], ['#c45bff', '#6a0fb0'], ['#ffe14a', '#b38f00']];
    for (let j = 0; j < 8; j++) for (let i = 0; i < 8; i++) {
      const x = i * C, y = j * C;
      g.fillStyle = (i + j) % 2 ? 'rgba(40,10,90,0.45)' : 'rgba(70,30,130,0.45)'; g.beginPath(); g.roundRect(x + 0.5, y + 0.5, C - 1, C - 1, 2.2); g.fill();
      const [c1, c2] = cols[Math.floor(r() * cols.length)], kind = r(), mx = x + C / 2, my = y + C / 2, s = C * 0.36;
      g.save(); g.filter = `blur(${0.5 * S}px)`; g.fillStyle = 'rgba(20,0,40,0.5)'; g.beginPath(); g.ellipse(mx + 0.4, my + 1, s, s * 0.9, 0, 0, 7); g.fill(); g.restore();
      const gr = g.createRadialGradient(mx - s * 0.35, my - s * 0.4, s * 0.1, mx, my, s * 1.1); gr.addColorStop(0, '#ffffff'); gr.addColorStop(0.25, c1); gr.addColorStop(1, c2); g.fillStyle = gr;
      g.beginPath(); if (kind < 0.33) g.arc(mx, my, s, 0, 7); else if (kind < 0.66) g.roundRect(mx - s, my - s, s * 2, s * 2, s * 0.45); else { g.moveTo(mx, my - s * 1.1); g.lineTo(mx + s * 1.1, my); g.lineTo(mx, my + s * 1.1); g.lineTo(mx - s * 1.1, my); g.closePath(); }
      g.fill();
      g.fillStyle = 'rgba(255,255,255,0.65)'; g.beginPath(); g.ellipse(mx - s * 0.35, my - s * 0.45, s * 0.35, s * 0.18, -0.5, 0, 7); g.fill();
    }
    const v = g.createRadialGradient(54, 54, 18, 54, 54, 50); v.addColorStop(0, 'rgba(40,0,80,0.55)'); v.addColorStop(1, 'rgba(40,0,80,0)'); g.fillStyle = v; g.fillRect(0, 0, 108, 108);
  },
  fg(g) {
    glow(g, both, '#ffb3e6', 3.2, 0.7);
    g.save(); g.filter = `blur(${1.2 * S}px)`; g.fillStyle = 'rgba(40,0,60,0.6)'; g.translate(0.4, 1.4); g.fill(both); g.restore();
    within(g, both, () => {
      const base = g.createLinearGradient(0, T, 0, B); base.addColorStop(0, '#ff8fc8'); base.addColorStop(0.5, '#ff3d8b'); base.addColorStop(1, '#c4005a'); g.fillStyle = base; g.fillRect(0, 0, 108, 108);
      g.save(); g.globalAlpha = 0.85; g.fillStyle = '#ffffff'; for (let k = -12; k < 12; k++) { g.beginPath(); g.moveTo(cx + k * 5.2, T - 4); g.lineTo(cx + k * 5.2 + 2.2, T - 4); g.lineTo(cx + k * 5.2 + 2.2 - 18, B + 4); g.lineTo(cx + k * 5.2 - 18, B + 4); g.closePath(); g.fill(); } g.restore();
      const jelly = g.createRadialGradient(cx - 6, sunTop + 5, 1, cx, sunCy, sunR + 8); jelly.addColorStop(0, 'rgba(255,255,255,0.55)'); jelly.addColorStop(0.4, 'rgba(255,255,255,0)'); jelly.addColorStop(1, 'rgba(120,0,50,0.4)'); g.fillStyle = jelly; g.fillRect(0, 0, 108, 108);
    });
    bevel(g, 'rgba(255,240,250,1)', 'rgba(110,0,50,0.6)', 1, 1.6);
    g.save(); g.clip(sun); g.fillStyle = 'rgba(255,255,255,0.85)'; g.beginPath(); g.ellipse(L + 9, sunTop + 6, 4.2, 1.6, -0.55, 0, 7); g.fill(); g.restore();
    for (const [x, y, s] of [[73, 36, 2.6], [36, 66, 1.8], [77, 69, 1.6]]) star4(g, x, y, s, '#ffffff', 0.95);
  },
};

// ------------------------------------------------------------------------------------------------ runner
// A neon rhythm runner: deep blue to violet, a glowing grid floor, a row of neon spikes, square particles; the mark a
// glowing block with a cyan rim and a trail of squares behind it.
THEMES.runner = {
  bg(g) {
    const sky = g.createLinearGradient(0, 0, 0, 108); sky.addColorStop(0, '#0b1a5c'); sky.addColorStop(0.55, '#3a1580'); sky.addColorStop(1, '#14063a'); g.fillStyle = sky; g.fillRect(0, 0, 108, 108);
    const n = noise2(5); pixels(g, (x, y) => [255, 255, 255, (n(x * 30, y * 30) - 0.5) * 10 + 5]);
    // background squares, drifting
    const r = rng(19);
    for (let i = 0; i < 26; i++) { const x = r() * 108, y = r() * 70, s = 1.5 + r() * 5; g.save(); g.translate(x, y); g.rotate(r() * 0.8);
      g.strokeStyle = `rgba(120,200,255,${0.12 + r() * 0.2})`; g.lineWidth = 0.35; g.strokeRect(-s / 2, -s / 2, s, s); g.restore(); }
    // the floor: a glowing line and a perspective grid below it
    const fy = 80;
    g.save(); g.filter = `blur(${1.2 * S}px)`; g.fillStyle = '#39e8ff'; g.fillRect(0, fy - 0.5, 108, 1.4); g.restore();
    g.fillStyle = '#c9fbff'; g.fillRect(0, fy - 0.25, 108, 0.5);
    const fl = g.createLinearGradient(0, fy, 0, 108); fl.addColorStop(0, '#1d0a52'); fl.addColorStop(1, '#09031f'); g.fillStyle = fl; g.fillRect(0, fy + 0.25, 108, 28);
    g.strokeStyle = 'rgba(57,232,255,0.45)'; g.lineWidth = 0.25;
    for (let k = -14; k <= 14; k++) { g.beginPath(); g.moveTo(54 + k * 4, fy + 0.3); g.lineTo(54 + k * 16, 108); g.stroke(); }
    for (let k = 1; k < 8; k++) { const y = fy + Math.pow(k / 8, 1.8) * 28; g.beginPath(); g.moveTo(0, y); g.lineTo(108, y); g.stroke(); }
    // neon spikes on the floor
    for (const x of [24, 30, 70, 76, 82]) {
      const sp = new Path2D(); sp.moveTo(x - 3, fy); sp.lineTo(x, fy - 6); sp.lineTo(x + 3, fy); sp.closePath();
      g.save(); g.filter = `blur(${1 * S}px)`; g.fillStyle = '#ff3fd0'; g.globalAlpha = 0.8; g.fill(sp); g.restore();
      g.fillStyle = '#2a0838'; g.fill(sp); g.strokeStyle = '#ff8ae6'; g.lineWidth = 0.5; g.stroke(sp);
    }
  },
  fg(g) {
    // the trail: squares fading behind to the left
    for (let k = 1; k <= 5; k++) { const s = 4.4 - k * 0.55, x = L - 2 - k * 4.6, y = sunCy - 2 + k * 1.2;
      g.save(); g.globalAlpha = 0.75 - k * 0.12; g.translate(x, y); g.rotate(k * 0.25); g.fillStyle = '#39e8ff'; g.fillRect(-s / 2, -s / 2, s, s); g.restore(); }
    glow(g, both, '#39e8ff', 3.4, 0.8);
    within(g, both, () => {
      const base = g.createLinearGradient(0, T, 0, B); base.addColorStop(0, '#ffe14a'); base.addColorStop(1, '#ff9f1a'); g.fillStyle = base; g.fillRect(0, 0, 108, 108);
      g.strokeStyle = 'rgba(120,60,0,0.35)'; g.lineWidth = 0.5;          // a block pattern inside, like a game cube
      g.beginPath(); g.arc(cx, sunCy, sunR * 0.55, Math.PI, 2 * Math.PI); g.stroke();
      g.strokeRect(cx - 3, sunCy - 8.5, 6, 6);
      const sh = g.createLinearGradient(L, T, R, B); sh.addColorStop(0.2, 'rgba(255,255,255,0)'); sh.addColorStop(0.32, 'rgba(255,255,255,0.45)'); sh.addColorStop(0.42, 'rgba(255,255,255,0)'); g.fillStyle = sh; g.fillRect(0, 0, 108, 108);
    });
    g.lineJoin = 'round'; g.strokeStyle = '#0b0526'; g.lineWidth = 1.0; g.stroke(sun); g.stroke(lines);
    g.strokeStyle = '#7ff4ff'; g.lineWidth = 0.4; g.stroke(sun); g.stroke(lines);
  },
};

// ------------------------------------------------------------------------------------------------ snake
// The snake of old push-button phones: a green LCD with its pixel grid, a wall, a score; a pixel snake curling
// round the mark towards its food; the mark in dark LCD pixels with a faint ghost.
THEMES.snake = {
  bg(g) {
    const n = noise2(15);
    pixels(g, (x, y) => {
      const k = Math.hypot(x - 40, y - 34) / 90, cell = (x % 1.5 < 0.12 || y % 1.5 < 0.12) ? -8 : 0;
      const v = (n(x * 6, y * 6) - 0.5) * 6;
      return [168 - 30 * k + cell + v, 186 - 30 * k + cell + v, 96 - 20 * k + cell * 0.6 + v];
    });
    const P = 1.5, ink = '#2a3a10', px = (i, j) => { g.fillStyle = ink; g.fillRect(i * P + 0.1, j * P + 0.1, P - 0.2, P - 0.2); };
    for (let i = 14; i <= 57; i++) { px(i, 14); px(i, 57); } for (let j = 14; j <= 57; j++) { px(14, j); px(57, j); }   // the wall
    // the score in 3x5 pixel digits
    const D = { 0: ['111', '101', '101', '101', '111'], 1: ['010', '110', '010', '010', '111'], 2: ['111', '001', '111', '100', '111'], 3: ['111', '001', '111', '001', '111'], 7: ['111', '001', '010', '010', '010'] };
    // the snake: a path of cells round the mark, the head with an eye, the food ahead
    const path = [];
    for (let i = 18; i <= 50; i++) path.push([i, 52]);          // along the bottom, under the mark
    for (let j = 51; j >= 21; j--) path.push([50, j]);          // up the right side
    for (let i = 49; i >= 40; i--) path.push([i, 21]);          // left above the mark, the head at the end
    for (const [i, j] of path) { px(i, j); px(i + 1, j); px(i, j + 1); px(i + 1, j + 1); }   // a two-pixel body, as on the phones
    g.fillStyle = 'rgba(168,186,96,1)'; g.fillRect(40 * P + 0.4, 21 * P + 0.4, 0.7, 0.7);                       // the eye
    for (const [i, j] of [[34, 20], [35, 21], [34, 22], [33, 21], [34, 21]]) px(i, j);                                      // the food
    const gl = g.createLinearGradient(0, 0, 108, 108); gl.addColorStop(0.1, 'rgba(255,255,255,0.0)'); gl.addColorStop(0.28, 'rgba(255,255,240,0.18)'); gl.addColorStop(0.4, 'rgba(255,255,255,0)'); g.fillStyle = gl; g.fillRect(0, 0, 108, 108);
  },
  fg(g) {
    const P = 1.5, { n, map } = pixelCells(both, P);
    for (let pass = 0; pass < 2; pass++) for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) if (map[j * n + i] === 1) {
      g.fillStyle = pass ? '#24330d' : 'rgba(42,58,16,0.18)';                         // a faint LCD ghost, then the pixel
      g.fillRect(i * P + 0.1 + (pass ? 0 : 0.6), j * P + 0.1 + (pass ? 0 : 0.6), P - 0.2, P - 0.2);
    }
  },
};

// ------------------------------------------------------------------------------------------------ airdrop
// A battle royale drop: a sunset sky with painted clouds, a distant ridge and coloured smoke from the ground; the
// mark a military supply container (olive steel, hazard stripes, a beacon) under a striped parachute.
THEMES.airdrop = {
  bg(g) {
    const n = noise2(131), n2 = noise2(132);
    pixels(g, (x, y) => {
      const t = y / 108;
      let r = 70 + 190 * t, gg = 110 + 90 * t, b = 190 - 60 * t;                       // blue above, warm at the horizon
      const cl = Math.max(0, fbm(n, x / 18, y / 7, 5) - 0.48) * 2.2 * (1 - t * 0.6);
      r += cl * 120; gg += cl * 90; b += cl * 60;
      const ridge = 86 + fbm(n2, x / 14, 1, 4) * 10 - 5;
      if (y > ridge) { const k = Math.min(1, (y - ridge) / 20); r = 60 - 20 * k; gg = 52 - 18 * k; b = 70 - 20 * k; }
      return [Math.min(255, r), Math.min(255, gg), Math.min(255, b)];
    });
    // purple smoke rising from the ground on the right
    const r = rng(4);
    for (let i = 0; i < 26; i++) { const t = i / 26, x = 80 + Math.sin(t * 5) * 3 + t * 4, y = 90 - t * 50, s = 2 + t * 6;
      g.save(); g.filter = `blur(${(1 + t * 2) * S}px)`; g.globalAlpha = 0.5 - t * 0.35; g.fillStyle = '#b44bff'; g.beginPath(); g.arc(x, y, s, 0, 7); g.fill(); g.restore(); }
    g.fillStyle = '#ffd36b'; g.beginPath(); g.arc(80, 90, 0.8, 0, 7); g.fill();
  },
  fg(g) {
    // the parachute: a striped canopy above, cords to the container's edges
    const cy0 = 31, cw = 23;
    g.strokeStyle = 'rgba(40,40,40,0.85)'; g.lineWidth = 0.25;
    for (const [x0, x1] of [[cx - cw, L + 1], [cx - cw * 0.45, L + 7], [cx + cw * 0.45, R - 7], [cx + cw, R - 1]]) { g.beginPath(); g.moveTo(x0, cy0); g.lineTo(x1, sunTop + 6); g.stroke(); }
    const canopy = new Path2D(); canopy.moveTo(cx - cw, cy0); canopy.bezierCurveTo(cx - cw, cy0 - 15, cx + cw, cy0 - 15, cx + cw, cy0);
    for (let k = 6; k >= 0; k--) { const x = cx - cw + k * (cw * 2 / 6); canopy.quadraticCurveTo(x + cw / 6, cy0 + 2.6, x, cy0); }
    canopy.closePath();
    g.save(); g.filter = `blur(${0.8 * S}px)`; g.fillStyle = 'rgba(40,20,0,0.35)'; g.translate(0.5, 1); g.fill(canopy); g.restore();
    g.save(); g.clip(canopy);
    for (let k = 0; k < 6; k++) { g.fillStyle = k % 2 ? '#f4efe6' : '#ff6a1a'; g.beginPath(); g.moveTo(cx, cy0 - 30);
      g.lineTo(cx - cw + k * (cw * 2 / 6) - 2, cy0 + 4); g.lineTo(cx - cw + (k + 1) * (cw * 2 / 6) - 2, cy0 + 4); g.closePath(); g.fill(); }
    const sh = g.createLinearGradient(0, cy0 - 12, 0, cy0 + 3); sh.addColorStop(0, 'rgba(255,255,255,0.35)'); sh.addColorStop(1, 'rgba(60,20,0,0.35)'); g.fillStyle = sh; g.fillRect(0, 0, 108, 108);
    g.restore();
    g.strokeStyle = 'rgba(60,30,0,0.6)'; g.lineWidth = 0.3; g.stroke(canopy);
    // the container
    g.save(); g.filter = `blur(${1 * S}px)`; g.fillStyle = 'rgba(30,20,0,0.5)'; g.translate(0.5, 1.3); g.fill(both); g.restore();
    const n = noise2(66);
    within(g, both, () => {
      pixels(g, (x, y) => { const v = (fbm(n, x * 0.8, y * 0.8, 4) - 0.5) * 30, t = (y - T) / (B - T);
        return [104 + v - t * 30, 112 + v - t * 30, 64 + v * 0.6 - t * 20, 255]; });
      g.strokeStyle = 'rgba(40,46,20,0.6)'; g.lineWidth = 0.3;                              // ribs on the dome
      for (let k = 1; k < 6; k++) { g.beginPath(); g.moveTo(L + k * (R - L) / 6, T); g.lineTo(L + k * (R - L) / 6, sunBot); g.stroke(); }
      g.save(); g.clip(lines); for (let k = -20; k < 30; k++) { g.fillStyle = k % 2 ? '#1c1c1c' : '#ffcc1a'; g.beginPath(); const x = L + k * 2.2;
        g.moveTo(x, 58); g.lineTo(x + 2.2, 58); g.lineTo(x + 2.2 - 14, 74); g.lineTo(x - 14, 74); g.closePath(); g.fill(); } g.restore();
    });
    bevel(g, 'rgba(230,240,200,0.8)', 'rgba(20,24,8,0.7)', 1, 1.2);
    // the beacon on top
    g.save(); g.filter = `blur(${1.6 * S}px)`; g.fillStyle = '#ff3a2a'; g.beginPath(); g.arc(cx, sunTop - 0.4, 2.4, 0, 7); g.fill(); g.restore();
    g.fillStyle = '#ffb3a6'; g.beginPath(); g.arc(cx, sunTop - 0.2, 0.9, 0, 7); g.fill();
  },
};
