// SPDX-License-Identifier: GPL-3.0-or-later
// Regressions for the second review round (N1–N8).
import { env, exports } from 'cloudflare:workers';
import * as testing from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { appearance, enter, inObject, layoutId, newKey, open, openLink, sleep } from './helpers';
import { addressKey } from '../src/util';

const t = testing as Record<string, any>;
const innStub = (layout: string, shard = 0) => env.INN.get(env.INN.idFromName(`${layout}:${shard}`)) as unknown as DurableObjectStub;
const linkStub = (id: string) => env.LINK.get(env.LINK.idFromName(id)) as unknown as DurableObjectStub;
const hello = () => ({ t: 'hello', v: 1, key: newKey(), name: 'x', appearance, dungeonProtocol: 12, x: 1, y: 1 });

async function linked() {
  const layout = layoutId();
  const host = await enter(layout, { name: 'H' });
  const guest = await enter(layout, { name: 'G' });
  await host.client.request({ t: 'queue' });
  await guest.client.request({ t: 'join', targetId: host.id });
  const { match } = await host.client.next('matched');
  const h = await openLink(match.id, host.key);
  const g = await openLink(match.id, guest.key);
  await h.client.next('paired');
  await g.client.next('paired');
  return { layout, host, guest, match, h, g };
}

describe('N1: connects per address', () => {
  it('churn from one address only locks out that address', async () => {
    const layout = layoutId();
    let refused = 0;
    for (let i = 0; i < 30; i++) {
      const socket = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': '198.51.100.1' });
      await sleep(5);
      if (socket.frames.some(f => f.code === 'rate_limited')) refused++;
      socket.close();
    }
    expect(refused).toBeGreaterThan(0);
    for (let i = 0; i < 5; i++) {
      const victim = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': `192.0.2.${50 + i}` });
      victim.send(hello());
      expect((await victim.next('welcome')).shard).toBe(0);
    }
  });
});

describe('N2: sweep then frame', () => {
  it('a socket closed by the sweep is not processed any further', async () => {
    const layout = layoutId();
    const watcher = await enter(layout, { name: 'W' });
    const lurker = await open(`/api/v5/inn?layout=${layout}`);
    await inObject(innStub(layout), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const member = ws.deserializeAttachment();
        if (member.phase !== 'new') continue;
        member.openedAt -= 20_000;
        ws.serializeAttachment(member);
        instance.conns.delete(ws);
      }
      instance.lastSweep = 0;
    });
    watcher.client.frames.length = 0;
    lurker.send({ t: 'enter', x: 3, y: 3 });
    expect((await lurker.waitClosed()).code).toBe(4002);
    expect(await watcher.client.has(f => f.t === 'person' || f.t === 'event')).toBe(false);
    const names = await inObject(innStub(layout), (_i: any, state: DurableObjectState) =>
      state.getWebSockets().map(ws => ws.deserializeAttachment()).filter((m: any) => m.phase !== 'closed').map((m: any) => m.name));
    expect(names).toEqual(['W']);
  });
});

describe('N3: relay burst', () => {
  it('a burst of 300 frames passes; a sustained flood still ends the run', async () => {
    const { h, g } = await linked();
    for (let i = 0; i < 280; i++) h.client.send({ t: 'data', d: `S|${i}` });
    for (let i = 0; i < 280; i++) expect((await g.client.next('data')).d).toBe(`S|${i}`);
    expect(h.client.closed).toBeNull();
    for (let i = 0; i < 400; i++) h.client.send({ t: 'data', d: 'flood' });
    expect((await h.client.waitClosed()).code).toBe(4008);
    expect(await g.client.next('ended')).toEqual({ t: 'ended', reason: 'rate_limited' });
  });
});

