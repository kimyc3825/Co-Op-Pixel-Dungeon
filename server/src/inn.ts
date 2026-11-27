// SPDX-License-Identifier: GPL-3.0-or-later
// One inn room shard: presence, movement, chat, NPC notices, expedition preparation and matchmaking.
// Every socket is hibernatable. Per-player state lives in the socket attachment, so it survives
// hibernation; only recent room events and pending preparations are written to SQLite.
import { DurableObject } from 'cloudflare:workers';
import type { Env, LinkConfig } from './env';
import {
  CLOSE, LIMITS, KEY, NPC_ID, PREPARATION_ID, WIRE_VERSION, cleanAppearance, cleanBool, cleanText, cleanTheme,
  isCoord, isDungeonProtocol, isRef, parseFrame,
  type Appearance, type Json, type Preparation, type Theme,
} from './protocol';
import { Bucket, acknowledgeClose, close, digest, randomIndex, randomUint32, refuse, send } from './util';

type Phase = 'new' | 'present' | 'away' | 'matched';

interface MatchInfo { id: string; role: 'host' | 'guest'; partnerId: string; partnerName: string }

interface Member {
  conn: string;
  phase: Phase;
  openedAt: number;
  shard: number;
  layout: string;
  id?: string;
  keyHash?: string;
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
  /** Last message time, persisted at most every 30 s so stale sockets are found after hibernation. */
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
  readonly all = new Bucket(40, 20);
  readonly move = new Bucket(6, 8);
  readonly chat = new Bucket(4, 1 / 3);
  readonly activity = new Bucket(6, 0.5);
  readonly lobby = new Bucket(8, 1);
  constructor(public member: Member) {}
}

const visible = (member: Member) => member.phase === 'present' || member.phase === 'matched';

