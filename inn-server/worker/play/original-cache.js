// SPDX-License-Identifier: GPL-3.0-or-later
const CACHE_PREFIX = 'noxac-original-assets-v1:';
const FILE_PATH = /^\/play\/original\/runtime\/([a-f0-9]{64})\/(.+)$/;
const SEGMENT = /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,119}$/;
const downloads = new Map();
let warmVersion;

function immutableAsset(value) {
  if (typeof value !== 'string') return null;
  let url;
  try { url = new URL(value); } catch { return null; }
  const match = FILE_PATH.exec(url.pathname);
  if (url.origin !== self.location.origin || url.username || url.password || url.search || !match ||
      match[2].length > 320 || !match[2].split('/').every((part) => SEGMENT.test(part))) return null;
  url.hash = '';
  return { url: url.href, cacheName: CACHE_PREFIX + match[1] };
}

async function loadAsset(asset, request) {
  let cache;
  try {
    cache = await caches.open(asset.cacheName);
    if (await cache.match(asset.url)) return { cache };
  } catch {
    cache = null;
  }

  const networkRequest = request ?? new Request(asset.url, { cache: 'force-cache', priority: 'low', redirect: 'error' });
  let response = await fetch(networkRequest);
  if (cache && response.status === 200 && !response.redirected) {
    try {
      await cache.put(asset.url, response);
      return { cache };
    } catch {
      // A failed write may have consumed the response. The HTTP cache can still serve it.
      response = await fetch(networkRequest);
    }
  }
  if (request) return { response };
  if (response.status !== 200 || response.redirected) {
    await response.body?.cancel();
    return { consumed: false };
  }
  await response.clone().body?.pipeTo(new WritableStream());
  return { response, consumed: true };
}

function download(asset, request) {
  const existing = downloads.get(asset.url);
  if (existing) return existing;
  const pending = loadAsset(asset, request);
  downloads.set(asset.url, pending);
  const remove = () => downloads.delete(asset.url);
  pending.then(remove, remove);
  return pending;
}

async function serve(asset, request) {
  try {
    const { cache, response } = await download(asset, request);
    if (cache) {
      const stored = await cache.match(asset.url);
      if (stored) return stored;
    }
    if (response) return response.clone();
  } catch {
    // The normal browser request remains available if caching fails.
  }
  return fetch(request);
}

async function retainRecentCaches(cacheName) {
  if (warmVersion === cacheName) return;
  warmVersion = cacheName;
  try {
    const others = (await caches.keys()).filter((name) => name.startsWith(CACHE_PREFIX) && name !== cacheName);
    if (warmVersion === cacheName) await Promise.all(others.slice(0, -1).map((name) => caches.delete(name)));
  } catch {
    // Eviction is opportunistic.
  }
}

async function warm(asset) {
  const { cache, response, consumed } = await download(asset);
  if (cache) {
    await retainRecentCaches(asset.cacheName);
    return true;
  }
  if (consumed !== undefined) return consumed;
  if (!response || response.status !== 200 || response.redirected) return false;
  await response.clone().body?.pipeTo(new WritableStream());
  return true;
}

self.addEventListener('install', (event) => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('message', (event) => {
  const port = event.ports[0];
  if (event.data?.type !== 'noxac-original-warm' || !port) return;
  const source = event.source?.url;
  const asset = source && new URL(source).origin === self.location.origin && immutableAsset(event.data.url);
  if (!asset) {
    port.postMessage({ ok: false });
    return;
  }
  event.waitUntil(warm(asset).then((ok) => port.postMessage({ ok }), () => port.postMessage({ ok: false })));
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET' || request.headers.has('Range')) return;
  const asset = immutableAsset(request.url);
  if (asset) event.respondWith(serve(asset, request));
});
