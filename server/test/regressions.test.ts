// SPDX-License-Identifier: GPL-3.0-or-later
// Regressions for the findings of the independent review (storage, quotas, presence, link loss, payload size).
import { env, exports } from 'cloudflare:workers';
import * as testing from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { appearance, enter, inObject, layoutId, newKey, open, openLink, sleep } from './helpers';
import { digest, sha256 } from '../src/util';
import { innName } from '../src/placement';

const t = testing as Record<string, any>;
const innStub = (layout: string, shard = 0) => env.INN.get(env.INN.idFromName(innName(layout, shard))) as unknown as DurableObjectStub;
const linkStub = (id: string) => env.LINK.get(env.LINK.idFromName(id)) as unknown as DurableObjectStub;
// An object that never wrote anything reports one empty 4 KiB page and no tables.
const EMPTY = { size: 4096, tables: [], keys: [], alarm: null };
const storageOf = (stub: DurableObjectStub) => inObject(stub, async (_instance: any, state: DurableObjectState) => ({
  size: state.storage.sql.databaseSize,
  tables: state.storage.sql.exec("SELECT name FROM sqlite_master WHERE name NOT LIKE '_cf_%'").toArray().map((row: any) => row.name),
  keys: [...(await state.storage.list()).keys()], alarm: await state.storage.getAlarm(),
})) as Promise<{ size: number; tables: string[]; keys: string[]; alarm: number | null }>;

async function matched(layout = layoutId()) {
  const host = await enter(layout, { name: 'H' });
  const guest = await enter(layout, { name: 'G' });
  await host.client.request({ t: 'queue' });
  await guest.client.request({ t: 'join', targetId: host.id });
  const { match } = await host.client.next('matched');
  await guest.client.next('matched');
  return { layout, host, guest, match };
}

async function linked() {
  const pair = await matched();
  const h = await openLink(pair.match.id, pair.host.key);
  const g = await openLink(pair.match.id, pair.guest.key);
  await h.client.next('paired');
  await g.client.next('paired');
  return { ...pair, h, g };
}

describe('hashing', () => {
  it('matches crypto.subtle SHA-256', async () => {
    for (const text of ['', 'abc', 'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(64), '가나다'.repeat(100)]) {
      const bytes = new TextEncoder().encode(text);
      const expected = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
      expect([...sha256(bytes)]).toEqual([...expected]);
    }
    expect(digest('inn-player', 'k', 22)).toHaveLength(22);
  });
});

describe('item 1-2: storage and quotas', () => {
  it('an inn room never writes storage, whatever its players do, and leaves nothing behind', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    const b = await enter(layout);
    await a.client.request({ t: 'chat', text: 'hello' });
    await a.client.request({ t: 'drink' });
    await a.client.request({ t: 'interact', npcId: 'innkeeper' });
    await a.client.request({ t: 'prepare' });
    await a.client.request({ t: 'move', x: 11, y: 14 });
    await a.client.request({ t: 'away' });
    await a.client.request({ t: 'enter', x: 10, y: 14 });
    await b.client.request({ t: 'queue' });
    expect(await storageOf(innStub(layout))).toEqual(EMPTY);
    a.client.close();
    b.client.close();
    await sleep(100);
    expect(await storageOf(innStub(layout))).toEqual(EMPTY);
  });

  it('deletes data an older server version stored once the room empties', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    await inObject(innStub(layout), (_i: any, state: DurableObjectState) => {
      state.storage.sql.exec('CREATE TABLE events (id INTEGER PRIMARY KEY, body TEXT)');
      state.storage.sql.exec("INSERT INTO events VALUES (1, 'old chat')");
    });
    a.client.close();
    await sleep(100);
    expect(await storageOf(innStub(layout))).toEqual(EMPTY);
  });

  it('random layout ids and shards leave no storage', async () => {
    for (let i = 0; i < 3; i++) {
      const layout = layoutId();
      const client = await open(`/api/v5/inn?layout=${layout}&shard=5`);
      client.close();
      await sleep(50);
      expect(await storageOf(innStub(layout))).toEqual(EMPTY);
    }
  });

  it('unknown link ids leave no storage and an ended link deletes everything', async () => {
    const id = crypto.randomUUID();
    const unknown = await open(`/api/v5/link?id=${id}`);
    await unknown.waitClosed();
    expect(await storageOf(linkStub(id))).toEqual(EMPTY);

    const { match, h, g } = await linked();
    expect((await storageOf(linkStub(match.id))).keys).toEqual(['link']);
    g.client.send({ t: 'leave' });
    await h.client.waitClosed();
    await sleep(50);
    const after = await storageOf(linkStub(match.id));
    expect(after.keys).toEqual([]);
    expect(after.alarm).toBeNull();
  });

  it('a link writes one row per state change and nothing per relayed frame', async () => {
    const { match, h, g } = await linked();
    const before = await storageOf(linkStub(match.id));
    for (let i = 0; i < 30; i++) h.client.send({ t: 'data', d: `S|${i}` });
    for (let i = 0; i < 30; i++) await g.client.next('data');
    const after = await storageOf(linkStub(match.id));
    expect(after.size).toBe(before.size);
  });

  it('tells a locked-out client when to retry', async () => {
    const layout = layoutId();
    const clients = [];
    for (let shard = 0; shard < 8; shard++) for (let i = 0; i < 32; i++) {
      clients.push(await open(`/api/v5/inn?layout=${layout}&shard=${shard}`, { 'CF-Connecting-IP': `198.51.${shard}.${i}` }));
    }
    const locked = await open(`/api/v5/inn?layout=${layout}`);
    expect(await locked.next('error')).toEqual({ t: 'error', code: 'inn_full', retryAfter: 30 });
    expect((await locked.waitClosed()).code).toBe(4013);
    for (const client of clients) client.close();
  }, 60_000);
});

