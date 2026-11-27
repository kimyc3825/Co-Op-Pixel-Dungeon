// SPDX-License-Identifier: GPL-3.0-or-later
import { exports } from 'cloudflare:workers';
import * as testing from 'cloudflare:test';

export type Frame = Record<string, any>;

let counter = 0;
export const layoutId = () => (++counter).toString(16).padStart(8, '0').repeat(8);
export const newKey = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
};
export const appearance = { job: 'mage', hairColor: 'natural', outfitColor: 'blue', hairStyle: 'short', accessory: 'none' };

/** A test-side WebSocket with an awaitable inbox. */
export class Client {
  readonly frames: Frame[] = [];
  closed: { code: number; reason: string } | null = null;
  private waiters: (() => void)[] = [];
  private ref = 0;

  constructor(readonly ws: WebSocket) {
    ws.addEventListener('message', event => {
      this.frames.push(typeof event.data === 'string' && event.data !== 'pong' ? JSON.parse(event.data) : { t: event.data });
      this.wake();
    });
    ws.addEventListener('close', event => {
      this.closed = { code: event.code, reason: event.reason };
      this.wake();
    });
    ws.accept();
  }

  private wake() { for (const waiter of this.waiters.splice(0)) waiter(); }

  send(frame: Frame | string) { this.ws.send(typeof frame === 'string' ? frame : JSON.stringify(frame)); }

  /** Sends with a fresh `ref` and resolves with the matching `ok` or `error`. */
  async request(frame: Frame): Promise<Frame> {
    const ref = ++this.ref;
    this.send({ ...frame, ref });
    return this.next(candidate => (candidate.t === 'ok' || candidate.t === 'error') && candidate.ref === ref);
  }

  /** Removes and returns the first frame matching `match`, waiting up to `timeout` ms. */
  async next(match: string | ((frame: Frame) => boolean), timeout = 2000): Promise<Frame> {
    const test = typeof match === 'string' ? (frame: Frame) => frame.t === match : match;
    const deadline = Date.now() + timeout;
    for (;;) {
      const index = this.frames.findIndex(test);
      if (index >= 0) return this.frames.splice(index, 1)[0];
      if (this.closed) throw new Error(`socket closed ${this.closed.code} ${this.closed.reason} while waiting`);
      const left = deadline - Date.now();
      if (left <= 0) throw new Error(`timed out waiting; inbox ${JSON.stringify(this.frames)}`);
      await new Promise<void>(resolve => {
        const timer = setTimeout(resolve, left);
        this.waiters.push(() => { clearTimeout(timer); resolve(); });
      });
    }
  }

  async waitClosed(timeout = 2000): Promise<{ code: number; reason: string }> {
    const deadline = Date.now() + timeout;
    while (!this.closed) {
      const left = deadline - Date.now();
      if (left <= 0) throw new Error('socket did not close');
      await new Promise<void>(resolve => {
        const timer = setTimeout(resolve, left);
        this.waiters.push(() => { clearTimeout(timer); resolve(); });
      });
    }
    return this.closed;
  }

  /** Lets in-flight frames arrive, then reports whether any frame matches. */
  async has(match: string | ((frame: Frame) => boolean), wait = 150): Promise<boolean> {
    await new Promise(resolve => setTimeout(resolve, wait));
    const test = typeof match === 'string' ? (frame: Frame) => frame.t === match : match;
    return this.frames.some(test);
  }

  close(code = 1000) { try { this.ws.close(code, 'test'); } catch { /* closed */ } }
}

export async function open(path: string, headers: Record<string, string> = {}): Promise<Client> {
  const response = await exports.default.fetch(`https://inn.test${path}`, { headers: { Upgrade: 'websocket', ...headers } });
  if (response.status !== 101 || !response.webSocket) throw new Error(`upgrade failed: ${response.status}`);
  return new Client(response.webSocket);
}

export interface Player { client: Client; key: string; welcome: Frame; id: string }

export async function enter(layout: string, options: Frame = {}, query = '', headers: Record<string, string> = {}): Promise<Player> {
  const key = options.key ?? newKey();
  const client = await open(`/api/v5/inn?layout=${layout}${query}`, headers);
  client.send({ t: 'hello', v: 1, key, name: options.name ?? 'tester', appearance, dungeonProtocol: 11, x: 10, y: 14, ...options });
  const welcome = await client.next('welcome');
  return { client, key, welcome, id: welcome.selfId };
}

export async function openLink(matchId: string, key: string): Promise<{ client: Client; link: Frame }> {
  const client = await open(`/api/v5/link?id=${matchId}`);
  client.send({ t: 'hello', v: 1, key });
  return { client, link: await client.next('link') };
}

export const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/** `runInDurableObject` without its deep generic types; tests reach into private state. */
export function inObject(stub: unknown, callback: (instance: any, state: DurableObjectState) => unknown): Promise<unknown> {
  return (testing as Record<string, any>).runInDurableObject(stub, callback);
}
