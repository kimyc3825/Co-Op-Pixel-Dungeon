// SPDX-License-Identifier: GPL-3.0-or-later

/** Token bucket. `capacity` tokens, refilled at `perSecond`. State lives in memory only. */
export class Bucket {
  private tokens: number;
  private at: number;

  constructor(private readonly capacity: number, private readonly perSecond: number, now = Date.now()) {
    this.tokens = capacity;
    this.at = now;
  }

  /** True if `cost` tokens are available now; spends nothing. */
  has(cost = 1, now = Date.now()): boolean {
    this.tokens = Math.min(this.capacity, this.tokens + (now - this.at) * this.perSecond / 1000);
    this.at = now;
    return this.tokens >= cost;
  }

  take(cost = 1, now = Date.now()): boolean {
    if (!this.has(cost, now)) return false;
    this.tokens -= cost;
    return true;
  }
}

/** Buckets per key (a client address), bounded in size; memory only. */
export class BucketMap {
  private readonly buckets = new Map<string, Bucket>();
  constructor(private readonly capacity: number, private readonly perSecond: number, private readonly maxKeys = 1024) {}

  get(key: string): Bucket {
    let bucket = this.buckets.get(key);
    if (!bucket) {
      if (this.buckets.size >= this.maxKeys) this.buckets.delete(this.buckets.keys().next().value!);
      bucket = new Bucket(this.capacity, this.perSecond);
      this.buckets.set(key, bucket);
    }
    return bucket;
  }
}

/**
 * The key for per-address limits: an IPv4 address as is, an IPv6 address cut to its /64 (one
 * subscriber usually holds a whole /64). Loopback and missing addresses give '' (no limit).
 */
export function addressKey(value: string | null): string {
  const address = (value ?? '').trim().toLowerCase();
  if (!address) return '';
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(address);
  if (mapped) return addressKey(mapped[1]);
  if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) return address.startsWith('127.') ? '' : address;
  if (!address.includes(':') || !/^[0-9a-f:]+$/.test(address)) return '';
  const [head, tail = ''] = address.split('::');
  const left = head ? head.split(':') : [];
  const right = address.includes('::') ? (tail ? tail.split(':') : []) : [];
  const groups = address.includes('::') ? [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill('0'), ...right] : left;
  if (groups.length !== 8) return '';
  const full = groups.map(group => (parseInt(group, 16) || 0).toString(16));
  if (full.every((group, index) => index === 7 ? group === '1' : group === '0')) return '';
  return `${full.slice(0, 4).join(':')}::/64`;
}

const encoder = new TextEncoder();
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

/**
 * Synchronous SHA-256. Handlers hash keys without awaiting, so no other socket event can run in the
 * middle of a hello (crypto.subtle is asynchronous and would let frames interleave).
 */
export function sha256(data: Uint8Array): Uint8Array {
  const length = data.length;
  const blocks = Math.ceil((length + 9) / 64);
  const padded = new Uint8Array(blocks * 64);
  padded.set(data);
  padded[length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(length / 0x20000000));
  view.setUint32(padded.length - 4, (length * 8) >>> 0);
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let block = 0; block < blocks; block++) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(block * 64 + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
  }
  const out = new Uint8Array(32);
  const outView = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) outView.setUint32(i * 4, h[i]);
  return out;
}

/** Domain-separated SHA-256 of a client key, base64url without padding, cut to `length` characters. */
export function digest(domain: string, key: string, length = 43): string {
  let binary = '';
  for (const byte of sha256(encoder.encode(`${domain}\n${key}`))) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '').slice(0, length);
}

export function send(ws: WebSocket, message: unknown): boolean {
  try {
    ws.send(typeof message === 'string' ? message : JSON.stringify(message));
    return true;
  } catch {
    return false;
  }
}

export function close(ws: WebSocket, code: number, reason: string): void {
  try { ws.close(code, reason); } catch { /* already closed */ }
}

/** Sends a final error frame and closes, as every refusal in the protocol does. */
export function refuse(ws: WebSocket, code: number, error: string, extra: Record<string, unknown> = {}): void {
  send(ws, { t: 'error', code: error, ...extra });
  close(ws, code, error);
}

/** Completes the close handshake for a hibernatable socket. */
export function acknowledgeClose(ws: WebSocket, code: number, reason: string): void {
  if (code >= 1000 && code < 5000 && ![1004, 1005, 1006, 1015].includes(code)) close(ws, code, reason);
  else try { ws.close(); } catch { /* already closed */ }
}

export function randomUint32(): number {
  return crypto.getRandomValues(new Uint32Array(1))[0];
}

export function randomIndex(length: number): number {
  return randomUint32() % length;
}
