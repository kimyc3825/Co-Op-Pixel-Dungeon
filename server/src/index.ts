// SPDX-License-Identifier: GPL-3.0-or-later
// Worker entry. Static files are served by Workers static assets before this code runs; the Worker
// only sees the socket endpoints, the health check and paths that matched no file.
import type { Env } from './env';
import { CLOSE, LAYOUT_ID, LIMITS, MATCH_ID, PATHS, WIRE_VERSION } from './protocol';
import { innStub, linkStub } from './placement';
import { addressKey, refuse } from './util';

export { InnRoom } from './inn';
export { PartyLink } from './link';

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
});

/**
 * Browsers always send Origin on WebSocket handshakes; only same-origin pages (plus configured
 * development origins) may connect. Non-browser clients such as the Windows client send none.
 */
export function originAllowed(request: Request, env: Env): boolean {
  const origin = request.headers.get('Origin');
  if (origin === null) return true;
  if (origin === new URL(request.url).origin) return true;
  return (env.ALLOWED_ORIGINS ?? '').split(',').map(value => value.trim()).filter(Boolean).includes(origin);
}

/**
 * The per-address limit key. Cloudflare's edge always sets CF-Connecting-IP; a local `wrangler dev`
 * (loopback host) passes through whatever the client sent, so it is ignored there.
 */
function clientAddress(request: Request, url: URL): string {
  if (['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) return '';
  return addressKey(request.headers.get('CF-Connecting-IP'));
}

/** Accepts the handshake only to report why the connection cannot be served, which browsers can read. */
function refusal(code: number, error: string, extra: Record<string, unknown> = {}): Response {
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();
  refuse(server, code, error, extra);
  return new Response(null, { status: 101, webSocket: client });
}

async function connectInn(request: Request, env: Env, url: URL): Promise<Response> {
  const layout = url.searchParams.get('layout') ?? '';
  if (!LAYOUT_ID.test(layout)) return refusal(CLOSE.badMessage, 'invalid_layout');
  const hint = Number(url.searchParams.get('shard'));
  const shards = Array.from({ length: LIMITS.shardsPerLayout }, (_, index) => index);
  if (Number.isInteger(hint) && hint > 0 && hint < LIMITS.shardsPerLayout) shards.unshift(...shards.splice(hint, 1));
  const address = clientAddress(request, url);
  let addressCapped = false;
  for (const shard of shards) {
    const headers = new Headers(request.headers);
    headers.set('X-Inn-Shard', String(shard));
    headers.set('X-Inn-Layout', layout);
    headers.set('X-Client-Address', address);
    const stub = innStub(env, layout, shard);
    const response = await stub.fetch(url.toString(), { headers });
    // 409 is about this shard's capacity (or this address's share of it): try the next shard.
    if (response.status === 409) {
      addressCapped ||= response.headers.get('X-Refusal') === 'address';
      continue;
    }
    if (response.status === 429) return refusal(CLOSE.rateLimited, 'rate_limited');
    return response;
  }
  if (addressCapped) return refusal(CLOSE.tooManyConnections, 'too_many_connections');
  return refusal(CLOSE.full, 'inn_full', { retryAfter: LIMITS.fullRetryAfterS });
}

function connectLink(request: Request, env: Env, url: URL): Promise<Response> | Response {
  const id = url.searchParams.get('id') ?? '';
  if (!MATCH_ID.test(id)) return refusal(CLOSE.notFound, 'link_not_found');
  return linkStub(env, id).fetch(request);
}

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === PATHS.health) return json({ ok: true, protocol: WIRE_VERSION });
    if (url.pathname === PATHS.inn || url.pathname === PATHS.link) {
      if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
        return json({ error: 'websocket_required' }, 426);
      }
      if (!originAllowed(request, env)) return json({ error: 'origin_not_allowed' }, 403);
      return url.pathname === PATHS.inn ? connectInn(request, env, url) : connectLink(request, env, url);
    }
    return json({ error: 'not_found' }, 404);
  },
} satisfies ExportedHandler<Env>;