describe('item 3: presence bookkeeping', () => {
  it('a reconnect with present:false tells the room the player left', async () => {
    const layout = layoutId();
    const watcher = await enter(layout, { name: 'W' });
    const a = await enter(layout, { name: 'A' });
    await watcher.client.next('person');
    await enter(layout, { key: a.key, name: 'A', present: false });
    expect(await watcher.client.next('gone')).toEqual({ t: 'gone', id: a.id });
    const c = await enter(layout, { name: 'C' });
    expect(c.welcome.people.map((p: any) => p.name).sort()).toEqual(['C', 'W']);
  });

  it('announces the leave of a player whose earlier socket was replaced', async () => {
    const layout = layoutId();
    const watcher = await enter(layout, { name: 'W' });
    const a = await enter(layout, { name: 'A' });
    await watcher.client.next('person');
    const again = await enter(layout, { key: a.key, name: 'A' });
    await a.client.waitClosed();
    again.client.close();
    expect(await watcher.client.next('gone')).toEqual({ t: 'gone', id: a.id });
  });

  it('ignores everything from a socket the server already closed', async () => {
    const layout = layoutId();
    const watcher = await enter(layout, { name: 'W' });
    const a = await enter(layout, { name: 'A' });
    await watcher.client.next('person');
    const key = a.key;
    // Simulate a replaced socket whose close never completed: mark it closed and feed it frames.
    await inObject(innStub(layout), async (instance: any, state: DurableObjectState) => {
      const ws = state.getWebSockets().find(socket => socket.deserializeAttachment().id === a.id)!;
      const conn = instance.conn(ws);
      instance.retire(ws, conn);
      await instance.webSocketMessage(ws, JSON.stringify({ t: 'chat', text: 'boo' }));
      await instance.webSocketMessage(ws, JSON.stringify({ t: 'queue' }));
      // A fresh hello on the zombie must not resurrect it either.
      conn.member.phase = 'closed';
      await instance.webSocketMessage(ws, JSON.stringify({ t: 'hello', v: 1, key, name: 'Zombie', appearance,
        dungeonProtocol: 12, x: 1, y: 1 }));
    });
    expect(await watcher.client.next('gone')).toEqual({ t: 'gone', id: a.id });
    expect(await watcher.client.has(f => f.t === 'event' || f.t === 'person')).toBe(false);
    const c = await enter(layout, { name: 'C' });
    expect(c.welcome.people.map((p: any) => p.name).sort()).toEqual(['C', 'W']);
  });

  it('a failed link creation neither resurrects a side that left nor loses the other side\'s preparation', async () => {
    const layout = layoutId();
    const watcher = await enter(layout, { name: 'W' });
    const host = await enter(layout, { name: 'H' });
    const guest = await enter(layout, { name: 'G' });
    await guest.client.request({ t: 'prepare' });
    const { preparation } = await guest.client.next('preparation');
    await host.client.request({ t: 'queue' });
    await inObject(innStub(layout), (instance: any) => {
      const real = instance.env;
      Object.defineProperty(instance, 'env', { configurable: true, value: { ...real, LINK: {
        idFromName: (name: string) => real.LINK.idFromName(name),
        get: () => ({ init: async () => { await new Promise(resolve => setTimeout(resolve, 300)); throw new Error('boom'); } }),
      } } });
    });
    watcher.client.frames.length = 0;
    guest.client.send({ t: 'join', targetId: host.id, ref: 9 });
    await sleep(100);
    host.client.close();
    expect(await guest.client.next(f => f.ref === 9)).toMatchObject({ code: 'partner_unavailable' });
    expect(await guest.client.next('error')).toMatchObject({ code: 'match_failed' });
    expect(guest.client.frames.filter(f => f.t === 'preparation').at(-1)?.preparation).toEqual(preparation);
    await sleep(100);
    // The watcher saw the host leave and never come back.
    const hostFrames = watcher.client.frames.filter(f => (f.t === 'person' && f.person.id === host.id) || (f.t === 'gone' && f.id === host.id));
    expect(hostFrames.at(-1)).toEqual({ t: 'gone', id: host.id });
    const c = await enter(layout, { name: 'C' });
    expect(c.welcome.people.map((p: any) => p.name).sort()).toEqual(['C', 'G', 'W']);
  });

  it('accepts frames pipelined right after hello', async () => {
    const client = await open(`/api/v5/inn?layout=${layoutId()}`);
    client.send({ t: 'hello', v: 1, key: newKey(), name: 'p', appearance, dungeonProtocol: 12, x: 1, y: 1 });
    client.send({ t: 'chat', text: 'pipelined', ref: 1 });
    await client.next('welcome');
    expect(await client.next(f => f.ref === 1)).toMatchObject({ t: 'ok' });
    expect((await client.next('event')).event.text).toBe('pipelined');
  });

});

