// SPDX-License-Identifier: GPL-3.0-or-later
import { HostBattle } from './battle.mjs';

const INN = '/api/v2/inn/';
const LINK = '/api/v4/link/';
const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];
const INN_POLL_MS = 700;
const MOVE_MS = 150;
const RELAY_POLL_MS = 165;
const DIRECT_POLL_MS = 2500;
const SIGNAL_RETRY_MS = 450;
const HEARTBEAT_MS = 650;
const DIRECT_TIMEOUT_MS = 3500;
const COMMAND_RETRY_MS = 275;
const ENTRY_TIMEOUT_MS = 16000;
const HOST_TIMEOUT_MS = 14000;
const MAX_COMMANDS = 16;
const MAX_MESSAGE_BYTES = 24 * 1024;
const MAX_CHANNEL_BUFFER = 128 * 1024;
const encoder = new TextEncoder();

const now = () => Math.floor(performance.timeOrigin + performance.now());
const newKey = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const newSeed = () => crypto.getRandomValues(new Uint32Array(1))[0];

class ApiError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

async function request(path, key, method, body, keepalive = false) {
  const headers = { Authorization: `Bearer ${key}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), keepalive,
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(response.status, error?.error ?? 'request_failed');
  }
  return response.status === 204 ? null : response.json();
}

const active = status => status === 'active' || status === 'won';
const linkEnded = error => error.status === 404 || error.status === 410 || error.code === 'link_inactive';
const validName = value => value.length > 0 && [...value].length <= 20 && !/[\p{C}\p{Zl}\p{Zp}]/u.test(value);
const walkable = (map, x, y) => map?.[y]?.[x] === '.' || map?.[y]?.[x] === 'D';

function normalizeAction(type, dx, dy, target) {
  if (type === 'move' && Number.isInteger(dx) && Number.isInteger(dy) && Math.max(Math.abs(dx), Math.abs(dy)) === 1) {
    return { type, dx, dy, target: null };
  }
  if (type === 'attack' && typeof target === 'string' && target.length > 0 && target.length <= 80) {
    return { type, dx: 0, dy: 0, target };
  }
  if (type === 'eat') return { type, dx: 0, dy: 0, target: null };
  return null;
}

export class PlaySession {
  constructor({ onInn = () => {}, onBattle = () => {}, onStatus = () => {}, relayOnly = false, iceServers = ICE_SERVERS } = {}) {
    this.onInn = onInn;
    this.onBattle = onBattle;
    this.onStatus = onStatus;
    this.relayOnly = relayOnly;
    this.iceServers = iceServers;
    this.inn = this.frame = this.battle = this.key = null;
    this.innSync = this.innTimer = null;
    this.name = '';
    this.history = [];
    this.after = this.generation = this.positionRevision = this.retryAt = this.nextMoveAt = 0;
    this.online = this.closed = this.moving = this.leftInn = this.renamePending = false;
    this.ignoredMatch = null;
    this.status = '';
    this.visibilityChanged = () => {
      if (document.hidden) {
        if (this.battle?.host && !this.battle.practicePlayer && active(this.frame?.state?.status)) {
          this.#status('파티장 탭을 열어 두어야 원정 연결을 유지할 수 있습니다.');
        }
      } else if (this.battle) {
        this.#status('');
        this.battle.resume();
      } else {
        void this.#refreshInn();
      }
    };
    this.pageHidden = event => { if (!event.persisted) this.close(); };
  }

  async join(name) {
    if (this.closed || this.battle) return false;
    const value = typeof name === 'string' ? name.trim() : '';
    if (!validName(value)) {
      this.#status('닉네임을 1~20자로 입력하세요.');
      return false;
    }
    this.name = value;
    this.renamePending = true;
    if (!this.key) {
      this.key = newKey();
      this.innTimer = setInterval(() => void this.#refreshInn(), INN_POLL_MS);
      globalThis.document?.addEventListener('visibilitychange', this.visibilityChanged);
      globalThis.addEventListener?.('pagehide', this.pageHidden);
    }
    return this.#refreshInn('enter');
  }

  rename(name) { return this.join(name); }

  moveInn(dx, dy) {
    if (!this.online || this.battle || this.moving || now() < this.nextMoveAt ||
        !Number.isInteger(dx) || !Number.isInteger(dy) || Math.max(Math.abs(dx), Math.abs(dy)) !== 1) return false;
    const self = this.inn?.people.find(person => person.id === this.inn.selfId);
    if (!self) return false;
    const x = self.x + dx;
    const y = self.y + dy;
    const map = this.inn.map;
    if (!walkable(map, x, y) || (dx && dy && (!walkable(map, x, self.y) || !walkable(map, self.x, y)))) return false;
    this.moving = true;
    this.nextMoveAt = now() + MOVE_MS;
    const key = this.key;
    void request(INN + 'move', key, 'POST', { x, y }).then(() => {
      if (key !== this.key || this.battle || this.closed || this.inn?.selfId !== self.id) return;
      this.positionRevision++;
      this.inn = { ...this.inn, people: this.inn.people.map(person => person.id === self.id ? { ...person, x, y } : person) };
      this.onInn(this.inn);
    }, error => {
      if (key === this.key && !this.closed && !this.battle) this.#innActionError(error);
    }).finally(() => { if (key === this.key) this.moving = false; });
    return true;
  }

  chat(message) { return this.#innAction('chat', 'POST', { message }); }
  drink() { return this.#innAction('drink', 'POST'); }
  match(targetId) { return this.#innAction('match', 'PUT', targetId ? { queued: true, targetId } : { queued: true }); }
  cancelMatch() { return this.#innAction('match', 'PUT', { queued: false }); }

  practice() {
    if (this.closed || this.battle || !this.name) return false;
    const player = this.inn?.people.find(person => person.id === this.inn.selfId) ?? { id: crypto.randomUUID(), name: this.name };
    this.#beginBattle(null, player);
    return true;
  }

  action(type, dx = 0, dy = 0, target = null) {
    const action = normalizeAction(type, dx, dy, target);
    return !!action && !!this.battle?.action(action);
  }

  async returnToInn() {
    if (!this.battle || this.closed) return false;
    const battle = this.battle;
    battle.close();
    this.battle = this.frame = null;
    this.generation++;
    this.onBattle(null);
    if (this.leftInn) {
      this.key = newKey();
      this.leftInn = this.moving = false;
      this.nextMoveAt = this.retryAt = this.after = 0;
      this.history = [];
    } else if (battle.matchId) {
      await this.cancelMatch();
    }
    return this.#refreshInn('sync');
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.innTimer);
    globalThis.document?.removeEventListener('visibilitychange', this.visibilityChanged);
    globalThis.removeEventListener?.('pagehide', this.pageHidden);
    this.battle?.close();
    if (this.key && !this.leftInn) this.#leaveInn();
  }

  async #refreshInn(force) {
    if (this.closed || this.battle || !this.key || now() < this.retryAt) return false;
    if (this.innSync) {
      if (!force) return this.innSync;
      await this.innSync;
      return this.#refreshInn(force);
    }
    const key = this.key;
    const generation = this.generation;
    const positionRevision = this.positionRevision;
    const enter = force === 'enter' || !this.inn || this.renamePending;
    const name = this.name;
    const wasOnline = this.online;
    this.innSync = request(INN + (enter ? 'session' : 'sync'), key, enter ? 'PUT' : 'POST',
      enter ? { name, after: this.after, dungeonProtocol: 5 } : { after: this.after }).then(fresh => {
      if (this.closed || this.battle || generation !== this.generation) return false;
      if (enter && name === this.name) this.renamePending = false;
      if (positionRevision !== this.positionRevision && fresh.selfId === this.inn?.selfId) {
        const self = this.inn.people.find(person => person.id === fresh.selfId);
        if (self) fresh.people = fresh.people.map(person => person.id === self.id ? { ...person, x: self.x, y: self.y } : person);
      }
      this.history = [...this.history, ...fresh.events.filter(event => event.id > this.after)].slice(-60);
      this.after = fresh.after;
      this.inn = { ...fresh, events: this.history };
      this.online = true;
      if (enter || !wasOnline) this.#status('');
      this.onInn(this.inn);
      if (fresh.match && fresh.match.id !== this.ignoredMatch) this.#beginBattle(fresh.match.id, null);
      return true;
    }, error => {
      if (!this.closed && !this.battle && generation === this.generation) this.#innSyncError(error);
      return false;
    }).finally(() => { this.innSync = null; });
    return this.innSync;
  }

  #innSyncError(error) {
    this.online = false;
    if (error.status === 404) {
      this.inn = null;
      this.history = [];
      this.after = 0;
      this.#status('여관에 다시 입장하는 중…');
    } else if (error.code === 'rate_limited') {
      this.retryAt = now() + 60000;
      this.#status('접속 요청이 많습니다. 잠시 뒤 다시 연결합니다.');
    } else if (error.code === 'inn_full') {
      this.retryAt = now() + 5000;
      this.#status('여관이 가득 찼습니다. 자리가 나면 입장합니다.');
    } else {
      this.#status('여관 서버에 다시 연결하는 중…');
    }
  }

  async #innAction(path, method, body) {
    if (!this.online || this.battle || this.closed) return false;
    const key = this.key;
    try {
      await request(INN + path, key, method, body);
      if (this.key === key && !this.closed && !this.battle) {
        this.#status('');
        await this.#refreshInn('sync');
      }
      return true;
    } catch (error) {
      if (this.key === key && !this.closed && !this.battle) this.#innActionError(error);
      return false;
    }
  }

  #innActionError(error) {
    if (!(error instanceof ApiError)) this.#innSyncError(error);
    else if (error.code === 'partner_unavailable') this.#status('이 모험가는 지금 참가할 수 없습니다.');
    else if (error.code === 'activity_rate_limited') this.#status('잠시 뒤 다시 시도해 주세요.');
    else if (error.code === 'invalid_message') this.#status('대화는 한 줄, 160자까지 보낼 수 있습니다.');
    else if (error.code === 'session_ended') this.#innSyncError(error);
    else if (error.code !== 'move_rejected') this.#status('여관 요청을 처리하지 못했습니다.');
  }

  #beginBattle(matchId, practicePlayer) {
    this.ignoredMatch = matchId;
    this.leftInn = false;
    this.generation++;
    this.#status(matchId ? '파티 던전에 들어가는 중…' : '길잡이와 던전에 들어가는 중…');
    const battle = new BattleSession(this.key, matchId, practicePlayer, this.relayOnly, this.iceServers,
      frame => this.#battleFrame(battle, frame));
    this.battle = battle;
    battle.start();
  }

  #battleFrame(battle, frame) {
    if (this.closed || battle !== this.battle) return;
    if (!frame.state) {
      void this.returnToInn().then(() => { if (!this.closed && !this.battle) this.#status(frame.notice); });
      return;
    }
    if (!this.leftInn && !frame.state.waitingForPartner && frame.state.status === 'active') {
      this.leftInn = true;
      this.online = false;
      this.inn = null;
      this.#leaveInn();
      this.#status('');
    }
    this.frame = frame;
    this.onBattle(frame);
  }

  #leaveInn() { void request(INN + 'session', this.key, 'DELETE', undefined, true).catch(() => {}); }

  #status(message) {
    if (message === this.status) return;
    this.status = message;
    this.onStatus(message);
  }
}

class BattleSession {
  constructor(key, matchId, practicePlayer, relayOnly, iceServers, listener) {
    this.key = key;
    this.matchId = matchId;
    this.practicePlayer = practicePlayer;
    this.relayOnly = relayOnly;
    this.iceServers = iceServers;
    this.listener = listener;
    this.startedAt = now();
    this.frame = this.link = this.engine = this.peer = this.channel = null;
    this.localSignal = this.relayPending = null;
    this.host = !!practicePlayer;
    this.closed = this.terminal = this.wasDirect = this.remoteApplied = false;
    this.syncInFlight = this.signalInFlight = this.relayInFlight = false;
    this.nextLinkSyncAt = this.nextSignalAt = this.nextRelayAt = this.nextHeartbeatAt = 0;
    this.nextPingAt = this.nextCommandAt = this.directSince = this.lastDirectAt = 0;
    this.lastSnapshotAt = this.lastSnapshotSequence = this.relayReceived = 0;
    this.packetSequence = this.commandSequence = 0;
    this.pending = [];
    this.unacked = [];
  }

  start() {
    this.timer = setInterval(() => this.#tick(), 25);
    if (this.practicePlayer) {
      this.engine = new HostBattle(crypto.randomUUID(), this.practicePlayer, null, now(), newSeed());
      this.#tick();
      return;
    }
    void this.#linkRequest('enter', { matchId: this.matchId }).then(link => {
      if (this.closed || this.terminal) {
        this.#leaveLink();
        return;
      }
      this.#acceptLink(link);
    }, () => this.#finish('파티 던전에 입장하지 못했습니다. 여관에서 다시 시도해 주세요.'));
  }

  action(action) {
    if (this.closed || this.terminal || !active(this.frame?.state?.status) ||
        (this.frame.state.status === 'won' && action.type === 'attack') || this.pending.length >= MAX_COMMANDS) return false;
    this.pending.push(action);
    return true;
  }

  resume() {
    this.nextLinkSyncAt = 0;
    this.#tick();
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.timer);
    this.engine?.close(now());
    if (this.#direct()) this.#sendDirect(JSON.stringify({ type: 'leave', sequence: 0 }));
    this.#closePeer();
    if (this.link) this.#leaveLink();
  }

  #tick() {
    if (this.closed || this.terminal) return;
    const time = now();
    if (!this.frame && time - this.startedAt >= ENTRY_TIMEOUT_MS) {
      this.#finish('동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.');
      return;
    }
    if (this.practicePlayer) {
      this.#advanceHost(time, false);
      return;
    }
    if (!this.link) return;
    if (this.#direct()) {
      if (!this.directSince) this.directSince = time;
      if (time - Math.max(this.directSince, this.lastDirectAt) >= DIRECT_TIMEOUT_MS) this.#closePeer();
    } else {
      this.directSince = 0;
    }
    const direct = this.#direct();
    const switched = direct !== this.wasDirect;
    if (switched) {
      this.wasDirect = direct;
      this.nextLinkSyncAt = this.nextCommandAt = this.nextPingAt = 0;
      this.relayPending = null;
      if (this.frame) this.#publish(this.frame.state, this.frame.serverTime());
    }
    if (!this.syncInFlight && time >= this.nextLinkSyncAt) this.#sync(time, direct);
    if (!this.link.paired) return;
    this.#publishSignal(time);
    if (this.host) {
      this.#advanceHost(time, switched || time >= this.nextHeartbeatAt);
    } else {
      if (direct && time >= this.nextPingAt) {
        this.#sendDirect(JSON.stringify({ type: 'ping', sequence: 0 }));
        this.nextPingAt = time + HEARTBEAT_MS;
      }
      if (this.frame && time - this.lastSnapshotAt >= HOST_TIMEOUT_MS) {
        this.#finish('파티장과의 연결이 끊겼습니다.');
        return;
      }
      this.#sendGuestCommands(time);
    }
    this.#flushRelay(time);
  }

  #sync(time, direct) {
    this.syncInFlight = true;
    this.nextLinkSyncAt = time + (direct ? DIRECT_POLL_MS : RELAY_POLL_MS);
    void this.#linkRequest('sync', { id: this.matchId }).then(link => {
      if (!this.closed && !this.terminal) this.#acceptLink(link);
    }, error => {
      if (this.closed || this.terminal) return;
      if (linkEnded(error) && !this.#direct()) this.#finish('파티 연결이 종료되었습니다.');
      else this.nextLinkSyncAt = Math.max(this.nextLinkSyncAt, now() + 350);
    }).finally(() => { this.syncInFlight = false; });
  }

  #acceptLink(link) {
    if (!link || link.id !== this.matchId || (link.role !== 'host' && link.role !== 'guest') ||
        typeof link.selfId !== 'string' || typeof link.partnerId !== 'string') {
      this.#finish('파티 연결 정보를 확인할 수 없습니다.');
      return;
    }
    if (link.closed) {
      this.#finish('동료가 원정을 종료했습니다.');
      return;
    }
    const wasPaired = this.link?.paired;
    this.link = link;
    if (!link.paired) return;
    if (!wasPaired) {
      this.host = link.role === 'host';
      if (this.host) this.engine = new HostBattle(link.id, { id: link.selfId, name: link.selfName },
        { id: link.partnerId, name: link.partnerName }, now(), newSeed());
      this.#openPeer();
    }
    if (this.peer && link.signal) this.#applySignal(link.signal);
    if (link.message && link.messageSeq > this.relayReceived) {
      this.relayReceived = link.messageSeq;
      this.#receive(link.message);
    }
  }

  #advanceHost(time, heartbeat) {
    const selfId = this.practicePlayer?.id ?? this.link.selfId;
    this.engine.advance(time);
    for (const action of this.pending.splice(0)) this.engine.input(selfId, ++this.commandSequence, action, time);
    const changed = this.frame?.state.revision !== this.engine.revision;
    if (changed) this.#publish(this.engine.snapshot(selfId, time), time);
    if (this.link && (changed || heartbeat)) {
      this.#transmit({ type: 'state', sequence: ++this.packetSequence, state: this.engine.snapshot(this.link.partnerId, time) });
      this.nextHeartbeatAt = time + HEARTBEAT_MS;
    }
  }

  #sendGuestCommands(time) {
    while (this.pending.length && this.unacked.length < MAX_COMMANDS) {
      const action = this.pending.shift();
      this.unacked.push({ sequence: ++this.commandSequence, action });
      this.nextCommandAt = 0;
    }
    if (!this.unacked.length || time < this.nextCommandAt) return;
    this.#transmit({ type: 'input', sequence: this.unacked.at(-1).sequence, commands: this.unacked });
    this.nextCommandAt = time + COMMAND_RETRY_MS;
  }

  #receive(message) {
    if (this.closed || this.terminal || !this.link?.paired || typeof message !== 'string' ||
        message.length > MAX_MESSAGE_BYTES || encoder.encode(message).byteLength > MAX_MESSAGE_BYTES) return;
    let wire;
    try { wire = JSON.parse(message); } catch { return; }
    if (!wire) return;
    if (wire.type === 'leave') {
      this.#finish('동료가 원정을 종료했습니다.');
    } else if (this.host && wire.type === 'input' && Number.isSafeInteger(wire.sequence) &&
        Array.isArray(wire.commands) && wire.commands.length > 0 && wire.commands.length <= MAX_COMMANDS) {
      const time = now();
      for (const command of wire.commands) {
        if (!command?.action || !Number.isSafeInteger(command.sequence) || command.sequence <= 0 ||
            command.sequence > wire.sequence) continue;
        this.engine.input(this.link.partnerId, command.sequence, command.action, time);
      }
      this.#advanceHost(time, false);
    } else if (!this.host && wire.type === 'state' && Number.isSafeInteger(wire.sequence) &&
        wire.sequence > this.lastSnapshotSequence && this.#validSnapshot(wire.state)) {
      this.lastSnapshotSequence = wire.sequence;
      this.lastSnapshotAt = now();
      this.unacked = this.unacked.filter(command => command.sequence > wire.state.commandAck);
      if (this.relayPending?.seq <= wire.state.commandAck) this.relayPending = null;
      const hostTime = Math.max(wire.state.now, this.frame?.serverTime() ?? 0);
      this.#publish(wire.state.revision === this.frame?.state.revision ? this.frame.state : wire.state, hostTime);
    }
  }

  #validSnapshot(state) {
    return state && state.id === this.matchId && state.selfId === this.link.selfId &&
      Number.isSafeInteger(state.revision) && state.revision >= (this.frame?.state.revision ?? 0) &&
      Number.isSafeInteger(state.commandAck) && Number.isFinite(state.now) &&
      ['active', 'won', 'lost'].includes(state.status) &&
      Array.isArray(state.map) && state.map.length > 0 && typeof state.map[0] === 'string' && state.map[0].length > 0 &&
      state.map.every(row => typeof row === 'string' && row.length === state.map[0].length) &&
      Array.isArray(state.actors) && state.actors.some(actor => actor?.id === state.selfId) && state.actors.every(actor =>
        typeof actor?.id === 'string' && typeof actor.name === 'string' && typeof actor.kind === 'string' &&
        Number.isFinite(actor.energy) && Number.isSafeInteger(actor.rations) && actor.rations >= 0) &&
      Array.isArray(state.drops) && state.drops.every(drop => typeof drop?.id === 'string' && typeof drop.item === 'string');
  }

  #openPeer() {
    if (this.relayOnly || typeof RTCPeerConnection !== 'function') return;
    try {
      const peer = new RTCPeerConnection({ iceServers: this.iceServers });
      this.peer = peer;
      this.channel = peer.createDataChannel('noxac-atb', { ordered: true, negotiated: true, id: 0 });
      this.channel.addEventListener('message', event => {
        if (this.peer !== peer || this.closed || this.terminal) return;
        this.lastDirectAt = now();
        this.#receive(event.data);
      });
      this.channel.addEventListener('open', () => this.#tick());
      peer.addEventListener('connectionstatechange', () => {
        if (this.peer === peer && (peer.connectionState === 'failed' || peer.connectionState === 'closed')) this.#closePeer();
      });
      if (this.host) void this.#prepareSignal(peer, 'offer').catch(() => { if (this.peer === peer) this.#closePeer(); });
    } catch {
      this.#closePeer();
    }
  }

  #applySignal(signal) {
    if (this.remoteApplied || signal.type !== (this.host ? 'answer' : 'offer') || typeof signal.sdp !== 'string') return;
    this.remoteApplied = true;
    const peer = this.peer;
    void peer.setRemoteDescription({ type: signal.type, sdp: signal.sdp }).then(() => {
      if (!this.host && this.peer === peer) return this.#prepareSignal(peer, 'answer');
    }).catch(() => { if (this.peer === peer) this.#closePeer(); });
  }

  async #prepareSignal(peer, type) {
    const description = type === 'offer' ? await peer.createOffer() : await peer.createAnswer();
    if (this.peer !== peer) return;
    await peer.setLocalDescription(description);
    if (peer.iceGatheringState !== 'complete') await new Promise(resolve => {
      const done = () => {
        clearTimeout(deadline);
        peer.removeEventListener('icegatheringstatechange', gathered);
        resolve();
      };
      const gathered = () => { if (peer.iceGatheringState === 'complete') done(); };
      const deadline = setTimeout(done, 8000);
      peer.addEventListener('icegatheringstatechange', gathered);
      gathered();
    });
    if (this.peer === peer && peer.localDescription?.sdp) this.localSignal = { type, sdp: peer.localDescription.sdp };
  }

  #publishSignal(time) {
    if (!this.localSignal || this.signalInFlight || time < this.nextSignalAt) return;
    const signal = this.localSignal;
    this.signalInFlight = true;
    this.nextSignalAt = time + SIGNAL_RETRY_MS;
    void this.#linkRequest('signal', { id: this.matchId, ...signal }).then(() => {
      if (this.localSignal === signal) this.localSignal = null;
    }, error => {
      if (linkEnded(error) || error.status === 400 || error.status === 403) this.#closePeer();
    }).finally(() => { this.signalInFlight = false; });
  }

  #closePeer() {
    const channel = this.channel;
    const peer = this.peer;
    this.channel = this.peer = this.localSignal = null;
    channel?.close();
    peer?.close();
  }

  #direct() { return this.channel?.readyState === 'open' && this.peer?.connectionState === 'connected'; }

  #sendDirect(message, bytes = encoder.encode(message).byteLength) {
    if (!this.#direct() || this.channel.bufferedAmount + bytes > MAX_CHANNEL_BUFFER) return false;
    try {
      this.channel.send(message);
      return true;
    } catch {
      this.#closePeer();
      return false;
    }
  }

  #transmit(wire) {
    const message = JSON.stringify(wire);
    const bytes = encoder.encode(message).byteLength;
    if (bytes > MAX_MESSAGE_BYTES) return;
    this.relayPending = this.#sendDirect(message, bytes) ? null : { seq: wire.sequence, message };
  }

  #flushRelay(time) {
    if (!this.relayPending || this.relayInFlight || time < this.nextRelayAt) return;
    const packet = this.relayPending;
    this.relayPending = null;
    this.relayInFlight = true;
    void this.#linkRequest('relay', { id: this.matchId, ...packet }).catch(error => {
      if (this.closed || this.terminal || this.#direct()) return;
      if (linkEnded(error)) this.#finish('파티 연결이 종료되었습니다.');
      else if (!this.relayPending) {
        this.relayPending = packet;
        this.nextRelayAt = now() + 250;
      }
    }).finally(() => { this.relayInFlight = false; });
  }

  #linkRequest(path, body, keepalive = false) { return request(LINK + path, this.key, 'POST', body, keepalive); }

  #leaveLink() { void this.#linkRequest('leave', { id: this.matchId }, true).catch(() => {}); }

  #publish(state, hostTime, notice = '') {
    if (this.closed) return;
    const receivedAt = now();
    this.frame = {
      state, notice,
      connection: this.practicePlayer ? 'local' : this.#direct() ? 'direct' : 'relay',
      serverTime: () => state ? hostTime + (now() - receivedAt) : 0,
    };
    this.listener(this.frame);
  }

  #finish(notice) {
    if (this.closed || this.terminal) return;
    this.terminal = true;
    clearInterval(this.timer);
    const state = this.frame?.state;
    const time = this.frame?.serverTime() ?? 0;
    this.#publish(active(state?.status) ? { ...state, now: time, status: 'closed', queued: null, inCombat: false } : state, time, notice);
    this.#closePeer();
  }
}
