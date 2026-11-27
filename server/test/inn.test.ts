// SPDX-License-Identifier: GPL-3.0-or-later
import { env, exports } from 'cloudflare:workers';
import { runDurableObjectAlarm } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { appearance, enter, inObject, layoutId, newKey, open, sleep } from './helpers';

describe('worker', () => {
  it('answers the health check', async () => {
    const response = await exports.default.fetch('https://inn.test/api/v5/health');
    expect(await response.json()).toEqual({ ok: true, protocol: 1 });
  });

  it('requires a WebSocket upgrade on socket paths and 404s unknown paths', async () => {
    expect((await exports.default.fetch('https://inn.test/api/v5/inn?layout=' + layoutId())).status).toBe(426);
    expect((await exports.default.fetch('https://inn.test/api/v2/inn/session')).status).toBe(404);
  });

  it('checks the Origin of browser sockets but admits clients without one', async () => {
    const layout = layoutId();
    const foreign = await exports.default.fetch(`https://inn.test/api/v5/inn?layout=${layout}`,
      { headers: { Upgrade: 'websocket', Origin: 'https://evil.example' } });
    expect(foreign.status).toBe(403);
    const same = await open(`/api/v5/inn?layout=${layout}`, { Origin: 'https://inn.test' });
    const configured = await open(`/api/v5/inn?layout=${layout}`, { Origin: 'https://preview.example' });
    const native = await open(`/api/v5/inn?layout=${layout}`);
    for (const client of [same, configured, native]) {
      client.send({ t: 'hello', v: 1, key: newKey(), name: 'o', appearance, dungeonProtocol: 11, x: 1, y: 1 });
      await client.next('welcome');
    }
  });

  it('rejects a malformed layout id', async () => {
    const client = await open('/api/v5/inn?layout=nothex');
    expect((await client.next('error')).code).toBe('invalid_layout');
    expect((await client.waitClosed()).code).toBe(4000);
  });
});

