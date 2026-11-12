const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
(async () => {
  const mark = fs.readFileSync(path.resolve('markdata.json'), 'utf8');
  fs.writeFileSync('themes6.built.html', fs.readFileSync('themes4.html', 'utf8').replace('__MARK__', mark).replace('__MORE__', fs.readFileSync('themes5.js', 'utf8') + '\n' + fs.readFileSync('themes6.js', 'utf8') + '\n' + fs.readFileSync('themes7.js', 'utf8')));
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 432, height: 432 } });
  p.on('pageerror', e => console.log('error:', e.message));
  await p.goto('file://' + path.resolve('themes6.built.html'));
  await p.evaluate(() => document.fonts.load('900 13px "WenQuanYi Zen Hei"', 'ドンゴ'));
  for (const n of process.argv.slice(2)) for (const layer of ['bg', 'fg']) {
    const d = await p.evaluate(([n, l]) => window.render(n, l), [n, layer]);
    fs.writeFileSync(`${layer}_${n}.png`, Buffer.from(d.split(',')[1], 'base64'));
  }
  await b.close();
})();
