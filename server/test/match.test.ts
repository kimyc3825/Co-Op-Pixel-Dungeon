// SPDX-License-Identifier: GPL-3.0-or-later
import { env } from 'cloudflare:workers';
import { runDurableObjectAlarm } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { appearance, enter, inObject, layoutId, newKey, open, openLink, sleep } from './helpers';

async function pairUp(layout = layoutId(), hostOptions = {}, guestOptions = {}) {
  const host = await enter(layout, { name: 'Host', ...hostOptions });
  const guest = await enter(layout, { name: 'Guest', ...guestOptions });
  await host.client.request({ t: 'queue', friendlyFire: true, theme: 'prison' });
  expect(await guest.client.request({ t: 'join', targetId: host.id })).toMatchObject({ t: 'ok' });
  const hostMatch = (await host.client.next('matched')).match;
  const guestMatch = (await guest.client.next('matched')).match;
  return { layout, host, guest, hostMatch, guestMatch };
}

async function linked() {
  const pair = await pairUp();
  const hostLink = await openLink(pair.hostMatch.id, pair.host.key);
  const guestLink = await openLink(pair.guestMatch.id, pair.guest.key);
  await hostLink.client.next('paired');
  await guestLink.client.next('paired');
  return { ...pair, hostLink, guestLink };
}

describe('matchmaking', () => {
  it('shows a recruiting sign and pairs a player who clicks it', async () => {
    const layout = layoutId();
    const host = await enter(layout, { name: 'Host' });
    const guest = await enter(layout, { name: 'Guest' });
    const watcher = await enter(layout, { name: 'Watcher' });
    expect(await host.client.request({ t: 'queue', friendlyFire: true, theme: 'prison' })).toMatchObject({ t: 'ok' });
    const sign = await watcher.client.next(f => f.t === 'person' && f.person.id === host.id && f.person.queued);
    expect(sign.person.queued).toBe(true);

    expect(await guest.client.request({ t: 'join', targetId: host.id })).toMatchObject({ t: 'ok' });
    const hostMatch = (await host.client.next('matched')).match;
    const guestMatch = (await guest.client.next('matched')).match;
    expect(hostMatch).toEqual({ id: guestMatch.id, role: 'host', partnerId: guest.id, partnerName: 'Guest' });
    expect(guestMatch).toEqual({ id: hostMatch.id, role: 'guest', partnerId: host.id, partnerName: 'Host' });
    // The sign comes down for everyone.
    await watcher.client.next(f => f.t === 'person' && f.person.id === host.id && !f.person.queued);

    const hostLink = await openLink(hostMatch.id, host.key);
    expect(hostLink.link).toMatchObject({ role: 'host', selfId: host.id, partnerId: guest.id, selfName: 'Host',
      partnerName: 'Guest', friendlyFire: true, theme: 'prison', dungeonProtocol: 11, paired: false, partnerConnected: false,
      selfAppearance: appearance, partnerAppearance: appearance, selfPreparation: null, partnerPreparation: null });
    const guestLink = await openLink(guestMatch.id, guest.key);
    expect(guestLink.link).toMatchObject({ role: 'guest', paired: true, partnerConnected: true, seed: hostLink.link.seed });
    expect(Number.isInteger(hostLink.link.seed) && hostLink.link.seed >= 0 && hostLink.link.seed <= 0xFFFFFFFF).toBe(true);
    await hostLink.client.next('paired');
    await guestLink.client.next('paired');
  });

  it('pairs two players who both queue, the earlier one hosting', async () => {
    const layout = layoutId();
    const first = await enter(layout);
    const second = await enter(layout);
    await first.client.request({ t: 'queue' });
    expect(await first.client.has('matched')).toBe(false);
    await second.client.request({ t: 'queue' });
    expect((await first.client.next('matched')).match.role).toBe('host');
    expect((await second.client.next('matched')).match.role).toBe('guest');
  });

  it('does not match a cancelled or disconnected recruit', async () => {
    const layout = layoutId();
    const a = await enter(layout);
    const b = await enter(layout);
    await a.client.request({ t: 'queue' });
    await a.client.request({ t: 'cancel' });
    expect(await b.client.request({ t: 'join', targetId: a.id })).toMatchObject({ code: 'partner_unavailable' });
    await b.client.request({ t: 'queue' });
    expect(await b.client.has('matched')).toBe(false);
    b.client.close();
    await a.client.next('gone');
    await a.client.request({ t: 'queue' });
    expect(await a.client.has('matched')).toBe(false);
    const c = await enter(layout);
    expect(await c.client.request({ t: 'join', targetId: b.id })).toMatchObject({ code: 'partner_unavailable' });
  });

  it('only matches players with the same dungeon protocol', async () => {
    const layout = layoutId();
    const old = await enter(layout, { dungeonProtocol: 9 });
    const fresh = await enter(layout, { dungeonProtocol: 11 });
    await old.client.request({ t: 'queue' });
    await fresh.client.request({ t: 'queue' });
    expect(await old.client.has('matched')).toBe(false);
    expect(await fresh.client.has('matched')).toBe(false);
    const another = await enter(layout, { dungeonProtocol: 11 });
    expect(await another.client.request({ t: 'join', targetId: old.id })).toMatchObject({ code: 'protocol_mismatch' });
    await another.client.request({ t: 'queue' });
    expect((await fresh.client.next('matched')).match.partnerId).toBe(another.id);
  });

  it('restores the queue on reconnect when the hello says so', async () => {
    const layout = layoutId();
    const a = await enter(layout, { queued: true });
    expect(a.welcome.queued).toBe(false); // the welcome precedes the queue update
    const b = await enter(layout);
    expect(b.welcome.people.find((p: any) => p.id === a.id).queued).toBe(true);
  });

  it('hands both sides their preparations and consumes them', async () => {
    const layout = layoutId();
    const host = await enter(layout);
    await host.client.request({ t: 'prepare' });
    const { preparation } = await host.client.next('preparation');
    const guest = await enter(layout);
    await host.client.request({ t: 'queue' });
    await guest.client.request({ t: 'join', targetId: host.id });
    expect((await host.client.next('preparation')).preparation).toBeNull();
    const { match } = await host.client.next('matched');
    const hostLink = await openLink(match.id, host.key);
    expect(hostLink.link.selfPreparation).toEqual(preparation);
    host.client.close();
    const back = await enter(layout, { key: host.key });
    expect(back.welcome.preparation).toBeNull();
  });
});

