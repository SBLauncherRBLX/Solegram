// Batch-render SVGs to PNGs in one Chromium session. usage: node render_batch.js jobs.json
// jobs.json: [{"svg": "path.svg", "out": "path.png", "size": 192, "pad": 0.04}]
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const fs = require('fs');
(async () => {
  const jobs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 64, height: 64 }, deviceScaleFactor: 1 });
  for (const j of jobs) {
    const s = j.size, pad = Math.round(s * (j.pad || 0)), inner = s - 2 * pad;
    const svg = fs.readFileSync(j.svg, 'utf8').replace(/width="\d+" height="\d+"/, `width="${inner}" height="${inner}"`);
    await page.setViewportSize({ width: s, height: s });
    await page.setContent(`<html><body style="margin:0;background:transparent"><div style="padding:${pad}px;width:${inner}px;height:${inner}px">${svg}</div></body></html>`);
    await page.screenshot({ path: j.out, omitBackground: true, clip: { x: 0, y: 0, width: s, height: s } });
  }
  await browser.close();
})();
