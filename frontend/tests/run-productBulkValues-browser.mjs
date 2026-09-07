import { build } from 'vite';
import react from '@vitejs/plugin-react';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fixture = process.argv[2] ?? 'productBulkValues.browser.tsx';
if (!/^[A-Za-z0-9._-]+\.browser\.tsx$/.test(fixture)) throw new Error('Expected a local browser fixture filename');
const out = await mkdtemp(join(tmpdir(), 'sbshop-bulk-values-browser-'));
await build({ root, configFile: false, plugins: [react()], logLevel: 'warn', define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: { outDir: join(out, 'dist'), emptyOutDir: true, lib: { entry: join(root, 'tests', fixture), formats: ['iife'], name: 'ContentFixture', fileName: () => 'fixture.js' } } });
const files = await readdir(join(out, 'dist'));
const css = (await Promise.all(files.filter(file => file.endsWith('.css')).map(file => readFile(join(out, 'dist', file), 'utf8')))).join('\n');
const js = await readFile(join(out, 'dist/fixture.js'), 'utf8');
const html = `<!doctype html><html lang="ko"><meta charset="utf-8"><title>상품 비숫자 일괄 편집 검증 · fixture</title><style>*{box-sizing:border-box}body{margin:0;background:#f4f6f8;font-family:Arial,sans-serif}.product-theme{--product-primary:#166534}${css}</style><body><div id="root"></div><script>${js.replaceAll('</script', '<\\/script')}</script></body></html>`;
await writeFile(join(out, 'index.html'), html);
function bottle(version, extra) {
  const current = version === 'new';
  const color = current ? '#166534' : '#475569';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="160" height="190" viewBox="0 0 160 190"><rect width="160" height="190" rx="12" fill="${current ? '#f0fdf4' : '#f1f5f9'}"/><rect x="53" y="18" width="54" height="24" rx="5" fill="${color}"/><rect x="35" y="40" width="90" height="128" rx="19" fill="white" stroke="${color}" stroke-width="3"/><rect x="39" y="75" width="82" height="59" fill="${color}"/><text x="80" y="97" text-anchor="middle" fill="white" font-family="Arial" font-size="13" font-weight="bold">${extra ? 'DETAIL' : current ? 'NEW LABEL' : 'OLD LABEL'}</text><text x="80" y="118" text-anchor="middle" fill="white" font-family="Arial" font-size="11">${current ? '2026' : '2022'}</text>${extra ? '<path d="M48 146h64M48 153h51M48 160h39" stroke="#64748b" stroke-width="2"/>' : '<circle cx="80" cy="151" r="7" fill="#e2e8f0"/>'}</svg>`;
}
const assets = Object.fromEntries(['old-main', 'old-detail', 'new-main', 'new-detail'].map(name => [`/fixtures/${name}.svg`, bottle(name.split('-')[0], name.endsWith('detail'))]));
const servedImages = new Set();
const server = createServer((request, response) => {
  const path = new URL(request.url ?? '/', 'http://127.0.0.1').pathname;
  if (assets[path]) { servedImages.add(path); response.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' }); response.end(assets[path]); }
  else if (path === '/' || path === '/index.html') { response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); response.end(html); }
  else { response.writeHead(404); response.end('Local fixture path not found'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const address = server.address();
const chrome = process.env.SBSHOP_TEST_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let stdout;
try {
({ stdout } = await promisify(execFile)(chrome, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--disable-extensions', '--no-proxy-server', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE 127.0.0.1',
  `--user-data-dir=${join(out, 'chrome-profile')}`, '--window-size=1440,1000', '--virtual-time-budget=18000', '--dump-dom',
  `--screenshot=${join(out, 'screenshot.png')}`, `http://127.0.0.1:${address.port}/index.html`], { maxBuffer: 20 * 1024 * 1024, timeout: 60_000 }));
} finally { await new Promise(resolve => server.close(resolve)); }
const match = stdout.match(/<script type="application\/json" id="browser-check-result">(.*?)<\/script>/s);
if (!match) throw new Error(`Chrome did not finish browser checks. Artifacts: ${out}`);
const result = JSON.parse(match[1]);
result.fixtureOnly = true;
result.servedImages = [...servedImages].sort();

await writeFile(join(out, 'result.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify({ ...result, artifacts: out }, null, 2));
if (result.status !== 'passed') process.exitCode = 1;
