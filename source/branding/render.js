// Render SVG files to PNG with Chromium. usage: node render.js <out.png> <size> <svg...> (multiple svgs -> horizontal sheet)
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const fs = require('fs');
(async () => {
  const [out, size, ...svgs] = process.argv.slice(2);
  const s = parseInt(size, 10);
  const html = `<html><body style="margin:0;background:transparent;display:flex;gap:${svgs.length > 1 ? 24 : 0}px;padding:${svgs.length > 1 ? 24 : 0}px;${svgs.length > 1 ? 'background:#ECEFF3' : ''}">` +
    svgs.map(f => `<div style="width:${s}px;height:${s}px">${fs.readFileSync(f, 'utf8').replace(/width="\d+" height="\d+"/, `width="${s}" height="${s}"`)}</div>`).join('') + `</body></html>`;
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: svgs.length > 1 ? (s + 24) * svgs.length + 24 : s, height: svgs.length > 1 ? s + 48 : s }, deviceScaleFactor: 1 });
  await page.setContent(html);
  await page.screenshot({ path: out, omitBackground: svgs.length === 1 });
  await browser.close();
})();