export class InnRoom extends DurableObject<Env> {
  private readonly conns = new Map<WebSocket, Conn>();
  private lastEventId = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Keep-alive pings are answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, body TEXT NOT NULL)`);
      ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS preparations (key TEXT PRIMARY KEY, id TEXT NOT NULL,
        hp INTEGER NOT NULL, food INTEGER NOT NULL, expires INTEGER NOT NULL)`);
      const row = ctx.storage.sql.exec<{ id: number | null }>('SELECT MAX(id) AS id FROM events').one();
      this.lastEventId = row.id ?? 0;
    });
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return new Response('Expected WebSocket', { status: 426 });
    const shard = Number(request.headers.get('X-Inn-Shard'));
    const layout = request.headers.get('X-Inn-Layout') ?? '';
    // The Worker tries the next shard on 409.
    if (this.ctx.getWebSockets().length >= LIMITS.socketsPerShard) return new Response('inn_full', { status: 409 });
    const [client, server] = Object.values(new WebSocketPair());
    const member: Member = { conn: crypto.randomUUID(), phase: 'new', openedAt: Date.now(), shard, layout };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(member);
    this.conns.set(server, new Conn(member));
    await this.scheduleAlarm(Date.now() + LIMITS.helloTimeoutMs);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const conn = this.conn(ws);
    const now = Date.now();
    if (now - (conn.member.seenAt ?? 0) >= 30_000) {
      conn.member.seenAt = now;
      this.save(ws, conn);
    }
    if (!conn.all.take(1, now)) {
      refuse(ws, CLOSE.rateLimited, 'rate_limited');
      this.drop(ws, conn);
      return;
    }
    const parsed = parseFrame(message, LIMITS.innFrameBytes);
    if ('close' in parsed) {
      refuse(ws, parsed.close, parsed.reason);
      this.drop(ws, conn);
      return;
    }
    const frame = parsed.frame;
    const ref = isRef(frame.ref) ? frame.ref : undefined;
    if (conn.member.phase === 'new') {
      if (frame.t !== 'hello') {
        refuse(ws, CLOSE.badMessage, 'hello_required');
        this.drop(ws, conn);
        return;
      }
      await this.hello(ws, conn, frame);
      return;
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
    this.drop(ws, this.conn(ws));
    this.conns.delete(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.drop(ws, this.conn(ws));
    this.conns.delete(ws);
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const conn = this.conn(ws);
      const member = conn.member;
      if (member.phase === 'new') {
        const deadline = member.openedAt + LIMITS.helloTimeoutMs;
        if (deadline <= now) {
          refuse(ws, CLOSE.helloTimeout, 'hello_timeout');
          this.drop(ws, conn);
        } else next = Math.min(next, deadline);
        continue;
      }
      const pinged = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0;
      if (Math.max(member.seenAt ?? 0, pinged, member.openedAt) + LIMITS.staleMs <= now) {
        close(ws, CLOSE.goingAway, 'stale');
        this.drop(ws, conn);
      } else next = Math.min(next, now + LIMITS.sweepMs);
    }
    if (next !== Infinity) await this.ctx.storage.setAlarm(next);
  }

  // ---- handlers ----

  private async hello(ws: WebSocket, conn: Conn, frame: Json): Promise<void> {
    if (frame.v !== WIRE_VERSION) {
      send(ws, { t: 'error', code: 'unsupported_version', supported: [WIRE_VERSION] });
      close(ws, CLOSE.unsupportedVersion, 'unsupported_version');
      this.drop(ws, conn);
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
      refuse(ws, CLOSE.badMessage, 'invalid_hello');
      this.drop(ws, conn);
      return;
    }
    const [id, keyHash] = await Promise.all([digest('inn-player', frame.key, 22), digest('inn-preparation', frame.key)]);
    // Other events may have run during the digest; re-check before mutating.
    if (conn.member.phase !== 'new' || !this.ctx.getWebSockets().includes(ws)) return;
    let match: MatchInfo | null = null;
    for (const other of this.ctx.getWebSockets()) {
      if (other === ws) continue;
      const previous = this.conn(other);
      if (previous.member.id !== id) continue;
      // A reconnect replaces the older socket of the same player without a leave/join flicker.
      if (previous.member.phase === 'matched') match = previous.member.match ?? null;
      previous.member.phase = 'new';
      this.save(other, previous);
      refuse(other, CLOSE.replaced, 'replaced');
    }
    const member = conn.member;
    Object.assign(member, {
      phase: present ? (match ? 'matched' : 'present') : 'away', id, keyHash, name, appearance,
      protocol: frame.dungeonProtocol, x: frame.x, y: frame.y, queuedAt: 0, friendlyFire, theme, match,
    } satisfies Partial<Member>);
    this.save(ws, conn);
    this.welcome(ws, conn);
    if (visible(member)) this.broadcast({ t: 'person', person: this.person(member) }, ws);
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
    for (const other of this.ctx.getWebSockets()) {
      const candidate = this.conn(other).member;
      if (visible(candidate) && candidate.id) people.push(this.person(candidate));
    }
    const events = this.ctx.storage.sql.exec<{ body: string }>(
      'SELECT body FROM events WHERE at >= ? ORDER BY id DESC LIMIT ?', now - LIMITS.eventMaxAgeMs, LIMITS.eventsKept,
    ).toArray().reverse().map(row => JSON.parse(row.body) as InnEvent);
    send(ws, {
      t: 'welcome', v: WIRE_VERSION, selfId: member.id, layoutId: member.layout, shard: member.shard, now,
      present: visible(member), people, events, after: events.at(-1)?.id ?? 0,
      queued: Boolean(member.queuedAt), match: member.match ?? null, preparation: this.preparation(member),
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
    if (!conn.chat.take()) return this.fail(ws, ref, 'activity_rate_limited');
    this.addEvent(conn.member, { kind: 'chat', text });
    this.ok(ws, ref);
  }

  private drink(ws: WebSocket, conn: Conn, ref?: number): void {
    if (!visible(conn.member)) return this.fail(ws, ref, 'not_in_inn');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    this.addEvent(conn.member, { kind: 'drink' });
    this.ok(ws, ref);
  }

  private interact(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    if (typeof frame.npcId !== 'string' || !NPC_ID.test(frame.npcId)) return this.fail(ws, ref, 'invalid_message');
    if (!visible(conn.member)) return this.fail(ws, ref, 'not_in_inn');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    this.addEvent(conn.member, { kind: 'interact', npcId: frame.npcId });
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
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    let preparation = this.preparation(member);
    if (!preparation) {
      // Guessed from the clients: maximum HP changes by 0 or ±2 and rations by 0 or ±1.
      preparation = { id: crypto.randomUUID(), hp: [-2, 0, 2][randomIndex(3)], food: [-1, 0, 1][randomIndex(3)] };
      const now = Date.now();
      this.ctx.storage.sql.exec('DELETE FROM preparations WHERE expires < ?', now);
      this.ctx.storage.sql.exec('INSERT OR REPLACE INTO preparations (key, id, hp, food, expires) VALUES (?, ?, ?, ?, ?)',
        member.keyHash, preparation.id, preparation.hp, preparation.food, now + LIMITS.preparationTtlMs);
    }
    send(ws, { t: 'preparation', preparation });
    this.ok(ws, ref);
  }

  private usePreparation(ws: WebSocket, conn: Conn, frame: Json, ref?: number): void {
    if (typeof frame.id !== 'string' || !PREPARATION_ID.test(frame.id)) return this.fail(ws, ref, 'invalid_message');
    if (!conn.activity.take()) return this.fail(ws, ref, 'activity_rate_limited');
    this.ctx.storage.sql.exec('DELETE FROM preparations WHERE key = ? AND id = ?', conn.member.keyHash, frame.id);
    send(ws, { t: 'preparation', preparation: this.preparation(conn.member) });
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
    const target = this.ctx.getWebSockets().find(other => this.conn(other).member.id === frame.targetId);
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
    Object.assign(member, { phase: 'present', x: frame.x, y: frame.y, match: null, queuedAt: 0 } satisfies Partial<Member>);
    this.save(ws, conn);
    if (wasVisible) this.broadcast({ t: 'moved', id: member.id, x: member.x, y: member.y }, ws);
    this.broadcastPerson(member);
    this.welcome(ws, conn);
    this.ok(ws, ref);
  }

  // ---- matchmaking ----

  private async tryMatch(ws: WebSocket, conn: Conn): Promise<void> {
    let best: [WebSocket, Conn] | null = null;
    for (const other of this.ctx.getWebSockets()) {
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
    const preparations = sides.map(([, conn]) => this.preparation(conn.member));
    for (const [ws, conn, role] of sides) {
      const partner = conn === host ? guest.member : host.member;
      conn.member.phase = 'matched';
      conn.member.queuedAt = 0;
      conn.member.match = { id, role, partnerId: partner.id!, partnerName: partner.name! };
      // The run uses the preparation; consume it now so it cannot be carried into another expedition.
      this.ctx.storage.sql.exec('DELETE FROM preparations WHERE key = ?', conn.member.keyHash);
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
    try {
      await this.env.LINK.get(this.env.LINK.idFromName(id)).init(config);
    } catch {
      for (const [ws, conn] of sides) {
        if (conn.member.match?.id !== id) continue;
        conn.member.phase = 'present';
        conn.member.match = null;
        this.save(ws, conn);
        this.broadcastPerson(conn.member);
        send(ws, { t: 'error', code: 'match_failed' });
      }
      return false;
    }
    for (const [ws, conn] of sides) {
      if (conn.member.match?.id !== id) continue;
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
        { conn: crypto.randomUUID(), phase: 'new', openedAt: Date.now(), shard: 0, layout: '' };
      conn = new Conn(member);
      this.conns.set(ws, conn);
    }
    return conn;
  }

  private save(ws: WebSocket, conn: Conn): void {
    try { ws.serializeAttachment(conn.member); } catch { /* socket already gone */ }
  }

  /**
   * Takes a closing socket out of the room and tells the others. The attachment is rewritten so a
   * later close event for the same socket, possibly after hibernation, does not announce it twice.
   */
  private drop(ws: WebSocket, conn: Conn): void {
    const member = conn.member;
    const wasVisible = visible(member);
    member.phase = 'new';
    member.queuedAt = 0;
    this.save(ws, conn);
    if (!wasVisible || !member.id) return;
    const replaced = this.ctx.getWebSockets().some(other => other !== ws && this.conn(other).member.id === member.id);
    if (!replaced) this.broadcast({ t: 'gone', id: member.id }, ws);
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
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === except) continue;
      if (visible(this.conn(ws).member)) send(ws, text);
    }
  }

  private addEvent(member: Member, fields: Pick<InnEvent, 'kind'> & Partial<InnEvent>): void {
    const time = Date.now();
    // Ids are time-based so they keep increasing across shards and restarts.
    const id = Math.max(this.lastEventId + 1, time * 1000);
    this.lastEventId = id;
    const event: InnEvent = { id, playerId: member.id!, name: member.name!, time, ...fields };
    this.ctx.storage.sql.exec('INSERT INTO events (id, at, body) VALUES (?, ?, ?)', id, time, JSON.stringify(event));
    // Keep only a short window of chat; nothing older is retained.
    this.ctx.storage.sql.exec('DELETE FROM events WHERE at < ? OR id <= (SELECT id FROM events ORDER BY id DESC LIMIT 1 OFFSET ?)',
      time - LIMITS.eventMaxAgeMs, LIMITS.eventsKept);
    this.broadcast({ t: 'event', now: time, event });
  }

  private preparation(member: Member): Preparation | null {
    if (!member.keyHash) return null;
    const row = this.ctx.storage.sql.exec<{ id: string; hp: number; food: number }>(
      'SELECT id, hp, food FROM preparations WHERE key = ? AND expires >= ?', member.keyHash, Date.now(),
    ).toArray()[0];
    return row ? { id: row.id, hp: row.hp, food: row.food } : null;
  }

  private ok(ws: WebSocket, ref?: number): void {
    if (ref) send(ws, { t: 'ok', ref });
  }

  private fail(ws: WebSocket, ref: number | undefined, code: string): void {
    send(ws, { t: 'error', ref, code });
  }

  private async scheduleAlarm(at: number): Promise<void> {
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > at) await this.ctx.storage.setAlarm(at);
  }
}
