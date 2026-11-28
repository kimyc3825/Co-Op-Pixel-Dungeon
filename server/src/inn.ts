// SPDX-License-Identifier: GPL-3.0-or-later
// One inn room shard: presence, movement, chat, NPC notices, expedition preparation and matchmaking.
//
// Storage cost is zero rows: every socket is hibernatable and all per-player state (position,
// queue, match, preparation) lives in its attachment; the recent-event history for newcomers is
// kept in memory only and is lost when the object hibernates. Nothing is written to SQLite and no
// alarm is set, so a room that empties leaves nothing behind. Hello timeouts and stale sockets are
// swept lazily whenever someone connects or sends a frame.
import { DurableObject } from 'cloudflare:workers';
import type { Env, LinkConfig } from './env';
import {
  CLOSE, LIMITS, KEY, NPC_ID, PREPARATION_ID, WIRE_VERSION, cleanAppearance, cleanBool, cleanText, cleanTheme,
  isCoord, isDungeonProtocol, isRef, parseFrame,
  type Appearance, type Json, type Preparation, type Theme,
} from './protocol';
import { linkStub } from './placement';
import { Bucket, BucketMap, acknowledgeClose, close, digest, randomIndex, randomUint32, refuse, send } from './util';

/** `closed`: the server closed or replaced the socket; every path ignores it from then on. */
type Phase = 'new' | 'present' | 'away' | 'matched' | 'closed';

interface MatchInfo { id: string; role: 'host' | 'guest'; partnerId: string; partnerName: string }

interface Member {
  conn: string;
  phase: Phase;
  openedAt: number;
  shard: number;
  layout: string;
  /**
   * Client address key (IPv4, or IPv6 /64) for the per-address limits. Kept in the attachment, so it
   * survives hibernation for the socket's lifetime; never written to storage.
   */
  address: string;
  id?: string;
  name?: string;
  appearance?: Appearance;
  protocol?: number;
  x?: number;
  y?: number;
  /** Time the player started recruiting, 0 when not queued. Earlier recruits host. */
  queuedAt?: number;
  friendlyFire?: boolean;
  theme?: Theme;
  match?: MatchInfo | null;
  preparation?: Preparation | null;
  /** Set by `use_preparation`; a new roll needs a fresh `enter` (one roll per entry). */
  preparationUsed?: boolean;
  /** Last frame time, saved at most every 30 s so stale sockets are found after hibernation. */
  seenAt?: number;
}

interface Person {
  id: string; name: string; x: number; y: number; appearance: Appearance; queued: boolean; dungeonProtocol: number;
}

interface InnEvent {
  id: number; kind: 'chat' | 'drink' | 'interact'; playerId: string; name: string; time: number;
  text?: string; npcId?: string;
}

class Conn {
  readonly all = new Bucket(30, 12);
  readonly move = new Bucket(6, 8);
  readonly chat = new Bucket(4, 1 / 3);
  readonly activity = new Bucket(6, 0.5);
  readonly lobby = new Bucket(8, 1);
  constructor(public member: Member) {}
}

const visible = (member: Member) => member.phase === 'present' || member.phase === 'matched';
const SWEEP_EVERY_MS = 5_000;