describe('inn presence', () => {
  it('welcomes with the roster and fans out join, movement, chat and leave', async () => {
    const layout = layoutId();
    const a = await enter(layout, { name: '앨리스', x: 10, y: 14 });
    expect(a.welcome).toMatchObject({ v: 1, layoutId: layout, shard: 0, present: true, queued: false, match: null,
      preparation: null, limits: { name: 20, chat: 160 } });
    expect(a.welcome.people).toEqual([{ id: a.id, name: '앨리스', x: 10, y: 14, appearance, queued: false, dungeonProtocol: 11 }]);

    const b = await enter(layout, { name: 'Bob', x: 12, y: 14 });
    expect(b.welcome.people.map((p: any) => p.id).sort()).toEqual([a.id, b.id].sort());
    expect((await a.client.next('person')).person).toMatchObject({ id: b.id, name: 'Bob', x: 12, y: 14 });

    b.client.send({ t: 'move', x: 13, y: 13 });
    expect(await a.client.next('moved')).toEqual({ t: 'moved', id: b.id, x: 13, y: 13 });
    expect(await b.client.has('moved')).toBe(false);

    expect(await a.client.request({ t: 'chat', text: '  안녕하세요  ' })).toMatchObject({ t: 'ok' });
    for (const client of [a.client, b.client]) {
      const { event, now } = await client.next('event');
      expect(event).toMatchObject({ kind: 'chat', playerId: a.id, name: '앨리스', text: '안녕하세요' });
      expect(Number.isSafeInteger(event.id) && now >= event.time).toBe(true);
    }

    await b.client.request({ t: 'drink' });
    expect((await a.client.next('event')).event).toMatchObject({ kind: 'drink', playerId: b.id, name: 'Bob' });
    await b.client.request({ t: 'interact', npcId: 'innkeeper' });
    expect((await a.client.next('event')).event).toMatchObject({ kind: 'interact', playerId: b.id, npcId: 'innkeeper' });

    // A newcomer sees recent events.
    const c = await enter(layout, { name: 'Cleo' });
    expect(c.welcome.events.map((e: any) => e.kind)).toEqual(['chat', 'drink', 'interact']);
    expect(c.welcome.after).toBe(c.welcome.events[2].id);

    b.client.close();
    expect(await a.client.next('gone')).toEqual({ t: 'gone', id: b.id });
  });

  it('updates profiles and rejects invalid ones', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    const b = await enter(layout);
    await a.client.next('person');
    const renamed = { ...appearance, job: 'rogue' };
    expect(await b.client.request({ t: 'profile', name: 'Renamed', appearance: renamed })).toMatchObject({ t: 'ok' });
    expect((await a.client.next('person')).person).toMatchObject({ id: b.id, name: 'Renamed', appearance: renamed });
    expect(await b.client.request({ t: 'profile', name: 'x'.repeat(21) })).toMatchObject({ code: 'invalid_profile' });
    expect(await b.client.request({ t: 'profile', name: 'bad\u0007' })).toMatchObject({ code: 'invalid_profile' });
    expect(await b.client.request({ t: 'profile', name: 'ok', appearance: { job: 'Bad Id!' } })).toMatchObject({ code: 'invalid_profile' });
  });

  it('accepts only adjacent single steps and corrects the client otherwise', async () => {
    const a = await enter(layoutId(), { x: 5, y: 5 });
    a.client.send({ t: 'move', x: 7, y: 5 });
    expect(await a.client.next('error')).toMatchObject({ code: 'move_rejected' });
    expect(await a.client.next('you')).toEqual({ t: 'you', x: 5, y: 5 });
    a.client.send({ t: 'move', x: 128, y: 5 });
    expect(await a.client.next('you')).toEqual({ t: 'you', x: 5, y: 5 });
    expect(await a.client.request({ t: 'move', x: 6, y: 6 })).toMatchObject({ t: 'ok' });
  });

  it('keeps rooms with different layout ids apart', async () => {
    const a = await enter(layoutId());
    const b = await enter(layoutId());
    expect(b.welcome.people).toHaveLength(1);
    await a.client.request({ t: 'chat', text: 'hello' });
    await a.client.next('event');
    expect(await b.client.has('event')).toBe(false);
    expect(await b.client.has('person')).toBe(false);
  });

  it('hides away players and shows them again on enter', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    const b = await enter(layout);
    await a.client.next('person');
    await b.client.request({ t: 'away' });
    expect(await a.client.next('gone')).toEqual({ t: 'gone', id: b.id });
    expect(await b.client.request({ t: 'chat', text: 'hi' })).toMatchObject({ code: 'not_in_inn' });
    expect(await b.client.request({ t: 'enter', x: 3, y: 4 })).toMatchObject({ t: 'ok' });
    expect((await a.client.next('person')).person).toMatchObject({ id: b.id, x: 3, y: 4 });
    const welcome = await b.client.next('welcome');
    expect(welcome.present).toBe(true);
  });

  it('rolls one preparation, keeps it across a reconnect and consumes it on use', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    expect(await a.client.request({ t: 'prepare' })).toMatchObject({ t: 'ok' });
    const { preparation } = await a.client.next('preparation');
    expect([-2, 0, 2]).toContain(preparation.hp);
    expect([-1, 0, 1]).toContain(preparation.food);
    await a.client.request({ t: 'prepare' });
    expect((await a.client.next('preparation')).preparation).toEqual(preparation);

    a.client.close();
    await sleep(50);
    const again = await enter(layout, { key: a.key });
    expect(again.id).toBe(a.id);
    expect(again.welcome.preparation).toEqual(preparation);
    await again.client.request({ t: 'use_preparation', id: preparation.id });
    expect((await again.client.next('preparation')).preparation).toBeNull();
  });
});

