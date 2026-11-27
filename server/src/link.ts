// SPDX-License-Identifier: GPL-3.0-or-later
// One party link per match: authenticates the two matched players, hands both the run settings,
// relays opaque dungeon payloads in order the moment they arrive, and ends the run when a side leaves.
//
// Storage: one row (`link`: settings and pairing state), written at creation, on pairing, on each
// side's first `ready` and when a side drops; deleted with everything else when the run ends.
import { DurableObject } from 'cloudflare:workers';
import type { Env, LinkConfig, LinkPlayer } from './env';
import { CLOSE, KEY, LIMITS, WIRE_VERSION, parseFrame, utf8Bytes, type Json } from './protocol';
import { Bucket, acknowledgeClose, close, digest, refuse, send } from './util';

type Role = 'host' | 'guest';
const ROLES: Role[] = ['host', 'guest'];
const other = (role: Role): Role => role === 'host' ? 'guest' : 'host';

interface Attachment { phase: 'new' | 'joined' | 'closed'; openedAt: number; role?: Role; seenAt?: number }

interface LinkState {
  pairedAt: number;
  ready: Record<Role, boolean>;
  /** When a joined side lost its socket without leaving; cleared on reconnect. */
  lostAt: Partial<Record<Role, number>>;
}

interface Stored { config: LinkConfig; state: LinkState }

class Conn {
  // 25 frames/s sustained protects the request quota; the burst of 300 absorbs bursts of play and a
  // reconnect's catch-up without ending a legitimate run.
  readonly messages = new Bucket(300, 25);
  readonly bytes = new Bucket(2 * 1024 * 1024, 1024 * 1024);
  constructor(public attachment: Attachment) {}
}

