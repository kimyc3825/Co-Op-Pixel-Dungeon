// SPDX-License-Identifier: GPL-3.0-or-later
// Stages the web lobby and the TeaVM "original" runtime as Workers static assets in server/public.
//
//   ./gradlew -PwebOnly html:dist && node scripts/stage-web-client.mjs
//   node scripts/stage-web-client.mjs --lobby-only      (lobby without the dungeon runtime, for local work)
//
// Layout (every versioned path is immutable; only the three entry files change between deploys):
//   /play/index.html                                  lobby page, <base href> points at the client folder
//   /play/client/<revision>-<signature>/...           lobby modules and art (web-client/play)
//   /play/client/.../runtime-manifest.json            legacy mini-battle runtime (runtime-loader.mjs)
//   /play/client/.../runtime/<version>/...
//   /play/original/manifest.json                      { version, entry } read by original-download.mjs
//   /play/original/catalogue/<version>.json           { version, files: [[path, size]] }
//   /play/original/runtime/<version>/...              html/build/dist/web (original-cache.js caches these)
//
// Set COOP_PREVIOUS_SITE to the server/public of the previous deploy to keep its versioned folders,
// so pages opened before a deploy can still load their files afterwards.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cp, lstat, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Workers static assets limits (Workers Free plan): 20,000 files per version, 25 MiB per file.
const MAX_ASSET_FILES = 20_000;
const MAX_ASSET_BYTES = 25 * 1024 * 1024;
const VERSION = /^[a-f0-9]{64}$/;

const lobbyOnly = process.argv.includes('--lobby-only');
const project = fileURLToPath(new URL('../', import.meta.url));
const source = join(project, 'web-client/play');
const dungeon = process.env.COOP_WEB_DIST ?? join(project, 'html/build/dist/web');
const site = process.env.COOP_SITE_DIR ?? join(project, 'server/public');
const play = join(site, 'play');

let revision = process.env.COOP_CLIENT_REVISION;
if (!revision) {
  try {
    revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: project, encoding: 'utf8' }).trim();
  } catch { /* checked below */ }
}
if (!/^[a-f0-9]{40}$/.test(revision ?? '')) throw new Error('Set COOP_CLIENT_REVISION to the client Git commit.');

const validPath = path => typeof path === 'string' && path.length <= 320 &&
  path.split('/').every(segment => /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,119}$/.test(segment));