describe('N4: room budgets', () => {
  it('emotes cannot starve chat, one address cannot use up the room, and room_busy costs no own token', async () => {
    const layout = layoutId();
    const players = [];
    for (let i = 0; i < 10; i++) players.push(await enter(layout, {}, '', { 'CF-Connecting-IP': `192.0.2.${i + 1}` }));
    for (const player of players) for (let i = 0; i < 4; i++) {
      expect(await player.client.request({ t: 'drink' })).toMatchObject({ t: 'ok' }); // silent when over budget
    }
    const results: string[] = [];
    for (const player of players) for (let i = 0; i < 4; i++) results.push((await player.client.request({ t: 'chat', text: `m${i}` })).code ?? 'ok');
    expect(results.filter(r => r === 'ok').length).toBe(40);

    const shared = layoutId();
    const same = [];
    for (let i = 0; i < 3; i++) same.push(await enter(shared, {}, '', { 'CF-Connecting-IP': '192.0.2.99' }));
    const lines: string[] = [];
    for (const player of same) for (let i = 0; i < 3; i++) lines.push((await player.client.request({ t: 'chat', text: `s${i}` })).code ?? 'ok');
    expect(lines.filter(r => r === 'ok')).toHaveLength(8);
    expect(lines.at(-1)).toBe('room_busy');
    // The third player was refused for the room, not for itself: its own bucket is still full.
    await sleep(1100);
    expect(await same[2].client.request({ t: 'chat', text: 'later' })).toMatchObject({ t: 'ok' });
  });
});

describe('N5: per-address cap', () => {
  it('overflows to the next shard instead of refusing', async () => {
    const layout = layoutId();
    const headers = { 'CF-Connecting-IP': '203.0.113.7' };
    for (let i = 0; i < 8; i++) await open(`/api/v5/inn?layout=${layout}`, headers);
    const ninth = await open(`/api/v5/inn?layout=${layout}`, headers);
    ninth.send(hello());
    expect((await ninth.next('welcome')).shard).toBe(1);
    const other = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': '203.0.113.8' });
    other.send(hello());
    expect((await other.next('welcome')).shard).toBe(0);
  });

  it('keys IPv6 by /64 and ignores loopback addresses and loopback hosts', async () => {
    expect(addressKey('2001:db8:1:2:aaaa::1')).toBe('2001:db8:1:2::/64');
    expect(addressKey('2001:db8:1:2:bbbb:cccc:dddd:eeee')).toBe('2001:db8:1:2::/64');
    expect(addressKey('2001:DB8::5')).toBe('2001:db8:0:0::/64');
    expect(addressKey('::ffff:198.51.100.4')).toBe('198.51.100.4');
    expect(addressKey('198.51.100.4')).toBe('198.51.100.4');
    for (const ignored of ['127.0.0.1', '127.1.2.3', '::1', '', 'nonsense', '1:2:3']) expect(addressKey(ignored)).toBe('');

    const layout = layoutId();
    for (let i = 0; i < 8; i++) await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': `2001:db8:7:7::${i + 1}` });
    const ninth = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': '2001:db8:7:7:ffff::9' });
    ninth.send(hello());
    expect((await ninth.next('welcome')).shard).toBe(1);

    // A loopback host (wrangler dev) ignores a client-sent header: twelve sockets, no cap.
    const local = layoutId();
    for (let i = 0; i < 12; i++) {
      const response = await exports.default.fetch(`http://127.0.0.1:8787/api/v5/inn?layout=${local}`,
        { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': '203.0.113.99' } });
      const ws = response.webSocket!;
      const welcome = new Promise<any>(resolve => ws.addEventListener('message', event => resolve(JSON.parse(event.data as string))));
      ws.accept();
      ws.send(JSON.stringify(hello()));
      expect(await welcome).toMatchObject({ t: 'welcome', shard: 0 });
    }
  });
});

describe('M1: closed sockets count toward the per-address cap', () => {
  it('one address cannot fill a shard with sockets it never lets close', async () => {
    const layout = layoutId();
    const zombie = { 'CF-Connecting-IP': '198.51.100.66' };
    const kept = [];
    for (let i = 0; i < 8; i++) kept.push(await open(`/api/v5/inn?layout=${layout}`, zombie));
    // Refused by the server, close never acknowledged: still in getWebSockets(), no longer live.
    await inObject(innStub(layout), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const conn = instance.conn(ws);
        conn.member.phase = 'closed';
        ws.serializeAttachment(conn.member);
      }
    });
    const next = await open(`/api/v5/inn?layout=${layout}`, zombie);
    next.send(hello());
    expect((await next.next('welcome')).shard).toBe(1);
    const shard0 = await inObject(innStub(layout), (_i: any, state: DurableObjectState) => state.getWebSockets().length);
    expect(shard0).toBe(8);
    const other = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': '192.0.2.12' });
    other.send(hello());
    expect((await other.next('welcome')).shard).toBe(0);
    expect(kept).toHaveLength(8);
  });
});