describe('inn limits', () => {
  it('validates chat like the clients do', async () => {
    const a = await enter(layoutId());
    expect(await a.client.request({ t: 'chat', text: 'x'.repeat(161) })).toMatchObject({ code: 'invalid_message' });
    expect(await a.client.request({ t: 'chat', text: 'two\nlines' })).toMatchObject({ code: 'invalid_message' });
    expect(await a.client.request({ t: 'chat', text: '   ' })).toMatchObject({ code: 'invalid_message' });
    expect(await a.client.request({ t: 'chat', text: '가'.repeat(160) })).toMatchObject({ t: 'ok' });
  });

  it('rate limits chat per socket', async () => {
    const a = await enter(layoutId());
    const results = [];
    for (let i = 0; i < 6; i++) results.push((await a.client.request({ t: 'chat', text: `m${i}` })).t);
    expect(results.slice(0, 4)).toEqual(['ok', 'ok', 'ok', 'ok']);
    expect(results.slice(4)).toEqual(['error', 'error']);
  });

  it('closes a flooding socket with 4008', async () => {
    const a = await enter(layoutId());
    for (let i = 0; i < 60; i++) a.client.send({ t: 'cancel' });
    expect((await a.client.waitClosed()).code).toBe(4008);
  });

  it('rejects oversized frames, binary frames and non-hello first frames', async () => {
    const layout = layoutId();
    const big = await enter(layout);
    big.client.send({ t: 'chat', text: 'x'.repeat(5000) });
    expect((await big.client.waitClosed()).code).toBe(1009);
    const binary = await open(`/api/v5/inn?layout=${layout}`);
    binary.ws.send(new Uint8Array([1, 2, 3]));
    expect((await binary.waitClosed()).code).toBe(1003);
    const rude = await open(`/api/v5/inn?layout=${layout}`);
    rude.send({ t: 'chat', text: 'hi' });
    expect((await rude.waitClosed()).code).toBe(4000);
    const garbage = await open(`/api/v5/inn?layout=${layout}`);
    garbage.send('not json');
    expect((await garbage.waitClosed()).code).toBe(4000);
  });

  it('refuses an unsupported wire version with 4001', async () => {
    const client = await open(`/api/v5/inn?layout=${layoutId()}`);
    client.send({ t: 'hello', v: 2, key: newKey(), name: 'x', appearance, dungeonProtocol: 11, x: 1, y: 1 });
    expect(await client.next('error')).toMatchObject({ code: 'unsupported_version', supported: [1] });
    expect((await client.waitClosed()).code).toBe(4001);
  });

  it('rejects an invalid hello', async () => {
    const layout = layoutId();
    for (const bad of [{ key: 'short' }, { name: '' }, { x: -1 }, { dungeonProtocol: 'eleven' }, { appearance: 'warrior' }]) {
      const client = await open(`/api/v5/inn?layout=${layout}`);
      client.send({ t: 'hello', v: 1, key: newKey(), name: 'x', appearance, dungeonProtocol: 11, x: 1, y: 1, ...bad });
      expect((await client.waitClosed()).code).toBe(4000);
    }
  });

  it('caps a shard and overflows into the next one', async () => {
    const layout = layoutId();
    const first = await enter(layout);
    const clients = [first.client];
    for (let i = 1; i < 32; i++) clients.push((await open(`/api/v5/inn?layout=${layout}`)));
    const overflow = await enter(layout);
    expect(overflow.welcome.shard).toBe(1);
    expect(overflow.welcome.people).toHaveLength(1);
    // The shard hint brings a reconnecting player back to its shard when there is room.
    const hinted = await enter(layout, {}, '&shard=1');
    expect(hinted.welcome.shard).toBe(1);
    for (const client of clients) client.close();
  });

  it('times out sockets that never say hello and drops stale ones', async () => {
    const layout = layoutId();
    const silent = await open(`/api/v5/inn?layout=${layout}`);
    const a = await enter(layout);
    const stub = env.INN.get(env.INN.idFromName(`${layout}:0`)) as unknown as DurableObjectStub;
    await inObject(stub, async (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const member = ws.deserializeAttachment();
        member.openedAt -= 200_000;
        if (member.seenAt) member.seenAt -= 200_000;
        ws.serializeAttachment(member);
        instance.conns.delete(ws);
      }
    });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect((await silent.waitClosed()).code).toBe(4002);
    expect((await a.client.waitClosed()).code).toBe(1001);
  });

  it('replaces the older socket when the same key reconnects', async () => {
    const layout = layoutId();
    const watcher = await enter(layout);
    const a = await enter(layout, { x: 3, y: 3 });
    await watcher.client.next('person');
    const again = await enter(layout, { key: a.key, x: 4, y: 3 });
    expect(again.id).toBe(a.id);
    expect(await a.client.next('error')).toMatchObject({ code: 'replaced' });
    expect((await a.client.waitClosed()).code).toBe(4009);
    expect((await watcher.client.next('person')).person).toMatchObject({ id: a.id, x: 4, y: 3 });
    expect(await watcher.client.has('gone')).toBe(false);
  });
});
