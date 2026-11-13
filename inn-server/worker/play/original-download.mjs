// SPDX-License-Identifier: GPL-3.0-or-later

const ROOT = '/play/original/';
const VERSION = /^[0-9a-f]{64}$/;
const FILE = /^(?:[a-zA-Z0-9_-][a-zA-Z0-9_.-]*\/)*[a-zA-Z0-9_-][a-zA-Z0-9_.-]*$/;
const PARALLEL = 3;
const REQUEST_TIMEOUT = 120_000;
const RETRY_DELAY = 12_000;

async function readJson(url, cache = 'default') {
  const response = await fetch(url, { cache, signal: AbortSignal.timeout(6000) });
  if (!response.ok) throw new Error('Original game file list unavailable');
  return response.json();
}

async function currentRuntime() {
  const { version, entry } = await readJson(`${ROOT}manifest.json`, 'no-store');
  if (!VERSION.test(version) || entry !== `${ROOT}runtime/${version}/index.html`) {
    throw new Error('Invalid original game manifest');
  }
  return { version, entry };
}

function startupPriority(path) {
  if (path === 'index.html') return 0;
  if (path === 'boot.js') return 1;
  if (path === 'spd.js' || path.endsWith('/spd.js')) return 2;
  if (path.includes('/scripts/')) return 3;
  if (path.includes('/assets/fonts/')) return 4;
  return 5;
}

async function startupFiles(version) {
  const listing = await readJson(`${ROOT}catalogue/${version}.json`);
  if (listing.version !== version || !Array.isArray(listing.files) || listing.files.length > 2048) {
    throw new Error('Invalid original game file list');
  }
  return listing.files.filter(entry => {
    if (!Array.isArray(entry) || entry.length !== 2) return false;
    const [path, size] = entry;
    return typeof path === 'string' && path.length <= 320 && FILE.test(path) &&
      Number.isSafeInteger(size) && size >= 0 && size <= 64 * 1024 * 1024 &&
      !/(^|\/)assets\/music\//.test(path) && !path.startsWith('licenses/') && path !== 'LICENSE.txt';
  }).sort(([left], [right]) => startupPriority(left) - startupPriority(right));
}

function waitForActivation(registration) {
  if (!registration) return null;
  const worker = registration.active ?? registration.waiting ?? registration.installing;
  if (!worker || worker.state === 'activated') return worker;
  return new Promise(resolve => {
    const finish = result => {
      clearTimeout(timer);
      worker.removeEventListener('statechange', check);
      resolve(result);
    };
    const check = () => {
      if (worker.state === 'activated') finish(worker);
      else if (worker.state === 'redundant') finish(null);
    };
    const timer = setTimeout(() => finish(null), 1600);
    worker.addEventListener('statechange', check);
    check();
  });
}

async function warmFile(worker, path) {
  if (!worker || worker.state === 'redundant') {
    const response = await fetch(path, { cache: 'force-cache', priority: 'low', signal: AbortSignal.timeout(REQUEST_TIMEOUT) });
    if (!response.ok) return false;
    await response.arrayBuffer();
    return true;
  }
  const { port1, port2 } = new MessageChannel();
  return new Promise(resolve => {
    const finish = ok => {
      clearTimeout(timer);
      port1.close();
      resolve(ok);
    };
    const timer = setTimeout(() => finish(false), REQUEST_TIMEOUT);
    port1.onmessage = event => finish(event.data?.ok === true);
    port1.onmessageerror = () => finish(false);
    try {
      worker.postMessage({ type: 'noxac-original-warm', url: new URL(path, location.origin).href }, [port2]);
    } catch {
      port2.close();
      finish(false);
    }
  });
}

export class OriginalDownload {
  #registration;
  #manifest;
  #job;
  #retryAt = 0;

  constructor(onProgress) {
    this.onProgress = onProgress;
  }

  warm() {
    if (this.#manifest || this.#job?.running || this.#job?.ready || performance.now() < this.#retryAt) return;
    this.#retryAt = performance.now() + RETRY_DELAY;
    void this.#current().then(runtime => this.#begin(runtime)).catch(() => {});
  }

  async enter(signal) {
    const runtime = await this.#current();
    signal.throwIfAborted();
    const job = this.#begin(runtime);
    const worker = await job.worker;
    signal.throwIfAborted();
    if (!worker) {
      // Finish existing HTTP-cache transfers before the iframe starts its own requests.
      job.stop = true;
      await Promise.all(job.inFlight);
      signal.throwIfAborted();
    }
    return runtime.entry;
  }

  get label() {
    const job = this.#job;
    if (!job?.running || job.stop) return '던전 불러오는 중';
    const percent = job.total ? ` · ${Math.floor(job.loaded * 100 / job.total)}%` : '';
    return `던전 이어 받는 중${percent}`;
  }

  #current() {
    return this.#manifest ??= currentRuntime().finally(() => { this.#manifest = null; });
  }

  async #cacheWorker() {
    if (!navigator.serviceWorker) return null;
    this.#registration ??= navigator.serviceWorker.register('/play/original-cache.js', { scope: ROOT }).catch(() => {
      this.#registration = undefined;
      return null;
    });
    let timer;
    const timeout = new Promise(resolve => { timer = setTimeout(() => resolve(null), 1600); });
    const registration = await Promise.race([this.#registration, timeout]);
    clearTimeout(timer);
    return waitForActivation(registration);
  }

  #begin({ version }) {
    if (this.#job?.version === version && (this.#job.running || this.#job.ready)) return this.#job;
    if (this.#job) this.#job.stop = true;
    const job = { version, worker: this.#cacheWorker(), running: true, ready: false, stop: false,
      loaded: 0, total: 0, inFlight: new Set() };
    this.#job = job;
    void this.#download(job).catch(() => {}).finally(() => {
      job.running = false;
      if (this.#job === job) {
        if (!job.ready) this.#retryAt = performance.now() + RETRY_DELAY;
        this.onProgress();
      }
    });
    return job;
  }

  async #download(job) {
    const [worker, files] = await Promise.all([job.worker, startupFiles(job.version)]);
    if (!files.some(([path]) => path === 'index.html')) return;
    job.total = files.reduce((total, [, size]) => total + size, 0);
    let next = 0;
    let failed = false;
    const pull = async () => {
      while (!job.stop && next < files.length) {
        const [file, size] = files[next++];
        const transfer = warmFile(worker, `${ROOT}runtime/${job.version}/${file}`).catch(() => false);
        job.inFlight.add(transfer);
        const ok = await transfer;
        job.inFlight.delete(transfer);
        if (ok) job.loaded += size;
        else failed = true;
        if (this.#job === job) this.onProgress();
      }
    };
    await Promise.all(Array.from({ length: PARALLEL }, pull));
    job.ready = !job.stop && !failed;
  }
}
