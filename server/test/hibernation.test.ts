// SPDX-License-Identifier: GPL-3.0-or-later
import { env } from 'cloudflare:workers';
import * as testing from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { enter, layoutId, openLink } from './helpers';
import { innName } from '../src/placement';

// Evicting tears the instance down and hibernates its sockets, as an idle object would be.
const evict = (stub: unknown) => (testing as Record<string, any>).evictDurableObject(stub) as Promise<void>;

describe('hibernation', () => {
  it('keeps the inn roster, positions and queue across eviction', async () => {
    const layout = layoutId();
    const a = await enter(layout, { name: 'A', x: 5, y: 5 });
    const b = await enter(layout, { name: 'B', x: 8, y: 8 });
    await a.client.next('person');
    await b.client.request({ t: 'queue' });
    await a.client.next(f => f.t === 'person' && f.person.queued);
    await evict(env.INN.get(env.INN.idFromName(innName(layout, 0))));

    expect(await a.client.request({ t: 'move', x: 6, y: 5 })).toMatchObject({ t: 'ok' });
    expect(await b.client.next('moved')).toEqual({ t: 'moved', id: a.id, x: 6, y: 5 });
    await a.client.request({ t: 'chat', text: 'after eviction' });
    expect((await b.client.next('event')).event.text).toBe('after eviction');
    const c = await enter(layout, { name: 'C' });
    const people = Object.fromEntries(c.welcome.people.map((p: any) => [p.name, p]));
    expect(people.A).toMatchObject({ x: 6, y: 5, queued: false });
    expect(people.B).toMatchObject({ x: 8, y: 8, queued: true });
    expect(c.welcome.events.at(-1).text).toBe('after eviction');
    expect(await c.client.request({ t: 'join', targetId: b.id })).toMatchObject({ t: 'ok' });
  });

  it('keeps a paired link relaying and remembers readiness across eviction', async () => {
    const layout = layoutId();
    const host = await enter(layout);
    const guest = await enter(layout);
    await host.client.request({ t: 'queue' });
    await guest.client.request({ t: 'join', targetId: host.id });
    const { match } = await host.client.next('matched');
    const h = await openLink(match.id, host.key);
    const g = await openLink(match.id, guest.key);
    await h.client.next('paired');
    g.client.send({ t: 'ready' });
    await h.client.next('peer');
    await evict(env.LINK.get(env.LINK.idFromName(match.id)));

    g.client.send({ t: 'data', d: 'C|1' });
    expect((await h.client.next('data')).d).toBe('C|1');
    h.client.send({ t: 'data', d: 'S|1' });
    expect((await g.client.next('data')).d).toBe('S|1');
    // A reconnecting host learns that the guest is ready from the stored state.
    const again = await openLink(match.id, host.key);
    expect(again.link).toMatchObject({ paired: true, partnerReady: true, partnerConnected: true });
  });
});
