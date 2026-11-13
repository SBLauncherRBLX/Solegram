const { chromium } = require('playwright'); const fs = require('fs'), path = require('path');
(async () => { const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 432, height: 432 } });
  p.on('pageerror', e => console.log('error:', e.message));
  await p.goto('file://' + path.resolve(process.argv[2]));
  for (const l of ['bg', 'fg']) { const d = await p.evaluate(l => window.render(l), l); fs.writeFileSync(path.join(process.argv[3], l + '.png'), Buffer.from(d.split(',')[1], 'base64')); }
  await b.close(); })();
