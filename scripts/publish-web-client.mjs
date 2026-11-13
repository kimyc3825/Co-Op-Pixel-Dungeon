// SPDX-License-Identifier: GPL-3.0-or-later
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
const ORIGINAL_MAX_FILE_BYTES = 64 * 1024 * 1024;
const ORIGINAL_MAX_TOTAL_BYTES = 512 * 1024 * 1024;
const ORIGINAL_MAX_FILES = 2048;

function validOriginalPath(path) {
  return typeof path === 'string' && path.length <= 320 &&
    path.split('/').every(segment => /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,119}$/.test(segment));
}

function originalVersion(entry, files) {
  return createHash('sha256').update(JSON.stringify({ entry, files })).digest('hex');
}

const UPLOAD_ROOT = '/api/admin/original/';
const PARALLEL_UPLOADS = 6;
const MAX_ATTEMPTS = 4;
const revisions = /^[a-f0-9]{40}$/;
const signatures = /^[a-f0-9]{64}$/;

async function publisherToken() {
  if (!process.stdin.isTTY && process.env.ORIGINAL_PUBLISH_TOKEN) return process.env.ORIGINAL_PUBLISH_TOKEN;
  const interactive = process.stdin.isTTY;
  const wasRaw = process.stdin.isRaw;
  if (interactive) {
    process.stdin.setRawMode(true);
    process.stderr.write('Enter ORIGINAL_PUBLISH_TOKEN (input is hidden):\n');
  }
  try {
    let token = '';
    for await (const chunk of process.stdin.setEncoding('utf8')) {
      if (chunk.includes('\u0003')) throw new Error('Upload cancelled.');
      const end = chunk.search(/[\r\n]/);
      token += end < 0 ? chunk : chunk.slice(0, end);
      if (token.length > 256) throw new Error('Invalid publishing token.');
      if (end >= 0) break;
    }
    return token;
  } finally {
    if (interactive) process.stdin.setRawMode(wasRaw);
  }
}

function siteOrigin(value) {
  const url = new URL(value);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) ||
      url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Provide the HTTPS origin of the Site, or a loopback preview origin.');
  }
  return url.origin;
}

async function sha256(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

async function catalogue(root) {
  let total = 0;
  const files = [];
  async function scan(folder, parent) {
    for (const dirent of await readdir(folder, { withFileTypes: true })) {
      const name = parent ? `${parent}/${dirent.name}` : dirent.name;
      if (!validOriginalPath(name)) throw new Error(`Unsupported artifact path: ${name}`);
      const path = join(folder, dirent.name);
      if (dirent.isDirectory()) {
        await scan(path, name);
      } else if (dirent.isFile()) {
        const { size } = await lstat(path);
        if (size > ORIGINAL_MAX_FILE_BYTES) throw new Error(`Artifact file exceeds ${ORIGINAL_MAX_FILE_BYTES} bytes: ${name}`);
        total += size;
        if (total > ORIGINAL_MAX_TOTAL_BYTES || files.length >= ORIGINAL_MAX_FILES) {
          throw new Error(`Artifact exceeds ${ORIGINAL_MAX_FILES} files or ${ORIGINAL_MAX_TOTAL_BYTES} bytes.`);
        }
        files.push([name, await sha256(path), size]);
      } else {
        throw new Error(`Unsupported artifact entry: ${name}`);
      }
    }
  }
  if (!(await lstat(root)).isDirectory()) throw new Error('The artifact must be a directory.');
  await scan(root, '');
  files.sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0);
  return { files, total };
}

