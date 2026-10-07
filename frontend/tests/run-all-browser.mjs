import { spawnSync } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '..');
const fixtures = (await readdir(directory)).filter(name => name.endsWith('.browser.tsx')).sort();
const failed = [];
for (const fixture of fixtures) {
  console.log(`Browser fixture: ${fixture}`);
  const runner = fixture === 'productContentRefresh.browser.tsx' ? 'run-productContentRefresh-browser.mjs' : 'run-productBulkValues-browser.mjs';
  const result = spawnSync(process.execPath, [join(directory, runner), fixture], {
    cwd: root, stdio: 'inherit', env: process.env,
  });
  if (result.status !== 0) failed.push(fixture);
}
console.log(`Browser fixtures: ${fixtures.length - failed.length}/${fixtures.length} passed`);
if (failed.length > 0) {
  console.error(`Failed fixtures: ${failed.join(', ')}`);
  process.exitCode = 1;
}
