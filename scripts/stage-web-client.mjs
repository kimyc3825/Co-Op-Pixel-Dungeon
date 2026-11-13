// SPDX-License-Identifier: GPL-3.0-or-later
import { createHash } from 'node:crypto';
import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const revision = process.env.COOP_CLIENT_REVISION;
if (!/^[a-f0-9]{40}$/.test(revision ?? '')) throw new Error('Set COOP_CLIENT_REVISION to the client Git commit.');

const project = new URL('../', import.meta.url);
const source = fileURLToPath(new URL('../web-client/play/', import.meta.url));
const dungeon = new URL('html/build/dist/web/', project);
const output = fileURLToPath(new URL('html/build/dist/web/play/', project));
const runtimeFiles = [
  'avatar-catalog.mjs', 'battle.mjs', 'layout.mjs', 'rules.mjs',
  'assets/items.png', 'assets/rat.png', 'assets/tiles_prison.png', 'assets/tiles_sewers.png', 'assets/training.png',
].sort();

await readFile(new URL('index.html', dungeon));
await rm(output, { recursive: true, force: true });
await cp(source, output, { recursive: true });

const runtimeHash = createHash('sha256');
for (const file of runtimeFiles) {
  const bytes = await readFile(join(output, file));
  runtimeHash.update(`${file.length}:${file}:${bytes.length}:`).update(bytes);
}
const runtimeVersion = runtimeHash.digest('hex');
const runtime = join(output, 'runtime', runtimeVersion);
for (const file of runtimeFiles) {
  const directory = file.startsWith('assets/') ? join(runtime, 'assets') : runtime;
  await mkdir(directory, { recursive: true });
  await cp(join(output, file), join(runtime, file));
}

const assetHash = createHash('sha256');
async function hashAssets(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  for (const entry of entries) {
    const name = prefix + entry.name;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await hashAssets(path, name + '/');
    } else if (entry.isFile()) {
      const bytes = await readFile(path);
      assetHash.update(`${name.length}:${name}:${bytes.length}:`).update(bytes);
    } else {
      throw new Error(`Unexpected web client asset: ${name}`);
    }
  }
}
await hashAssets(output);
const signature = assetHash.digest('hex');
const base = `/play/client/${revision}-${signature}/`;
const page = join(output, 'index.html');
const html = await readFile(page, 'utf8');
if (!html.includes('<head>')) throw new Error('Missing web client document head.');
await writeFile(page, html.replace('<head>', `<head>\n  <base href="${base}">`));
await writeFile(join(output, 'runtime-manifest.json'), JSON.stringify({
  version: runtimeVersion,
  entry: `${base}runtime/${runtimeVersion}/battle.mjs`,
}));
console.log(signature);