async function put(url, authorization, options, label) {
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const { body, headers } = options();
    let response;
    try {
      response = await fetch(url, {
        method: 'PUT', body, headers: { ...headers, Authorization: authorization },
        duplex: 'half', redirect: 'error', signal: AbortSignal.timeout(120_000),
      });
    } catch {
      body?.destroy?.();
      if (attempt === MAX_ATTEMPTS - 1) throw new Error(`Connection failed while uploading ${label}.`);
      await delay(500 * 2 ** attempt);
      continue;
    }
    if (response.ok) return response;
    const detail = (await response.json().catch(() => null))?.error;
    if ((response.status !== 429 && response.status < 500) || attempt === MAX_ATTEMPTS - 1) {
      const code = typeof detail === 'string' && /^[a-z_]{1,64}$/.test(detail) ? ` ${detail}` : '';
      throw new Error(`Upload failed for ${label} (HTTP ${response.status}${code}).`);
    }
    await delay(500 * 2 ** attempt);
  }
}

async function uploadFiles(origin, authorization, root, version, files) {
  let next = 0;
  let uploaded = 0;
  let failure;
  async function uploadNext() {
    while (!failure && next < files.length) {
      const [name, sha, size] = files[next++];
      try {
        await put(`${origin}${UPLOAD_ROOT}runtime/${version}/${name}`, authorization, () => ({
          body: size ? createReadStream(join(root, name)) : Buffer.alloc(0),
          headers: { 'X-Original-Sha256': sha, 'Content-Length': String(size) },
        }), name);
        uploaded++;
        if (uploaded % 100 === 0 || uploaded === files.length) console.log(`Uploaded ${uploaded}/${files.length} files`);
      } catch (error) {
        failure ??= error;
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(PARALLEL_UPLOADS, files.length) }, uploadNext));
  if (failure) throw failure;
}

async function main() {
  const [base, directory = 'html/build/dist/web', entry = 'index.html', extra] = process.argv.slice(2);
  if (!base || !directory || extra || !validOriginalPath(entry) || !entry.toLowerCase().endsWith('.html')) {
    throw new Error('Usage: node scripts/publish-web-client.mjs <site-origin> [artifact-directory] [entry.html]');
  }
  const origin = siteOrigin(base);
  const root = resolve(directory);
  const { files, total } = await catalogue(root);
  if (!files.find(([name]) => name === entry)?.[2]) throw new Error(`Missing or empty artifact entry: ${entry}`);
  const version = await originalVersion(entry, files);
  console.log(`Original runtime ${version} (${files.length} files, ${total} bytes)`);

  const clientRevision = process.env.COOP_CLIENT_REVISION;
  const clientSignature = process.env.COOP_CLIENT_SIGNATURE;
  if (files.some(([file]) => file.startsWith('play/')) && (!revisions.test(clientRevision ?? '') || !signatures.test(clientSignature ?? ''))) {
    throw new Error('The web lobby build must provide its source revision and asset signature.');
  }
  const token = await publisherToken();
  if (!/^\S{1,256}$/.test(token)) throw new Error('Provide ORIGINAL_PUBLISH_TOKEN on standard input.');
  const authorization = `Bearer ${token}`;
  await uploadFiles(origin, authorization, root, version, files);
  const manifest = JSON.stringify({ version, entry, files, clientRevision, clientSignature });
  const published = await put(`${origin}${UPLOAD_ROOT}manifest.json`, authorization,
    () => ({ body: manifest, headers: { 'Content-Type': 'application/json' } }), 'current manifest');
  const result = await published.json();
  if (result?.version !== version || result.entry !== `/play/original/runtime/${version}/${entry}` ||
      (clientRevision && !signatures.test(result.clientVersion ?? ''))) {
    throw new Error('The Worker returned an unexpected published manifest.');
  }
  if (clientRevision) {
    const expected = `/play/client/${clientRevision}-${clientSignature}/`;
    const [page, client] = await Promise.all([
      fetch(origin + '/play/', { cache: 'no-store', signal: AbortSignal.timeout(15_000) }),
      fetch(origin + expected + 'main.mjs', { signal: AbortSignal.timeout(15_000) }),
    ]);
    if (!page.ok || !(await page.text()).includes(`<base href="${expected}">`) || !client.ok) {
      throw new Error('The new web client did not become active.');
    }
    await client.body?.cancel();
  }
  console.log(JSON.stringify(result));
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