export class InnRoom extends DurableObject<Env> {
  private readonly conns = new Map<WebSocket, Conn>();
  /** Recent room events for newcomers' welcome. Memory only. */
  private events: InnEvent[] = [];
  private lastEventId = 0;
  private lastSweep = 0;
  // Room-wide budgets for broadcast events. Chat has its own so emotes cannot starve it, and a
  // per-address chat budget keeps one machine from using up the room's.
  private readonly roomChat = new Bucket(40, 4);
  private readonly addressChat = new BucketMap(8, 1);
  private readonly roomEmotes = new Bucket(30, 2);
  /** New connections per client address; churn from one address only locks out that address. */
  private readonly addressJoins = new BucketMap(10, 0.5);

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Keep-alive pings are answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return new Response('Expected WebSocket', { status: 426 });
    const now = Date.now();
    this.sweep(now, true);
    const live = this.live();
    // Capacity refusals answer 409 and the Worker tries the next shard. The total includes closed
    // sockets whose close was never acknowledged, so they cannot pile up without bound.
    if (live.length >= LIMITS.socketsPerShard || this.ctx.getWebSockets().length >= LIMITS.socketsPerShardTotal) {
      return new Response('inn_full', { status: 409 });
    }
    const address = request.headers.get('X-Client-Address') ?? '';
    // Every socket of the address counts, closed ones whose close was never acknowledged included:
    // otherwise one address could fill the 64-socket ceiling on its own.
    const held = this.ctx.getWebSockets().filter(ws => this.conn(ws).member.address === address).length;
    if (address && held >= LIMITS.socketsPerAddress) {
      return new Response('too_many_connections', { status: 409, headers: { 'X-Refusal': 'address' } });
    }
    // Only about this address, so no other shard is tried.
    if (address && !this.addressJoins.get(address).take(1, now)) return new Response('rate_limited', { status: 429 });
    const [client, server] = Object.values(new WebSocketPair());
    const member: Member = {
      conn: crypto.randomUUID(), phase: 'new', openedAt: now,
      shard: Number(request.headers.get('X-Inn-Shard')) || 0, layout: request.headers.get('X-Inn-Layout') ?? '', address,
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(member);
    this.conns.set(server, new Conn(member));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const conn = this.conn(ws);
    if (conn.member.phase === 'closed') return;
    const now = Date.now();
    if (now - (conn.member.seenAt ?? 0) >= 30_000) {
      conn.member.seenAt = now;
      this.save(ws, conn);
    }
    this.sweep(now);
    // The sweep may just have closed this very socket (hello timeout, stale).
    if ((conn.member.phase as Phase) === "closed") return;
    if (!conn.all.take(1, now)) return this.reject(ws, conn, CLOSE.rateLimited, 'rate_limited');
    const parsed = parseFrame(message, LIMITS.innFrameBytes);
    if ('close' in parsed) return this.reject(ws, conn, parsed.close, parsed.reason);
    const frame = parsed.frame;
    const ref = isRef(frame.ref) ? frame.ref : undefined;
    if (conn.member.phase === 'new') {
      if (frame.t !== 'hello') return this.reject(ws, conn, CLOSE.badMessage, 'hello_required');
      return this.hello(ws, conn, frame);
    }
    switch (frame.t) {
      case 'move': return this.move(ws, conn, frame, ref);
      case 'chat': return this.chat(ws, conn, frame, ref);
      case 'drink': return this.drink(ws, conn, ref);
      case 'interact': return this.interact(ws, conn, frame, ref);
      case 'profile': return this.profile(ws, conn, frame, ref);
      case 'prepare': return this.prepare(ws, conn, ref);
      case 'use_preparation': return this.usePreparation(ws, conn, frame, ref);
      case 'queue': return this.queue(ws, conn, frame, ref);
      case 'join': return this.joinTarget(ws, conn, frame, ref);
      case 'cancel': return this.cancel(ws, conn, ref);
      case 'away': return this.away(ws, conn, ref);
      case 'enter': return this.enter(ws, conn, frame, ref);
      default: return this.fail(ws, ref, 'unknown_type');
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    acknowledgeClose(ws, code, reason);
    this.retire(ws, this.conn(ws));
    this.conns.delete(ws);
    await this.cleanUp();
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.retire(ws, this.conn(ws));
    this.conns.delete(ws);
    await this.cleanUp();
  }

  // ---- handlers ----

  private async hello(ws: WebSocket, conn: Conn, frame: Json): Promise<void> {
    if (frame.v !== WIRE_VERSION) {
      send(ws, { t: 'error', code: 'unsupported_version', supported: [WIRE_VERSION] });
      close(ws, CLOSE.unsupportedVersion, 'unsupported_version');
      this.retire(ws, conn);
      return;
    }
    const name = cleanText(frame.name, LIMITS.nameChars);
    const appearance = cleanAppearance(frame.appearance ?? {});
    const present = cleanBool(frame.present, true);
    const queued = cleanBool(frame.queued, false);
    const friendlyFire = cleanBool(frame.friendlyFire, false);
    const theme = cleanTheme(frame.theme);
    if (typeof frame.key !== 'string' || !KEY.test(frame.key) || !name || !appearance ||
        !isDungeonProtocol(frame.dungeonProtocol) || !isCoord(frame.x) || !isCoord(frame.y) ||
        present === null || queued === null || friendlyFire === null || !theme) {
      return this.reject(ws, conn, CLOSE.badMessage, 'invalid_hello');
    }
    // Synchronous: no other event can run between here and the end of the takeover below.
    const id = digest('inn-player', frame.key, 22);
    let match: MatchInfo | null = null;
    let preparation: Preparation | null = null;
    let preparationUsed = false;
    let wasVisible = false;
    for (const other of this.live()) {
      if (other === ws) continue;
      const previous = this.conn(other);
      if (previous.member.id !== id) continue;
      // A reconnect takes over the older socket of the same player without a leave/join flicker.
      if (previous.member.phase === 'matched') match = previous.member.match ?? null;
      preparation ??= previous.member.preparation ?? null;
      preparationUsed ||= previous.member.preparationUsed ?? false;
      wasVisible ||= visible(previous.member);
      previous.member.phase = 'closed';
      this.save(other, previous);
      refuse(other, CLOSE.replaced, 'replaced');
    }
    const member = conn.member;
    Object.assign(member, {
      phase: present ? (match ? 'matched' : 'present') : 'away', id, name, appearance,
      protocol: frame.dungeonProtocol, x: frame.x, y: frame.y, queuedAt: 0, friendlyFire, theme,
      match: present ? match : null, preparation, preparationUsed,
    } satisfies Partial<Member>);
    this.save(ws, conn);
    this.welcome(ws, conn);
    if (visible(member)) this.broadcast({ t: 'person', person: this.person(member) }, ws);
    else if (wasVisible) this.broadcast({ t: 'gone', id }, ws);
    if (queued && member.phase === 'present') {
      member.queuedAt = Date.now();
      this.save(ws, conn);
      this.broadcastPerson(member);
      await this.tryMatch(ws, conn);
    }
  }

  private welcome(ws: WebSocket, conn: Conn): void {
    const member = conn.member;
    const now = Date.now();
    const people: Person[] = [];
    for (const other of this.live()) {
      const candidate = this.conn(other).member;
      if (visible(candidate) && candidate.id) people.push(this.person(candidate));
    }
    const events = this.recentEvents(now);
    send(ws, {
      t: 'welcome', v: WIRE_VERSION, selfId: member.id, layoutId: member.layout, shard: member.shard, now,
      present: visible(member), people, events, after: events.at(-1)?.id ?? 0,
      queued: Boolean(member.queuedAt), match: member.match ?? null, preparation: member.preparation ?? null,
      limits: { name: LIMITS.nameChars, chat: LIMITS.chatChars },
    });
  }

  private move(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    const member = conn.member;
    const { x, y } = frame;
    const adjacent = isCoord(x) && isCoord(y) && Math.max(Math.abs(x - member.x!), Math.abs(y - member.y!)) === 1;
    if (member.phase !== 'present' || !adjacent || !conn.move.take()) {
      send(ws, { t: 'error', ref, code: 'move_rejected' });
      if (member.phase === 'present') send(ws, { t: 'you', x: member.x, y: member.y });
      return;
    }
    member.x = x as number;
    member.y = y as number;
    this.save(ws, conn);
    this.broadcast({ t: 'moved', id: member.id, x, y }, ws);
    if (ref) send(ws, { t: 'ok', ref });
  }

  private chat(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    const text = cleanText(frame.text, LIMITS.chatChars);
    if (!text) return this.fail(ws, ref, 'invalid_message');
    if (!visible(conn.member)) return this.fail(ws, ref, 'not_in_inn');
    if (!conn.chat.has()) return this.fail(ws, ref, 'activity_rate_limited');
    const address = conn.member.address ? this.addressChat.get(conn.member.address) : null;
    // A busy room must not also cost the sender its own token.
    if (!this.roomChat.has() || (address && !address.has())) return this.fail(ws, ref, 'room_busy');
    conn.chat.take();
    this.roomChat.take();
    address?.take();
    this.addEvent(conn.member, { kind: 'chat', text });
    this.ok(ws, ref);
  }

  private drink(ws: WebSocket, conn: Conn, ref?: number): void {
    if (!visible(conn.member)) return this.fail(ws, ref, 'not_in_inn');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    // Emotes beyond the room budget are dropped silently.
    if (this.roomEmotes.take()) this.addEvent(conn.member, { kind: 'drink' });
    this.ok(ws, ref);
  }

  private interact(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    if (typeof frame.npcId !== 'string' || !NPC_ID.test(frame.npcId)) return this.fail(ws, ref, 'invalid_message');
    if (!visible(conn.member)) return this.fail(ws, ref, 'not_in_inn');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (this.roomEmotes.take()) this.addEvent(conn.member, { kind: 'interact', npcId: frame.npcId });
    this.ok(ws, ref);
  }

  private profile(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    const name = cleanText(frame.name, LIMITS.nameChars);
    const appearance = cleanAppearance(frame.appearance ?? {});
    if (!name || !appearance) return this.fail(ws, ref, 'invalid_profile');
    if (!conn.lobby.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (conn.member.phase === 'matched') return this.fail(ws, ref, 'busy');
    conn.member.name = name;
    conn.member.appearance = appearance;
    this.save(ws, conn);
    this.broadcastPerson(conn.member);
    this.ok(ws, ref);
  }

  private prepare(ws: WebSocket, conn: Conn, ref?: number): void {
    const member = conn.member;
    if (member.phase !== 'present') return this.fail(ws, ref, 'not_in_inn');
    if (!member.preparation && member.preparationUsed) return this.fail(ws, ref, 'preparation_used');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    // Guessed from the clients: maximum HP changes by 0 or ±2 and rations by 0 or ±1.
    member.preparation ??= { id: crypto.randomUUID(), hp: [-2, 0, 2][randomIndex(3)], food: [-1, 0, 1][randomIndex(3)] };
    this.save(ws, conn);
    send(ws, { t: 'preparation', preparation: member.preparation });
    this.ok(ws, ref);
  }

  private usePreparation(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    if (typeof frame.id !== 'string' || !PREPARATION_ID.test(frame.id)) return this.fail(ws, ref, 'invalid_message');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (conn.member.preparation?.id === frame.id) {
      conn.member.preparation = null;
      conn.member.preparationUsed = true;
      this.save(ws, conn);
    }
    send(ws, { t: 'preparation', preparation: conn.member.preparation ?? null });
    this.ok(ws, ref);
  }

  private matchOptions(conn: Conn, frame: Json): boolean {
    const friendlyFire = cleanBool(frame.friendlyFire, false);
    const theme = cleanTheme(frame.theme);
    if (friendlyFire === null || !theme) return false;
    conn.member.friendlyFire = friendlyFire;
    conn.member.theme = theme;
    return true;
  }

  private async queue(ws: WebSocket, conn: Conn, frame: Json, ref?: number): Promise<void> {
    const member = conn.member;
    if (member.phase !== 'present') return this.fail(ws, ref, member.phase === 'matched' ? 'busy' : 'not_in_inn');
    if (!conn.lobby.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (!this.matchOptions(conn, frame)) return this.fail(ws, ref, 'invalid_message');
    if (!member.queuedAt) member.queuedAt = Date.now();
    this.save(ws, conn);
    this.broadcastPerson(member);
    await this.tryMatch(ws, conn);
    this.ok(ws, ref);
  }

  private async joinTarget(ws: WebSocket, conn: Conn, frame: Json, ref?: number): Promise<void> {
    const member = conn.member;
    if (member.phase !== 'present') return this.fail(ws, ref, member.phase === 'matched' ? 'busy' : 'not_in_inn');
    if (!conn.lobby.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (typeof frame.targetId !== 'string' || frame.targetId === member.id || !this.matchOptions(conn, frame)) {
      return this.fail(ws, ref, 'invalid_message');
    }
    const target = this.live().find(other => other !== ws && this.conn(other).member.id === frame.targetId);
    const host = target && this.conn(target);
    if (!target || !host || host.member.phase !== 'present' || !host.member.queuedAt) {
      return this.fail(ws, ref, 'partner_unavailable');
    }
    if (host.member.protocol !== member.protocol) return this.fail(ws, ref, 'protocol_mismatch');
    if (!await this.pair(target, host, ws, conn)) return this.fail(ws, ref, 'partner_unavailable');
    this.ok(ws, ref);
  }

  private cancel(ws: WebSocket, conn: Conn, ref?: number): void {
    if (!conn.lobby.take()) return this.fail(ws, ref, 'activity_rate_limited');
    if (conn.member.queuedAt) {
      conn.member.queuedAt = 0;
      this.save(ws, conn);
      this.broadcastPerson(conn.member);
    }
    this.ok(ws, ref);
  }

  /** Never refused (apart from the flood limit): a player entering a dungeon must leave the room view. */
  private away(ws: WebSocket, conn: Conn, ref?: number): void {
    const member = conn.member;
    if (visible(member)) {
      member.phase = 'away';
      member.queuedAt = 0;
      member.match = null;
      this.save(ws, conn);
      this.broadcast({ t: 'gone', id: member.id }, ws);
    }
    this.ok(ws, ref);
  }

  private enter(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    const member = conn.member;
    if (!isCoord(frame.x) || !isCoord(frame.y)) return this.fail(ws, ref, 'invalid_message');
    if (!conn.lobby.take()) return this.fail(ws, ref, 'activity_rate_limited');
    const wasVisible = visible(member);
    Object.assign(member, {
      phase: 'present', x: frame.x, y: frame.y, match: null, queuedAt: 0, preparationUsed: false,
    } satisfies Partial<Member>);
    this.save(ws, conn);
    if (wasVisible) this.broadcast({ t: 'moved', id: member.id, x: member.x, y: member.y }, ws);
    this.broadcastPerson(member);
    this.welcome(ws, conn);
    this.ok(ws, ref);
  }

  // ---- matchmaking ----

  private async tryMatch(ws: WebSocket, conn: Conn): Promise<void> {
    let best: [WebSocket, Conn] | null = null;
    for (const other of this.live()) {
      if (other === ws) continue;
      const candidate = this.conn(other);
      const m = candidate.member;
      if (m.phase !== 'present' || !m.queuedAt || m.protocol !== conn.member.protocol) continue;
      if (!best || m.queuedAt < best[1].member.queuedAt!) best = [other, candidate];
    }
    if (best) await this.pair(best[0], best[1], ws, conn);
  }

  /** Pairs two present players. The recruiter that was waiting (`hostWs`) hosts. */
  private async pair(hostWs: WebSocket, host: Conn, guestWs: WebSocket, guest: Conn): Promise<boolean> {
    const id = crypto.randomUUID();
    const sides = [[hostWs, host, 'host'], [guestWs, guest, 'guest']] as const;
    // The run uses the preparations; take them out of the inn so they cannot be used twice.
    const preparations = sides.map(([, conn]) => conn.member.preparation ?? null);
    for (const [ws, conn, role] of sides) {
      const partner = conn === host ? guest.member : host.member;
      conn.member.phase = 'matched';
      conn.member.queuedAt = 0;
      conn.member.preparation = null;
      conn.member.match = { id, role, partnerId: partner.id!, partnerName: partner.name! };
      this.save(ws, conn);
      this.broadcastPerson(conn.member);
    }
    const config: LinkConfig = {
      id, seed: randomUint32(), friendlyFire: host.member.friendlyFire ?? false, theme: host.member.theme ?? 'sewers',
      dungeonProtocol: host.member.protocol!, createdAt: Date.now(),
      players: sides.map(([, conn, role], index) => ({
        role, id: conn.member.id!, name: conn.member.name!, appearance: conn.member.appearance!,
        preparation: preparations[index],
      })),
    };
    const ours = (conn: Conn) => conn.member.phase === 'matched' && conn.member.match?.id === id;
    try {
      await linkStub(this.env, id).init(config);
    } catch {
      // Sides that left in the meantime stay gone; the others get their preparation back.
      sides.forEach(([ws, conn], index) => {
        if (!ours(conn)) return;
        conn.member.phase = 'present';
        conn.member.match = null;
        conn.member.preparation = preparations[index];
        this.save(ws, conn);
        this.broadcastPerson(conn.member);
        send(ws, { t: 'preparation', preparation: conn.member.preparation });
        send(ws, { t: 'error', code: 'match_failed' });
      });
      return false;
    }
    for (const [ws, conn] of sides) {
      if (!ours(conn)) continue;
      send(ws, { t: 'preparation', preparation: null });
      send(ws, { t: 'matched', match: conn.member.match });
    }
    return true;
  }

  // ---- helpers ----

  private conn(ws: WebSocket): Conn {
    let conn = this.conns.get(ws);
    if (!conn) {
      const member = (ws.deserializeAttachment() as Member | null) ??
        { conn: crypto.randomUUID(), phase: 'closed', openedAt: Date.now(), shard: 0, layout: '', address: '' };
      conn = new Conn(member);
      this.conns.set(ws, conn);
    }
    return conn;
  }

  /** Sockets the room still serves (not closed by the server). */
  private live(): WebSocket[] {
    return this.ctx.getWebSockets().filter(ws => this.conn(ws).member.phase !== 'closed');
  }

  private save(ws: WebSocket, conn: Conn): void {
    try { ws.serializeAttachment(conn.member); } catch { /* socket already gone */ }
  }

  /** Sends a refusal, closes the socket and takes it out of the room. */
  private reject(ws: WebSocket, conn: Conn, code: number, reason: string): void {
    refuse(ws, code, reason);
    this.retire(ws, conn);
  }

  /**
   * Takes a socket out of the room for good and tells the others. The attachment is rewritten as
   * `closed`, so later frames or a second close event for it (even after hibernation) do nothing.
   */
  private retire(ws: WebSocket, conn: Conn): void {
    const member = conn.member;
    if (member.phase === 'closed') return;
    const wasVisible = visible(member);
    Object.assign(member, { phase: 'closed', queuedAt: 0, match: null } satisfies Partial<Member>);
    this.save(ws, conn);
    if (!wasVisible || !member.id) return;
    const stillHere = this.live().some(other => visible(this.conn(other).member) && this.conn(other).member.id === member.id);
    if (!stillHere) this.broadcast({ t: 'gone', id: member.id }, ws);
  }

  /** Closes sockets that never said hello and sockets that went silent. */
  private sweep(now: number, force = false): void {
    if (!force && now - this.lastSweep < SWEEP_EVERY_MS) return;
    this.lastSweep = now;
    for (const ws of this.live()) {
      const conn = this.conn(ws);
      const member = conn.member;
      if (member.phase === 'new') {
        if (member.openedAt + LIMITS.helloTimeoutMs <= now) this.reject(ws, conn, CLOSE.helloTimeout, 'hello_timeout');
        continue;
      }
      const pinged = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0;
      if (Math.max(member.seenAt ?? 0, pinged, member.openedAt) + LIMITS.staleMs <= now) {
        close(ws, CLOSE.goingAway, 'stale');
        this.retire(ws, conn);
      }
    }
  }

  /**
   * Nothing is ever written, but drop anything an older version may have stored once the room
   * empties. An object that never wrote reports a single empty 4 KiB page.
   */
  private async cleanUp(): Promise<void> {
    if (this.live().length === 0 && this.ctx.storage.sql.databaseSize > 4096) {
      await this.ctx.storage.deleteAlarm();
      await this.ctx.storage.deleteAll();
    }
  }

  private person(member: Member): Person {
    return {
      id: member.id!, name: member.name!, x: member.x!, y: member.y!, appearance: member.appearance!,
      queued: Boolean(member.queuedAt), dungeonProtocol: member.protocol!,
    };
  }

  private broadcastPerson(member: Member): void {
    if (visible(member)) this.broadcast({ t: 'person', person: this.person(member) });
  }

  /** Sends to every socket currently shown the room, optionally skipping one. */
  private broadcast(message: unknown, except?: WebSocket): void {
    const text = JSON.stringify(message);
    for (const ws of this.live()) {
      if (ws === except) continue;
      if (visible(this.conn(ws).member)) send(ws, text);
    }
  }

  private recentEvents(now: number): InnEvent[] {
    this.events = this.events.filter(event => event.time >= now - LIMITS.eventMaxAgeMs).slice(-LIMITS.eventsKept);
    return this.events;
  }

  private addEvent(member: Member, fields: Pick<InnEvent, 'kind'> & Partial<InnEvent>): void {
    const time = Date.now();
    // Ids are time-based so they keep increasing across shards and restarts.
    const id = Math.max(this.lastEventId + 1, time * 1000);
    this.lastEventId = id;
    const event: InnEvent = { id, playerId: member.id!, name: member.name!, time, ...fields };
    this.events.push(event);
    this.recentEvents(time);
    this.broadcast({ t: 'event', now: time, event });
  }

  private ok(ws: WebSocket, ref?: number): void {
    if (ref) send(ws, { t: 'ok', ref });
  }

  private fail(ws: WebSocket, ref: number | undefined, code: string): void {
    send(ws, { t: 'error', ref, code });
  }
}
