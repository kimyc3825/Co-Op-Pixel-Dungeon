// SPDX-License-Identifier: GPL-3.0-or-later
// One party link per match: authenticates the two matched players, hands both the run settings,
// relays opaque dungeon payloads in order the moment they arrive, and ends the run when a side leaves.
import { DurableObject } from 'cloudflare:workers';
import type { Env, LinkConfig, LinkPlayer } from './env';
import { CLOSE, KEY, LIMITS, WIRE_VERSION, parseFrame, utf8Bytes, type Json } from './protocol';
import { Bucket, acknowledgeClose, close, digest, refuse, send } from './util';

type Role = 'host' | 'guest';
const ROLES: Role[] = ['host', 'guest'];
const other = (role: Role): Role => role === 'host' ? 'guest' : 'host';

interface Attachment { phase: 'new' | 'joined' | 'closed'; openedAt: number; role?: Role }

interface LinkState {
  pairedAt: number;
  ready: Record<Role, boolean>;
  /** When a joined side lost its socket without leaving; cleared on reconnect. */
  lostAt: Partial<Record<Role, number>>;
}

class Conn {
  // A relay frame every 120 ms per side is normal for the original engine; this leaves ample headroom.
  readonly messages = new Bucket(120, 60);
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
      this.config = (await ctx.storage.get<LinkConfig>('config')) ?? null;
      this.state = (await ctx.storage.get<LinkState>('state')) ?? this.state;
    });
  }

  /** Called once by the inn room that made the match. */
  async init(config: LinkConfig): Promise<void> {
    if (this.config || this.ended) throw new Error('link_exists');
    if (config.players.length !== 2 || config.players[0].role !== 'host' || config.players[1].role !== 'guest') {
      throw new Error('invalid_link');
    }
    this.config = config;
    await this.ctx.storage.put({ config, state: this.state });
    await this.reschedule();
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return new Response('Expected WebSocket', { status: 426 });
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const attachment: Attachment = { phase: 'new', openedAt: Date.now() };
    server.serializeAttachment(attachment);
    this.conns.set(server, new Conn(attachment));
    if (!this.config || this.ended) {
      this.closeSocket(server, CLOSE.notFound, 'link_not_found');
    } else if (this.ctx.getWebSockets().length > 6) {
      this.closeSocket(server, CLOSE.full, 'link_full');
    } else {
      await this.reschedule();
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const conn = this.conn(ws);
    if (conn.attachment.phase === 'closed' || !this.config) return;
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (!conn.messages.take() || !conn.bytes.take(size)) {
      if (conn.attachment.phase === 'joined') await this.end('rate_limited', conn.attachment.role, CLOSE.rateLimited);
      else this.closeSocket(ws, CLOSE.rateLimited, 'rate_limited');
      return;
    }
    const parsed = parseFrame(message, LIMITS.linkFrameBytes);
    if ('close' in parsed) {
      if (conn.attachment.phase === 'joined') {
        // A broken frame from a matched player ends the run for both, like a leave.
        await this.end('protocol_error', conn.attachment.role, parsed.close);
      } else this.closeSocket(ws, parsed.close, parsed.reason);
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
          await this.saveState();
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
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = this.conn(ws).attachment;
      if (attachment.phase === 'new' && attachment.openedAt + LIMITS.helloTimeoutMs <= now) {
        this.closeSocket(ws, CLOSE.helloTimeout, 'hello_timeout');
      }
    }
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
    const id = await digest('inn-player', frame.key, 22);
    const config = this.config;
    if (!config || this.ended || conn.attachment.phase !== 'new') return;
    const player = config.players.find(candidate => candidate.id === id);
    if (!player) {
      this.closeSocket(ws, CLOSE.forbidden, 'forbidden');
      return;
    }
    const role = player.role;
    const previous = this.socket(role);
    if (previous) this.closeSocket(previous, CLOSE.replaced, 'replaced');
    conn.attachment = { phase: 'joined', openedAt: conn.attachment.openedAt, role };
    ws.serializeAttachment(conn.attachment);
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
    await this.saveState();
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
    try { ws.serializeAttachment(conn.attachment); } catch { /* gone */ }
    this.conns.delete(ws);
    if (phase !== 'joined' || !role || !this.config || this.ended || this.socket(role)) return;
    this.state.lostAt[role] = Date.now();
    await this.saveState();
    const partner = this.socket(other(role));
    if (partner) send(partner, { t: 'peer', connected: false });
    await this.reschedule();
  }

  /**
   * Ends the run: the side that caused it (if any) gets a plain close, the other side an `ended`
   * frame with the reason. All storage is deleted, so later connections get `link_not_found`.
   */
  private async end(reason: string, by?: Role, closeCode: number = CLOSE.normal): Promise<void> {
    if (this.ended) return;
    this.ended = true;
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = this.conn(ws).attachment;
      if (attachment.phase === 'closed') continue;
      if (attachment.phase === 'joined' && attachment.role === by) {
        if (closeCode !== CLOSE.normal) send(ws, { t: 'error', code: reason });
        this.closeSocket(ws, closeCode, reason, false);
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
      conn = new Conn((ws.deserializeAttachment() as Attachment | null) ?? { phase: 'new', openedAt: Date.now() });
      this.conns.set(ws, conn);
    }
    return conn;
  }

  private socket(role: Role): WebSocket | undefined {
    return this.ctx.getWebSockets().find(ws => {
      const attachment = this.conn(ws).attachment;
      return attachment.phase === 'joined' && attachment.role === role;
    });
  }

  private closeSocket(ws: WebSocket, code: number, reason: string, withError = true): void {
    const conn = this.conn(ws);
    conn.attachment = { ...conn.attachment, phase: 'closed' };
    try { ws.serializeAttachment(conn.attachment); } catch { /* gone */ }
    if (withError) refuse(ws, code, reason);
    else close(ws, code, reason);
  }

  private async saveState(): Promise<void> {
    if (!this.ended) await this.ctx.storage.put('state', this.state);
  }

  private async reschedule(): Promise<void> {
    if (!this.config || this.ended) return;
    const deadlines = [this.config.createdAt + LIMITS.linkLifetimeMs];
    if (!this.state.pairedAt) deadlines.push(this.config.createdAt + LIMITS.pairTimeoutMs);
    for (const role of ROLES) {
      const lostAt = this.state.lostAt[role];
      if (lostAt) deadlines.push(lostAt + LIMITS.reconnectGraceMs);
    }
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = this.conn(ws).attachment;
      if (attachment.phase === 'new') deadlines.push(attachment.openedAt + LIMITS.helloTimeoutMs);
    }
    const at = Math.min(...deadlines);
    if ((await this.ctx.storage.getAlarm()) !== at) await this.ctx.storage.setAlarm(at);
  }
}
