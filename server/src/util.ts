// SPDX-License-Identifier: GPL-3.0-or-later

/** Token bucket. `capacity` tokens, refilled at `perSecond`. State lives in memory only. */
export class Bucket {
  private tokens: number;
  private at: number;

  constructor(private readonly capacity: number, private readonly perSecond: number, now = Date.now()) {
    this.tokens = capacity;
    this.at = now;
  }

  take(cost = 1, now = Date.now()): boolean {
    this.tokens = Math.min(this.capacity, this.tokens + (now - this.at) * this.perSecond / 1000);
    this.at = now;
    if (this.tokens < cost) return false;
    this.tokens -= cost;
    return true;
  }
}

const encoder = new TextEncoder();

/** Domain-separated SHA-256 of a client key, base64url without padding, cut to `length` characters. */
export async function digest(domain: string, key: string, length = 43): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(`${domain}\n${key}`)));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
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
export function refuse(ws: WebSocket, code: number, error: string): void {
  send(ws, { t: 'error', code: error });
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