export class PartyLink extends DurableObject<Env> {
  private config: LinkConfig | null = null;
  private state: LinkState = { pairedAt: 0, ready: { host: false, guest: false }, lostAt: {} };
  private readonly conns = new Map<WebSocket, Conn>();
  /** Frames for a side that is reconnecting, in order. Memory only. */
  private readonly pending: Record<Role, { frames: string[]; bytes: number }> = {
    host: { frames: [], bytes: 0 }, guest: { frames: [], bytes: 0 },
  };
  private ended = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    ctx.blockConcurrencyWhile(async () => {
      const stored = await ctx.storage.get<Stored>('link');
      if (!stored) return;
      this.config = stored.config;
      this.state = stored.state;
      // After a reset (a deploy, an eviction) no close handler runs for sockets that were lost.
      // A paired side without a socket counts as lost from now on.
      await this.noticeMissing();
    });
  }

  /** Called once by the inn room that made the match. */
  async init(config: LinkConfig): Promise<void> {
    if (this.config || this.ended) throw new Error('link_exists');
    if (config.players.length !== 2 || config.players[0].role !== 'host' || config.players[1].role !== 'guest') {
      throw new Error('invalid_link');
    }
    this.config = config;
    await this.save();
    await this.reschedule();
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return new Response('Expected WebSocket', { status: 426 });
    const now = Date.now();
    this.sweep(now);
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const attachment: Attachment = { phase: 'new', openedAt: now };
    server.serializeAttachment(attachment);
    this.conns.set(server, new Conn(attachment));
    if (!this.config || this.ended) {
      this.closeSocket(server, CLOSE.notFound, 'link_not_found');
      return new Response(null, { status: 101, webSocket: client });
    }
    // Closed sockets whose close was never acknowledged still count here, so they cannot pile up.
    if (this.ctx.getWebSockets().length > LIMITS.linkSocketsTotal) {
      this.closeSocket(server, CLOSE.full, 'link_full');
      return new Response(null, { status: 101, webSocket: client });
    }
    const live = this.live();
    if (live.length > LIMITS.linkSockets) {
      // Unauthenticated sockets must not lock a player out: make room by dropping the oldest one.
      const squatter = live.filter(ws => ws !== server && this.conn(ws).attachment.phase === 'new')
        .sort((a, b) => this.conn(a).attachment.openedAt - this.conn(b).attachment.openedAt)[0];
      if (squatter) this.closeSocket(squatter, CLOSE.full, 'link_full');
      else this.closeSocket(server, CLOSE.full, 'link_full');
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const conn = this.conn(ws);
    if (conn.attachment.phase === 'closed' || !this.config || this.ended) return;
    const now = Date.now();
    if (now - (conn.attachment.seenAt ?? 0) >= 30_000) {
      conn.attachment.seenAt = now;
      this.saveAttachment(ws, conn);
    }
    this.sweep(now);
    if ((conn.attachment.phase as Attachment["phase"]) === "closed") return;
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (!conn.messages.take(1, now) || !conn.bytes.take(size, now)) {
      if (conn.attachment.phase === 'joined') await this.end('rate_limited', conn.attachment.role, CLOSE.rateLimited);
      else this.closeSocket(ws, CLOSE.rateLimited, 'rate_limited');
      return;
    }
    const parsed = parseFrame(message, LIMITS.linkFrameBytes);
    if ('close' in parsed) {
      // A broken frame from a matched player ends the run for both, like a leave.
      if (conn.attachment.phase === 'joined') await this.end('protocol_error', conn.attachment.role, parsed.close);
      else this.closeSocket(ws, parsed.close, parsed.reason);
      return;
    }
    const frame = parsed.frame;
    if (conn.attachment.phase === 'new') {
      if (frame.t === 'hello') await this.hello(ws, conn, frame);
      else this.closeSocket(ws, CLOSE.badMessage, 'hello_required');
      return;
    }
    const role = conn.attachment.role!;
    switch (frame.t) {
      case 'data': {
        const data = frame.d;
        if (typeof data !== 'string' || !data || data.length > LIMITS.linkDataBytes || utf8Bytes(data) > LIMITS.linkDataBytes) {
          send(ws, { t: 'error', code: 'payload_too_large' });
          return;
        }
        await this.deliver(other(role), JSON.stringify({ t: 'data', d: data }));
        return;
      }
      case 'ready':
        if (!this.state.ready[role]) {
          this.state.ready[role] = true;
          await this.save();
        }
        await this.deliver(other(role), JSON.stringify({ t: 'peer', ready: true }));
        return;
      case 'leave':
        await this.end('partner_left', role, CLOSE.normal);
        return;
      default:
        send(ws, { t: 'error', code: 'unknown_type' });
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    acknowledgeClose(ws, code, reason);
    await this.lost(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.lost(ws);
  }

  async alarm(): Promise<void> {
    if (!this.config || this.ended) return;
    const now = Date.now();
    this.sweep(now);
    await this.noticeMissing();
    if (!this.state.pairedAt && now >= this.config.createdAt + LIMITS.pairTimeoutMs) {
      await this.end('pair_timeout');
      return;
    }
    for (const role of ROLES) {
      const lostAt = this.state.lostAt[role];
      if (lostAt && !this.socket(role) && now >= lostAt + LIMITS.reconnectGraceMs) {
        await this.end('partner_lost', role);
        return;
      }
    }
    if (now >= this.config.createdAt + LIMITS.linkLifetimeMs) {
      await this.end('expired');
      return;
    }
    await this.reschedule();
  }

  // ---- handlers ----

  private async hello(ws: WebSocket, conn: Conn, frame: Json): Promise<void> {
    if (frame.v !== WIRE_VERSION) {
      send(ws, { t: 'error', code: 'unsupported_version', supported: [WIRE_VERSION] });
      this.closeSocket(ws, CLOSE.unsupportedVersion, 'unsupported_version', false);
      return;
    }
    if (typeof frame.key !== 'string' || !KEY.test(frame.key)) {
      this.closeSocket(ws, CLOSE.badMessage, 'invalid_hello');
      return;
    }
    const config = this.config!;
    // Synchronous digest: no other frame can run before this socket has joined.
    const player = config.players.find(candidate => candidate.id === digest('inn-player', frame.key as string, 22));
    if (!player) {
      this.closeSocket(ws, CLOSE.forbidden, 'forbidden');
      return;
    }
    const role = player.role;
    const previous = this.socket(role);
    if (previous) this.closeSocket(previous, CLOSE.replaced, 'replaced');
    conn.attachment = { phase: 'joined', openedAt: conn.attachment.openedAt, role, seenAt: Date.now() };
    this.saveAttachment(ws, conn);
    const wasLost = role in this.state.lostAt;
    delete this.state.lostAt[role];
    const partner = config.players.find(candidate => candidate.role !== role)!;
    const partnerSocket = this.socket(other(role));
    const firstPairing = !this.state.pairedAt && Boolean(partnerSocket);
    if (firstPairing) this.state.pairedAt = Date.now();
    send(ws, this.describe(config, player, partner, Boolean(partnerSocket)));
    if (firstPairing) {
      send(ws, { t: 'paired' });
      send(partnerSocket!, { t: 'paired' });
    } else if (this.state.pairedAt && partnerSocket && !previous) {
      send(partnerSocket, { t: 'peer', connected: true });
    }
    const queue = this.pending[role];
    for (const text of queue.frames.splice(0)) send(ws, text);
    queue.bytes = 0;
    if (firstPairing || wasLost) await this.save();
    await this.noticeMissing();
    await this.reschedule();
  }

  private describe(config: LinkConfig, self: LinkPlayer, partner: LinkPlayer, partnerConnected: boolean) {
    return {
      t: 'link', v: WIRE_VERSION, id: config.id, role: self.role,
      selfId: self.id, selfName: self.name, selfAppearance: self.appearance, selfPreparation: self.preparation,
      partnerId: partner.id, partnerName: partner.name, partnerAppearance: partner.appearance,
      partnerPreparation: partner.preparation,
      seed: config.seed, friendlyFire: config.friendlyFire, theme: config.theme, dungeonProtocol: config.dungeonProtocol,
      paired: Boolean(this.state.pairedAt), partnerConnected, partnerReady: this.state.ready[partner.role],
    };
  }

  /** Sends to a side immediately, or queues in order while that side reconnects. */
  private async deliver(role: Role, text: string): Promise<void> {
    const target = this.socket(role);
    if (target && send(target, text)) return;
    const queue = this.pending[role];
    queue.frames.push(text);
    queue.bytes += text.length;
    if (queue.frames.length > LIMITS.linkBufferMessages || queue.bytes > LIMITS.linkBufferBytes) {
      await this.end('partner_lost', role);
    }
  }

  private async lost(ws: WebSocket): Promise<void> {
    const conn = this.conn(ws);
    const { phase, role } = conn.attachment;
    conn.attachment = { ...conn.attachment, phase: 'closed' };
    this.saveAttachment(ws, conn);
    this.conns.delete(ws);
    if (phase !== 'joined' || !role) return;
    await this.markLost(role);
  }

  private async markLost(role: Role): Promise<void> {
    if (!this.config || this.ended || this.socket(role) || this.state.lostAt[role]) return;
    this.state.lostAt[role] = Date.now();
    await this.save();
    const partner = this.socket(other(role));
    if (partner) send(partner, { t: 'peer', connected: false });
    await this.reschedule();
  }

  /** Paired sides without a socket and without a recorded loss (missed close events) are lost now. */
  private async noticeMissing(): Promise<void> {
    if (!this.config || this.ended || !this.state.pairedAt) return;
    for (const role of ROLES) if (!this.socket(role)) await this.markLost(role);
  }

  /** Closes sockets that never said hello in time, and joined sockets that went silent. */
  private sweep(now: number): void {
    for (const ws of this.live()) {
      const conn = this.conn(ws);
      const attachment = conn.attachment;
      if (attachment.phase === 'new') {
        if (attachment.openedAt + LIMITS.helloTimeoutMs <= now) this.closeSocket(ws, CLOSE.helloTimeout, 'hello_timeout');
        continue;
      }
      const pinged = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0;
      if (Math.max(attachment.seenAt ?? 0, pinged, attachment.openedAt) + LIMITS.staleMs <= now) {
        const role = attachment.role;
        this.closeSocket(ws, CLOSE.goingAway, 'stale', false);
        if (role) void this.markLost(role);
      }
    }
  }

  /**
   * Ends the run: the side that caused it (if any) gets a plain close, the other side an `ended`
   * frame with the reason. All storage is deleted, so later connections get `link_not_found`.
   */
  private async end(reason: string, by?: Role, closeCode: number = CLOSE.normal): Promise<void> {
    if (this.ended) return;
    this.ended = true;
    for (const ws of this.live()) {
      const attachment = this.conn(ws).attachment;
      if (attachment.phase === 'joined' && attachment.role === by) {
        if (closeCode !== CLOSE.normal) send(ws, { t: 'error', code: reason });
        this.closeSocket(ws, closeCode, closeCode === CLOSE.normal ? 'left' : reason, false);
      } else {
        send(ws, { t: 'ended', reason });
        this.closeSocket(ws, CLOSE.ended, reason, false);
      }
    }
    this.config = null;
    this.pending.host = { frames: [], bytes: 0 };
    this.pending.guest = { frames: [], bytes: 0 };
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }

  // ---- helpers ----

  private conn(ws: WebSocket): Conn {
    let conn = this.conns.get(ws);
    if (!conn) {
      conn = new Conn((ws.deserializeAttachment() as Attachment | null) ?? { phase: 'closed', openedAt: Date.now() });
      this.conns.set(ws, conn);
    }
    return conn;
  }

  private live(): WebSocket[] {
    return this.ctx.getWebSockets().filter(ws => this.conn(ws).attachment.phase !== 'closed');
  }

  private socket(role: Role): WebSocket | undefined {
    return this.live().find(ws => {
      const attachment = this.conn(ws).attachment;
      return attachment.phase === 'joined' && attachment.role === role;
    });
  }

  private saveAttachment(ws: WebSocket, conn: Conn): void {
    try { ws.serializeAttachment(conn.attachment); } catch { /* gone */ }
  }

  private closeSocket(ws: WebSocket, code: number, reason: string, withError = true): void {
    const conn = this.conn(ws);
    conn.attachment = { ...conn.attachment, phase: 'closed' };
    this.saveAttachment(ws, conn);
    if (withError) refuse(ws, code, reason);
    else close(ws, code, reason);
  }

  private async save(): Promise<void> {
    if (this.config && !this.ended) await this.ctx.storage.put<Stored>('link', { config: this.config, state: this.state });
  }

  private async reschedule(): Promise<void> {
    if (!this.config || this.ended) return;
    const deadlines = [this.config.createdAt + LIMITS.linkLifetimeMs];
    if (!this.state.pairedAt) deadlines.push(this.config.createdAt + LIMITS.pairTimeoutMs);
    // While paired, wake up regularly to find a side that died silently while the other only watches.
    else deadlines.push(Date.now() + LIMITS.linkCheckMs);
    for (const role of ROLES) {
      const lostAt = this.state.lostAt[role];
      if (lostAt) deadlines.push(lostAt + LIMITS.reconnectGraceMs);
    }
    const at = Math.min(...deadlines);
    // An earlier pending alarm is fine (the handler re-evaluates); only write when none is due in time.
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current <= Date.now() || current > at) await this.ctx.storage.setAlarm(at);
  }
}