describe('N6: hard socket ceiling', () => {
  it('closed sockets whose close was never acknowledged count toward a ceiling that overflows', async () => {
    const layout = layoutId();
    const kept = [];
    for (let i = 0; i < 32; i++) kept.push(await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': `198.51.100.${i}` }));
    // Mark them closed without completing the close, as an unresponsive client leaves them.
    await inObject(innStub(layout), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const conn = instance.conn(ws);
        conn.member.phase = 'closed';
        ws.serializeAttachment(conn.member);
      }
    });
    for (let i = 0; i < 32; i++) kept.push(await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': `198.51.101.${i}` }));
    expect(await inObject(innStub(layout), (_i: any, state: DurableObjectState) => state.getWebSockets().length)).toBe(64);
    const next = await open(`/api/v5/inn?layout=${layout}`, { 'CF-Connecting-IP': '192.0.2.200' });
    next.send(hello());
    expect((await next.next('welcome')).shard).toBe(1);
    expect(kept).toHaveLength(64);
  });

  it('a link refuses sockets beyond its total ceiling', async () => {
    const { match } = await linked();
    const extra = [];
    const markClosed = () => inObject(linkStub(match.id), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const conn = instance.conn(ws);
        if (conn.attachment.phase === 'new') { conn.attachment.phase = 'closed'; ws.serializeAttachment(conn.attachment); }
      }
    });
    // Ten unacknowledged closed sockets next to the two players.
    for (const batch of [4, 4, 2]) {
      for (let i = 0; i < batch; i++) extra.push(await open(`/api/v5/link?id=${match.id}`));
      await markClosed();
    }
    expect(await inObject(linkStub(match.id), (_i: any, state: DurableObjectState) => state.getWebSockets().length)).toBe(12);
    const late = await open(`/api/v5/link?id=${match.id}`);
    expect(await late.next('error')).toMatchObject({ code: 'link_full' });
  });
});

describe('N7: silent loss while the other side only watches', () => {
  it('a paired link always has a check alarm and finds a silently dead side', async () => {
    const { match, h, g } = await linked();
    const alarm = await inObject(linkStub(match.id), (_i: any, state: DurableObjectState) => state.storage.getAlarm()) as number;
    expect(alarm - Date.now()).toBeLessThanOrEqual(120_000);
    // The host's connection died without a close; the guest sends nothing.
    await inObject(linkStub(match.id), (instance: any, state: DurableObjectState) => {
      for (const ws of state.getWebSockets()) {
        const attachment = ws.deserializeAttachment();
        if (attachment.role !== 'host') continue;
        attachment.openedAt -= 200_000;
        attachment.seenAt = attachment.openedAt;
        ws.serializeAttachment(attachment);
        instance.conns.delete(ws);
      }
    });
    expect(await t.runDurableObjectAlarm(linkStub(match.id))).toBe(true);
    expect((await h.client.waitClosed()).code).toBe(1001);
    expect(await g.client.next('peer')).toEqual({ t: 'peer', connected: false });
    await inObject(linkStub(match.id), (instance: any) => { instance.state.lostAt.host -= 60_000; });
    expect(await t.runDurableObjectAlarm(linkStub(match.id))).toBe(true);
    expect(await g.client.next('ended')).toEqual({ t: 'ended', reason: 'partner_lost' });
  });
});

describe('N8: away is never refused', () => {
  it('after many lobby requests away still takes the player out of the room', async () => {
    const layout = layoutId();
    const watcher = await enter(layout);
    const a = await enter(layout);
    for (let i = 0; i < 8; i++) await a.client.request({ t: i % 2 ? 'cancel' : 'queue' });
    expect(await a.client.request({ t: 'queue' })).toMatchObject({ code: 'activity_rate_limited' });
    expect(await a.client.request({ t: 'away' })).toMatchObject({ t: 'ok' });
    expect(await watcher.client.next(f => f.t === 'gone')).toEqual({ t: 'gone', id: a.id });
  });
});

describe('N9: preparation fishing', () => {
  it('a used preparation cannot be re-rolled until the next entry', async () => {
    const a = await enter(layoutId());
    await a.client.request({ t: 'prepare' });
    const { preparation } = await a.client.next('preparation');
    await a.client.request({ t: 'use_preparation', id: preparation.id });
    await a.client.next('preparation');
    expect(await a.client.request({ t: 'prepare' })).toMatchObject({ code: 'preparation_used' });
    await a.client.request({ t: 'enter', x: 2, y: 2 });
    expect(await a.client.request({ t: 'prepare' })).toMatchObject({ t: 'ok' });
  });
});
