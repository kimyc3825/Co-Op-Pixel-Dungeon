// SPDX-License-Identifier: GPL-3.0-or-later
// Worker entry. Static files are served by Workers static assets before this code runs; the Worker
// only sees the socket endpoints, the health check and paths that matched no file.
import type { Env } from './env';
import { CLOSE, LAYOUT_ID, LIMITS, MATCH_ID, PATHS, WIRE_VERSION } from './protocol';
import { refuse } from './util';

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

/** Accepts the handshake only to report why the connection cannot be served, which browsers can read. */
function refusal(code: number, error: string): Response {
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();
  refuse(server, code, error);
  return new Response(null, { status: 101, webSocket: client });
}

async function connectInn(request: Request, env: Env, url: URL): Promise<Response> {
  const layout = url.searchParams.get('layout') ?? '';
  if (!LAYOUT_ID.test(layout)) return refusal(CLOSE.badMessage, 'invalid_layout');
  const hint = Number(url.searchParams.get('shard'));
  const shards = Array.from({ length: LIMITS.shardsPerLayout }, (_, index) => index);
  if (Number.isInteger(hint) && hint > 0 && hint < LIMITS.shardsPerLayout) shards.unshift(...shards.splice(hint, 1));
  for (const shard of shards) {
    const headers = new Headers(request.headers);
    headers.set('X-Inn-Shard', String(shard));
    headers.set('X-Inn-Layout', layout);
    const stub = env.INN.get(env.INN.idFromName(`${layout}:${shard}`));
    const response = await stub.fetch(url.toString(), { headers });
    if (response.status !== 409) return response;
  }
  return refusal(CLOSE.full, 'inn_full');
}

function connectLink(request: Request, env: Env, url: URL): Promise<Response> | Response {
  const id = url.searchParams.get('id') ?? '';
  if (!MATCH_ID.test(id)) return refusal(CLOSE.notFound, 'link_not_found');
  return env.LINK.get(env.LINK.idFromName(id)).fetch(request);
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