describe('party link', () => {
  it('relays data in order in both directions and forwards readiness', async () => {
    const { hostLink, guestLink } = await linked();
    guestLink.client.send({ t: 'ready' });
    expect(await hostLink.client.next('peer')).toEqual({ t: 'peer', ready: true });
    hostLink.client.send({ t: 'ready' });
    expect(await guestLink.client.next('peer')).toEqual({ t: 'peer', ready: true });

    for (let i = 0; i < 50; i++) {
      hostLink.client.send({ t: 'data', d: `S|${i}` });
      guestLink.client.send({ t: 'data', d: `C|${i}` });
    }
    const atGuest = [];
    const atHost = [];
    for (let i = 0; i < 50; i++) {
      atGuest.push((await guestLink.client.next('data')).d);
      atHost.push((await hostLink.client.next('data')).d);
    }
    expect(atGuest).toEqual(Array.from({ length: 50 }, (_, i) => `S|${i}`));
    expect(atHost).toEqual(Array.from({ length: 50 }, (_, i) => `C|${i}`));
  });

  it('accepts payloads up to 24 KiB and refuses larger ones without ending the run', async () => {
    const { hostLink, guestLink } = await linked();
    const max = 'x'.repeat(24 * 1024);
    hostLink.client.send({ t: 'data', d: max });
    expect((await guestLink.client.next('data')).d).toBe(max);
    hostLink.client.send({ t: 'data', d: max + 'y' });
    expect(await hostLink.client.next('error')).toMatchObject({ code: 'payload_too_large' });
    hostLink.client.send({ t: 'data', d: 'still alive' });
    expect((await guestLink.client.next('data')).d).toBe('still alive');
  });

  it('ends the run for both when one side leaves', async () => {
    const { hostLink, guestLink, hostMatch, host } = await linked();
    guestLink.client.send({ t: 'leave' });
    expect(await hostLink.client.next('ended')).toEqual({ t: 'ended', reason: 'partner_left' });
    expect((await hostLink.client.waitClosed()).code).toBe(4010);
    expect((await guestLink.client.waitClosed()).code).toBe(1000);
    // The link is gone: reconnecting is refused.
    const late = await open(`/api/v5/link?id=${hostMatch.id}`);
    late.send({ t: 'hello', v: 1, key: host.key });
    expect(await late.next('error')).toMatchObject({ code: 'link_not_found' });
    expect((await late.waitClosed()).code).toBe(4004);
  });

  it('buffers for a reconnecting side and ends the run if it does not come back', async () => {
    const { hostLink, guestLink, guestMatch, guest } = await linked();
    guestLink.client.close(4999);
    expect(await hostLink.client.next('peer')).toEqual({ t: 'peer', connected: false });
    hostLink.client.send({ t: 'data', d: 'one' });
    hostLink.client.send({ t: 'data', d: 'two' });
    await sleep(50);
    const back = await openLink(guestMatch.id, guest.key);
    expect(back.link).toMatchObject({ paired: true, partnerConnected: true, role: 'guest' });
    expect((await back.client.next('data')).d).toBe('one');
    expect((await back.client.next('data')).d).toBe('two');
    expect(await hostLink.client.next('peer')).toEqual({ t: 'peer', connected: true });

    back.client.close(4999);
    await hostLink.client.next('peer');
    const stub = env.LINK.get(env.LINK.idFromName(guestMatch.id)) as unknown as DurableObjectStub;
    await inObject(stub, (instance: any) => { instance.state.lostAt.guest -= 60_000; });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await hostLink.client.next('ended')).toEqual({ t: 'ended', reason: 'partner_lost' });
  });

  it('ends a link that never pairs', async () => {
    const { hostMatch, host } = await pairUp();
    const hostLink = await openLink(hostMatch.id, host.key);
    const stub = env.LINK.get(env.LINK.idFromName(hostMatch.id)) as unknown as DurableObjectStub;
    await inObject(stub, (instance: any) => { instance.config.createdAt -= 60_000; });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect(await hostLink.client.next('ended')).toEqual({ t: 'ended', reason: 'pair_timeout' });
  });

  it('refuses players who are not part of the match', async () => {
    const { hostMatch } = await pairUp();
    const stranger = await open(`/api/v5/link?id=${hostMatch.id}`);
    stranger.send({ t: 'hello', v: 1, key: newKey() });
    expect((await stranger.waitClosed()).code).toBe(4003);
    const unknown = await open(`/api/v5/link?id=${crypto.randomUUID()}`);
    expect((await unknown.waitClosed()).code).toBe(4004);
  });

  it('ends the run when a side floods the relay', async () => {
    const { hostLink, guestLink } = await linked();
    for (let i = 0; i < 400; i++) hostLink.client.send({ t: 'data', d: String(i) });
    expect((await hostLink.client.waitClosed()).code).toBe(4008);
    expect(await guestLink.client.next('ended')).toEqual({ t: 'ended', reason: 'rate_limited' });
  });

  it('replaces a duplicate connection for the same side', async () => {
    const { hostLink, hostMatch, host, guestLink } = await linked();
    const again = await openLink(hostMatch.id, host.key);
    expect(again.link.paired).toBe(true);
    expect((await hostLink.client.waitClosed()).code).toBe(4009);
    guestLink.client.send({ t: 'data', d: 'to new socket' });
    expect((await again.client.next('data')).d).toBe('to new socket');
  });

  it('keeps matched players out of new matches until they come back to the inn', async () => {
    const { layout, host, guest } = await pairUp();
    const third = await enter(layout);
    expect(await third.client.request({ t: 'join', targetId: host.id })).toMatchObject({ code: 'partner_unavailable' });
    await host.client.request({ t: 'away' });
    await guest.client.request({ t: 'away' });
    expect(await host.client.request({ t: 'enter', x: 2, y: 2 })).toMatchObject({ t: 'ok' });
    const welcome = await host.client.next('welcome');
    expect(welcome.match).toBeNull();
    await host.client.request({ t: 'queue' });
    expect(await third.client.request({ t: 'join', targetId: host.id })).toMatchObject({ t: 'ok' });
  });
});