async function sha256File(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

async function listFiles(root, prefix = '', skip = () => false) {
  const files = [];
  const entries = await readdir(join(root, prefix), { withFileTypes: true });
  entries.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  for (const entry of entries) {
    const name = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (skip(name)) continue;
    if (entry.isDirectory()) files.push(...await listFiles(root, name, skip));
    else if (entry.isFile()) files.push(name);
    else throw new Error(`Unsupported file: ${join(root, name)}`);
  }
  return files;
}

await rm(site, { recursive: true, force: true });
await mkdir(play, { recursive: true });

// ---- original runtime ----
let original = null;
if (!lobbyOnly) {
  await readFile(join(dungeon, 'index.html'));
  // An older staging layout put the lobby inside the dist; it is not part of the runtime.
  const names = await listFiles(dungeon, '', name => name === 'play');
  const files = [];
  for (const name of names) {
    if (!validPath(name)) throw new Error(`Unsupported runtime path: ${name}`);
    const path = join(dungeon, name);
    files.push([name, await sha256File(path), (await lstat(path)).size]);
  }
  // Same version formula as the former publisher, so cached runtimes keep their names.
  const version = createHash('sha256').update(JSON.stringify({ entry: 'index.html', files })).digest('hex');
  const runtime = join(play, 'original/runtime', version);
  for (const [name] of files) {
    await mkdir(join(runtime, name, '..'), { recursive: true });
    await cp(join(dungeon, name), join(runtime, name));
  }
  await mkdir(join(play, 'original/catalogue'), { recursive: true });
  await writeFile(join(play, 'original/catalogue', `${version}.json`),
    JSON.stringify({ version, files: files.map(([name, , size]) => [name, size]) }));
  await writeFile(join(play, 'original/manifest.json'),
    JSON.stringify({ version, entry: `/play/original/runtime/${version}/index.html` }));
  original = { version, files: files.length, bytes: files.reduce((total, [, , size]) => total + size, 0) };
}

// ---- lobby ----
const staging = join(site, '.lobby');
await cp(source, staging, { recursive: true });
const runtimeFiles = [
  'avatar-catalog.mjs', 'battle.mjs', 'layout.mjs', 'rules.mjs',
  'assets/items.png', 'assets/rat.png', 'assets/tiles_prison.png', 'assets/tiles_sewers.png', 'assets/training.png',
].sort();
const runtimeHash = createHash('sha256');
for (const file of runtimeFiles) {
  const bytes = await readFile(join(staging, file));
  runtimeHash.update(`${file.length}:${file}:${bytes.length}:`).update(bytes);
}
const runtimeVersion = runtimeHash.digest('hex');
for (const file of runtimeFiles) {
  const target = join(staging, 'runtime', runtimeVersion, file);
  await mkdir(join(target, '..'), { recursive: true });
  await cp(join(staging, file), target);
}
const assetHash = createHash('sha256');
for (const name of await listFiles(staging)) {
  const bytes = await readFile(join(staging, name));
  assetHash.update(`${name.length}:${name}:${bytes.length}:`).update(bytes);
}
const signature = assetHash.digest('hex');
const clientName = `${revision}-${signature}`;
const base = `/play/client/${clientName}/`;
await writeFile(join(staging, 'runtime-manifest.json'), JSON.stringify({
  version: runtimeVersion,
  entry: `${base}runtime/${runtimeVersion}/battle.mjs`,
}));
const html = await readFile(join(staging, 'index.html'), 'utf8');
if (!html.includes('<head>')) throw new Error('Missing web client document head.');
const page = html.replace('<head>', `<head>\n  <base href="${base}">`);
await writeFile(join(staging, 'index.html'), page);
await mkdir(join(play, 'client'), { recursive: true });
await cp(staging, join(play, 'client', clientName), { recursive: true });
await rm(staging, { recursive: true });
await writeFile(join(play, 'index.html'), page);

// ---- earlier deploys ----
// play/generations.json lists the versioned folders of the last GENERATIONS deploys, newest first.
// Their folders are copied from the previous site so pages opened before a deploy keep working,
// also when the same commit is deployed twice.
const GENERATIONS = 3;
const current = { client: clientName, original: original?.version ?? null };
const carried = [];
let earlier = [];
const previous = process.env.COOP_PREVIOUS_SITE;
if (previous) {
  try {
    earlier = JSON.parse(await readFile(join(previous, 'play/generations.json'), 'utf8')).generations ?? [];
  } catch {
    // A site staged before generations.json existed: derive its own generation.
    try {
      const oldPage = await readFile(join(previous, 'play/index.html'), 'utf8');
      const oldManifest = JSON.parse(await readFile(join(previous, 'play/original/manifest.json'), 'utf8'));
      earlier = [{ client: /<base href="\/play\/client\/([a-f0-9]{40}-[a-f0-9]{64})\/">/.exec(oldPage)?.[1] ?? null,
        original: oldManifest?.version ?? null }];
    } catch (error) {
      console.warn(`Previous deploy not carried over: ${error.message}`);
    }
  }
}
const generations = [current];
for (const generation of earlier) {
  if (generations.length >= GENERATIONS) break;
  if (generations.some(kept => kept.client === generation.client && kept.original === generation.original)) continue;
  const client = /^[a-f0-9]{40}-[a-f0-9]{64}$/.test(generation.client ?? '') ? generation.client : null;
  const runtime = VERSION.test(generation.original ?? '') ? generation.original : null;
  try {
    if (client && client !== clientName) {
      await cp(join(previous, 'play/client', client), join(play, 'client', client), { recursive: true, force: false });
      carried.push(`client/${client}`);
    }
    if (runtime && original && runtime !== original.version) {
      await cp(join(previous, 'play/original/runtime', runtime), join(play, 'original/runtime', runtime), { recursive: true, force: false });
      await cp(join(previous, 'play/original/catalogue', `${runtime}.json`), join(play, 'original/catalogue', `${runtime}.json`), { force: false });
      carried.push(`original/${runtime}`);
    }
    generations.push({ client, original: runtime });
  } catch (error) {
    console.warn(`Generation ${client ?? runtime} not carried over: ${error.message}`);
  }
}
await writeFile(join(play, 'generations.json'), JSON.stringify({ generations }));

// ---- routing and headers (applied by Workers static assets without invoking the Worker) ----
// html_handling is "none" (see server/wrangler.jsonc), so the lobby page is mapped here; the 200 rule
// serves /play/index.html at /play/ without invoking the Worker.
await writeFile(join(site, '_redirects'), '/ /play/ 302\n/play /play/ 301\n/play/ /play/index.html 200\n');
await writeFile(join(site, '_headers'), `# Generated by scripts/stage-web-client.mjs
/play/client/*
  Cache-Control: public, max-age=31536000, immutable
/play/original/runtime/*
  Cache-Control: public, max-age=31536000, immutable
/play/original/catalogue/*
  Cache-Control: public, max-age=31536000, immutable
# The cache worker lives with the lobby but controls the runtime folder.
/play/client/:client/original-cache.js
  Service-Worker-Allowed: /play/original/
/*
  X-Content-Type-Options: nosniff
`);

// ---- limits ----
const all = await listFiles(site);
if (all.length > MAX_ASSET_FILES) throw new Error(`${all.length} asset files exceed the ${MAX_ASSET_FILES}-file limit.`);
let largest = ['', 0];
for (const name of all) {
  const { size } = await stat(join(site, name));
  if (size > MAX_ASSET_BYTES) throw new Error(`${name} (${size} bytes) exceeds the 25 MiB asset limit.`);
  if (size > largest[1]) largest = [name, size];
}
console.log(JSON.stringify({ site, client: base, runtime: runtimeVersion, original, carried, files: all.length,
  largest: { path: largest[0], bytes: largest[1] } }, null, 2));