describe('item 6: link loss without close events', () => {
  it('a reset link notices the side that never came back', async () => {
    const { match, host, h, g } = await linked();
    await t.evictDurableObject(linkStub(match.id), { webSockets: 'close' });
    await h.client.waitClosed().catch(() => {});
    await g.client.waitClosed().catch(() => {});
    // Only the host returns.
    const back = await openLink(match.id, host.key);
    expect(back.link).toMatchObject({ paired: true, partnerConnected: false });
    const state = await inObject(linkStub(match.id), (instance: any) => ({ ...instance.state.lostAt })) as Record<string, number>;
    expect(state.guest).toBeGreaterThan(0);
    await inObject(linkStub(match.id), (instance: any) => { instance.state.lostAt.guest -= 60_000; });
    expect(await t.runDurableObjectAlarm(linkStub(match.id))).toBe(true);
    expect(await back.client.next('ended')).toEqual({ t: 'ended', reason: 'partner_lost' });
  });

  it('drops a silent partner socket and starts the reconnect grace', async () => {
    const { match, h, g } = await linked();
    await inObject(linkStub(match.id), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const attachment = ws.deserializeAttachment();
        if (attachment.role !== 'guest') continue;
        attachment.openedAt -= 200_000;
        attachment.seenAt = attachment.openedAt;
        ws.serializeAttachment(attachment);
        instance.conns.delete(ws);
      }
    });
    h.client.send({ t: 'data', d: 'anyone there?' });
    expect((await g.client.waitClosed()).code).toBe(1001);
    expect(await h.client.next('peer')).toEqual({ t: 'peer', connected: false });
  });

  it('does not let unauthenticated sockets lock a player out of its link', async () => {
    const { match, guest, g } = await linked();
    const squatters = [];
    for (let i = 0; i < 6; i++) squatters.push(await open(`/api/v5/link?id=${match.id}`));
    g.client.close(4999);
    await sleep(50);
    const back = await openLink(match.id, guest.key);
    expect(back.link.role).toBe('guest');
  });

  it('accepts frames pipelined right after the link hello', async () => {
    const { match, host, guest } = await matched();
    const g = await openLink(match.id, guest.key);
    const h = await open(`/api/v5/link?id=${match.id}`);
    h.send({ t: 'hello', v: 1, key: host.key });
    h.send({ t: 'ready' });
    h.send({ t: 'data', d: 'early' });
    await h.next('link');
    expect(await g.client.next('peer')).toEqual({ t: 'peer', ready: true });
    expect((await g.client.next('data')).d).toBe('early');
  });
});

describe('item 9: payload size', () => {
  it('relays a 24 KiB payload whatever its JSON escaping costs', async () => {
    const { h, g } = await linked();
    const quotes = '"'.repeat(12 * 1024) + '\\'.repeat(12 * 1024);
    const controls = '\u0001'.repeat(24 * 1024);
    for (const payload of [quotes, controls, JSON.stringify({ s: Array.from({ length: 2400 }, () => ({ a: 'b' })) }).slice(0, 24 * 1024)]) {
      expect(new TextEncoder().encode(payload).byteLength).toBeLessThanOrEqual(24 * 1024);
      h.client.send({ t: 'data', d: payload });
      expect((await g.client.next('data')).d).toBe(payload);
    }
    expect(h.client.closed).toBeNull();
  });

  it('answers empty or non-string data with payload_too_large', async () => {
    const { h } = await linked();
    h.client.send({ t: 'data', d: '' });
    expect(await h.client.next('error')).toMatchObject({ code: 'payload_too_large' });
    h.client.send({ t: 'data', d: 5 });
    expect(await h.client.next('error')).toMatchObject({ code: 'payload_too_large' });
  });
});

void exports;
