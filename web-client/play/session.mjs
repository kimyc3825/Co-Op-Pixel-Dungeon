// SPDX-License-Identifier: GPL-3.0-or-later
// Inn and party networking over the WebSocket protocol in server/PROTOCOL.md.
import { isPotion, PRISON, SEWERS } from './rules.mjs';
import { loadBattleRuntime } from './runtime-loader.mjs';
import { normalizeAppearance } from './avatar-catalog.mjs';
import { loadInnLayout, localInnState, walkable } from './inn-layout.mjs';

const WIRE_VERSION = 1;
const INN_PATH = '/api/v5/inn';
const LINK_PATH = '/api/v5/link';
// 12: turn-clock party packets (6-field commands with `W` wait, 16-field snapshots). 11 was the ATB build.
const ORIGINAL_PROTOCOL = 13;
const LEGACY_PROTOCOL = 9;
export const INN_MOVE_MS = 150;
const PING_MS = 25000;
const REQUEST_TIMEOUT_MS = 8000;
const CONNECT_TIMEOUT_MS = 10000;
const RECONNECT_MS = [500, 1000, 2000, 4000, 8000];
// The server keeps a run open for 20 s after a socket drops; stop retrying a little earlier.
const LINK_RECONNECT_WINDOW_MS = 15000;
const HEARTBEAT_MS = 650;
// The host's packets are full snapshots, so only the newest matters: send at most 15 per second.
const HOST_FRAME_MS = 67;
const COMMAND_RETRY_MS = 275;
const ENTRY_TIMEOUT_MS = 20000;
const HOST_TIMEOUT_MS = 14000;
const ORIGINAL_ENTRY_TIMEOUT_MS = 150000;
const MAX_COMMANDS = 16;
const MAX_MESSAGE_BYTES = 24 * 1024;
const MAX_HISTORY = 60;
const encoder = new TextEncoder();

const now = () => Math.floor(performance.timeOrigin + performance.now());
const newKey = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const newSeed = () => crypto.getRandomValues(new Uint32Array(1))[0];

class ApiError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function socketUrl(path, params) {
  const url = new URL(path, globalThis.location?.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  for (const [name, value] of Object.entries(params)) url.searchParams.set(name, String(value));
  return url.href;
}

/** One WebSocket with request/response correlation (`ref`) and keep-alive pings. */
class Channel {
  constructor(url, { onOpen, onFrame, onClose }) {
    this.refs = new Map();
    this.nextRef = 0;
    this.ws = new WebSocket(url);
    // A handshake that hangs would otherwise never report a close.
    const connectTimer = setTimeout(() => { if (this.ws.readyState === WebSocket.CONNECTING) this.ws.close(); }, CONNECT_TIMEOUT_MS);
    this.ws.addEventListener('open', () => {
      clearTimeout(connectTimer);
      // The server answers the literal `ping` without waking its Durable Object.
      this.pinger = setInterval(() => this.sendText('ping'), PING_MS);
      onOpen();
    });
    this.ws.addEventListener('message', event => {
      if (typeof event.data !== 'string' || event.data === 'pong') return;
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (!frame || typeof frame.t !== 'string') return;
      const pending = frame.ref != null && (frame.t === 'ok' || frame.t === 'error') ? this.refs.get(frame.ref) : null;
      if (pending) {
        this.refs.delete(frame.ref);
        clearTimeout(pending.timer);
        if (frame.t === 'ok') pending.resolve(frame);
        else pending.reject(new ApiError(frame.code));
        return;
      }
      onFrame(frame);
    });
    this.ws.addEventListener('close', event => {
      clearTimeout(connectTimer);
      clearInterval(this.pinger);
      for (const pending of this.refs.values()) {
        clearTimeout(pending.timer);
        pending.reject(new ApiError('offline'));
      }
      this.refs.clear();
      onClose(event.code, event.reason);
    });
  }

  get open() { return this.ws.readyState === WebSocket.OPEN; }

  sendText(text) {
    if (!this.open) return false;
    try {
      this.ws.send(text);
      return true;
    } catch {
      return false;
    }
  }

  send(frame) { return this.sendText(JSON.stringify(frame)); }

  request(frame) {
    const ref = ++this.nextRef;
    return new Promise((resolve, reject) => {
      if (!this.send({ ...frame, ref })) {
        reject(new ApiError('offline'));
        return;
      }
      const timer = setTimeout(() => {
        this.refs.delete(ref);
        reject(new ApiError('timeout'));
      }, REQUEST_TIMEOUT_MS);
      this.refs.set(ref, { resolve, reject, timer });
    });
  }

  close(code = 1000, reason = '') {
    try { this.ws.close(code, reason); } catch { /* closing */ }
  }
}

const active = status => status === 'active' || status === 'won';
const validName = value => value.length > 0 && [...value].length <= 20 && !/[\p{C}\p{Zl}\p{Zp}]/u.test(value);
const attackResults = new Set(['hit', 'out_of_reach', 'blocked', 'target_gone', 'miss', 'ally_blocked']);
const mapCell = (map, x, y) => Number.isSafeInteger(x) && Number.isSafeInteger(y) && typeof map[y]?.[x] === 'string';
const reconnectDelay = attempt => RECONNECT_MS[Math.min(attempt, RECONNECT_MS.length - 1)] * (0.75 + Math.random() / 2);

function normalizeAction(type, dx, dy, target) {
  if (type === 'move' && Number.isInteger(dx) && Number.isInteger(dy) && Math.max(Math.abs(dx), Math.abs(dy)) === 1) {
    return { type, dx, dy, target: null };
  }
  if (type === 'attack' && typeof target === 'string' && target.length > 0 && target.length <= 80) {
    return { type, dx: 0, dy: 0, target };
  }
  if (type === 'shoot' && typeof target === 'string' && /^\d{1,3},\d{1,3}$/.test(target)) {
    return { type, dx: 0, dy: 0, target };
  }
  if (type === 'eat') return { type, dx: 0, dy: 0, target: null };
  if (type === 'use-item' && isPotion(target)) return { type, dx: 0, dy: 0, target };
  if (type === 'pause' && (target === 'begin' || target === 'resume')) return { type, dx: 0, dy: 0, target };
  if (type === 'chat' && typeof target === 'string') {
    const message = target.trim();
    if (message && [...message].length <= 160) return { type, dx: 0, dy: 0, target: message };
  }
  return null;
}

function maySend(action, state) {
  if (!state || state.status === 'closed') return false;
  if (action.type === 'chat') return true;
  if (!active(state.status)) return false;
  if (action.type === 'pause') return true;
  return !(state.pausedUntil > 0) && (state.status !== 'won' ||
    action.type === 'move' || action.type === 'eat' || action.type === 'use-item');
}

function validInventory(inventory) {
  return inventory == null || Array.isArray(inventory) && inventory.length <= 4 && inventory.every(entry =>
    Number.isSafeInteger(entry?.amount) && entry.amount > 0 &&
    (entry.item === 'ration' || entry.item === 'gold' || isPotion(entry.item))) &&
    new Set(inventory.map(entry => entry.item)).size === inventory.length;
}

function validKnowledge(knowledge) {
  return knowledge == null || Array.isArray(knowledge) && knowledge.length <= 2 && knowledge.every(entry =>
    isPotion(entry?.item) && (entry.effect === 'healing' || entry.effect === 'energy')) &&
    new Set(knowledge.map(entry => entry.item)).size === knowledge.length &&
    new Set(knowledge.map(entry => entry.effect)).size === knowledge.length;
}

export class PlaySession {
  constructor({ onInn = () => {}, onBattle = () => {}, onStatus = () => {}, onLoading = () => {},
    onOriginalParty = null, runtimeLoader = loadBattleRuntime } = {}) {
    this.onInn = onInn;
    this.onBattle = onBattle;
    this.onStatus = onStatus;
    this.onLoading = onLoading;
    this.onOriginalParty = onOriginalParty;
    this.runtimeLoader = runtimeLoader;
    this.inn = this.frame = this.battle = this.key = this.channel = this.layout = this.position = null;
    this.name = '';
    this.appearance = normalizeAppearance();
    this.theme = SEWERS;
    this.friendlyFire = false;
    this.history = [];
    this.shard = null;
    this.welcomeWaiters = [];
    this.reconnectAttempt = this.channelGeneration = this.nextMoveAt = 0;
    this.reconnectTimer = this.retryAfter = null;
    this.holdUntil = 0;
    this.online = this.closed = this.leftInn = this.innSuspended = this.stopped = false;
    this.ignoredMatch = null;
    this.status = '';
    this.visibilityChanged = () => {
      if (document.hidden) {
        if (this.battle?.host && !this.battle.localPlayer && active(this.frame?.state?.status)) {
          this.#status('파티장 탭을 열어 두어야 원정 연결을 유지할 수 있습니다.');
        }
      } else {
        if (this.battle) {
          this.#status('');
          this.battle.resume();
        }
        // Reconnect at once, unless the server asked for a wait (full, rate limited, per-address cap).
        if (!this.channel && this.key && !this.stopped && now() >= this.holdUntil) this.#openInn();
      }
    };
    this.pageHidden = event => { if (!event.persisted) this.close(); };
  }

  async join(name, appearance = this.appearance) {
    if (this.closed || this.battle || this.innSuspended) return false;
    const value = typeof name === 'string' ? name.trim() : '';
    if (!validName(value)) {
      this.#status('닉네임을 1~20자로 입력하세요.');
      return false;
    }
    this.name = value;
    this.appearance = normalizeAppearance(appearance);
    if (!this.key) {
      this.key = newKey();
      globalThis.document?.addEventListener('visibilitychange', this.visibilityChanged);
      globalThis.addEventListener?.('pagehide', this.pageHidden);
    }
    // A connected player renames in place; otherwise the next hello carries the profile.
    if (this.online && this.channel?.open) return this.#innAction({ t: 'profile', name: this.name, appearance: this.appearance });
    this.stopped = false;
    const welcomed = this.#nextWelcome();
    if (!this.channel) this.#openInn();
    return welcomed;
  }

  rename(name, appearance) { return this.join(name, appearance); }

  setTheme(theme) {
    if (this.closed || this.battle || this.innSuspended || this.inn?.queued || this.inn?.match ||
        (theme !== SEWERS && theme !== PRISON)) return false;
    this.theme = theme;
    return true;
  }

  setFriendlyFire(enabled) {
    if (this.closed || this.battle || this.innSuspended || this.inn?.queued || this.inn?.match || typeof enabled !== 'boolean') return false;
    this.friendlyFire = enabled;
    return true;
  }

  /** True while the last predicted step is still inside its walk cadence. */
  get innMovePending() { return now() < this.nextMoveAt; }

  moveInn(dx, dy) {
    if (!this.online || this.closed || this.battle || this.innSuspended || !this.channel?.open ||
        !Number.isInteger(dx) || !Number.isInteger(dy) || Math.max(Math.abs(dx), Math.abs(dy)) !== 1) return 'blocked';
    const self = this.inn?.people.find(person => person.id === this.inn.selfId);
    if (!self) return 'blocked';
    const x = self.x + dx;
    const y = self.y + dy;
    const map = this.inn.map;
    if (!walkable(map, x, y) || (dx && dy && !walkable(map, x, self.y) && !walkable(map, self.x, y))) return 'blocked';
    const time = now();
    if (time < this.nextMoveAt) return 'waiting';
    // Predict locally; the server applies steps in order and answers a rejected one with `you`.
    this.nextMoveAt = time + INN_MOVE_MS;
    this.#showPerson({ ...self, x, y });
    this.channel.send({ t: 'move', x, y });
    return 'accepted';
  }

  chat(message) { return this.#innAction({ t: 'chat', text: message }); }
  drink() { return this.#innAction({ t: 'drink' }); }
  talkToNpc(npcId) {
    // A dropped notification must not interrupt a locally displayed conversation.
    if (this.inn?.layoutId && this.online && !this.battle && !this.closed && !this.innSuspended) {
      this.channel?.send({ t: 'interact', npcId });
    }
  }
  prepare() { return this.#innAction({ t: 'prepare' }); }
  async usePreparation(id) {
    try {
      if (!this.channel?.open) throw new ApiError('offline');
      await this.channel.request({ t: 'use_preparation', id });
    } catch {
      // Keep a failed consumption from carrying over into another expedition: take a new identity,
      // and reconnect so the inn (and later the party link) knows this player by the new key.
      this.key = newKey();
      this.channel?.close(1000, 'new_key');
    }
  }
  match(targetId) {
    const options = { friendlyFire: this.friendlyFire, theme: this.theme };
    return this.#innAction(targetId ? { t: 'join', targetId, ...options } : { t: 'queue', ...options });
  }
  cancelMatch() { return this.#innAction({ t: 'cancel' }); }

  practice() {
    return this.#startLocalBattle('practice');
  }

  solo() {
    return this.#startLocalBattle('solo');
  }

  #startLocalBattle(localMode) {
    if (this.closed || this.battle || this.innSuspended || !this.name || this.inn?.queued || this.inn?.match) return false;
    const player = { id: this.inn?.selfId ?? crypto.randomUUID(), name: this.name, appearance: this.appearance };
    this.#beginBattle({ localPlayer: player, localMode });
    return true;
  }

  action(type, dx = 0, dy = 0, target = null) {
    if (type === 'pause' && target === null) target = this.frame?.state?.pausedUntil > 0 ? 'resume' : 'begin';
    const action = normalizeAction(type, dx, dy, target);
    return !!action && !!this.battle?.action(action);
  }

  originalPartyReady() { this.battle?.originalReady(); }
  sendOriginalParty(data) { this.battle?.sendOriginal(data); }

  /** Hides this player from the inn while a solo expedition runs; the socket stays open. */
  async suspendInn() {
    if (this.closed || this.battle || this.innSuspended || this.inn?.match) return false;
    this.innSuspended = true;
    if (!this.key) return true;
    if (this.channel?.open && this.online) {
      try {
        await this.channel.request({ t: 'away' });
      } catch {
        this.innSuspended = false;
        return false;
      }
    }
    if (this.closed) return false;
    this.leftInn = true;
    this.online = false;
    this.inn = null;
    this.onInn(null);
    return true;
  }

  async resumeInn() {
    if (this.closed || !this.innSuspended) return false;
    this.innSuspended = false;
    if (!this.key) return true;
    this.ignoredMatch = null;
    return this.#enterInn();
  }

  async returnToInn() {
    if (!this.battle || this.closed) return false;
    const battle = this.battle;
    battle.close();
    this.battle = this.frame = null;
    this.onBattle(null);
    if (battle.matchId) this.ignoredMatch = battle.matchId;
    return this.#enterInn();
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.reconnectTimer);
    globalThis.document?.removeEventListener('visibilitychange', this.visibilityChanged);
    globalThis.removeEventListener?.('pagehide', this.pageHidden);
    this.battle?.close();
    this.channel?.close(1000, 'bye');
    this.#settleWelcome(false);
  }

  // ---- inn socket ----

  #present() { return !this.leftInn && !this.innSuspended; }

  #spawn() {
    const spawns = this.layout?.spawns ?? [];
    return spawns.length ? spawns[newSeed() % spawns.length] : { x: 1, y: 1 };
  }

  /** Shows the player in the inn again at a fresh spawn point. */
  async #enterInn() {
    this.leftInn = false;
    this.nextMoveAt = 0;
    this.position = null;
    if (this.stopped) return false;
    const welcomed = this.#nextWelcome();
    if (this.channel?.open) {
      this.position = this.#spawn();
      const channel = this.channel;
      channel.request({ t: 'enter', ...this.position }).catch(error => {
        if (error.code === 'offline' || channel !== this.channel) return;
        // Still away on the server: reconnect, and the hello brings the player back in.
        this.#innActionError(error);
        channel.close(1000, 'retry_enter');
      });
    } else if (!this.channel) {
      this.#openInn();
    }
    return welcomed;
  }

  #nextWelcome() {
    return new Promise(resolve => {
      const finish = value => {
        clearTimeout(timer);
        this.welcomeWaiters = this.welcomeWaiters.filter(waiter => waiter !== finish);
        resolve(value);
      };
      const timer = setTimeout(() => finish(false), REQUEST_TIMEOUT_MS + 4000);
      this.welcomeWaiters.push(finish);
    });
  }

  #settleWelcome(value) {
    for (const waiter of [...this.welcomeWaiters]) waiter(value);
  }

  async #openInn() {
    if (this.closed || this.stopped || this.channel || !this.key) return;
    clearTimeout(this.reconnectTimer);
    const generation = ++this.channelGeneration;
    let layout;
    try {
      layout = await loadInnLayout();
    } catch {
      if (generation === this.channelGeneration) this.#scheduleReconnect('여관 서버에 다시 연결하는 중…');
      return;
    }
    if (this.closed || this.channel || generation !== this.channelGeneration) return;
    this.layout = layout;
    const params = { layout: layout.id };
    if (Number.isInteger(this.shard)) params.shard = this.shard;
    const channel = new Channel(socketUrl(INN_PATH, params), {
      onOpen: () => {
        this.position ??= this.#spawn();
        const queued = Boolean(this.inn?.queued);
        channel.send({ t: 'hello', v: WIRE_VERSION, key: this.key, name: this.name, appearance: this.appearance,
          dungeonProtocol: this.onOriginalParty ? ORIGINAL_PROTOCOL : LEGACY_PROTOCOL, ...this.position,
          present: this.#present(), queued, friendlyFire: this.friendlyFire, theme: this.theme });
      },
      onFrame: frame => { if (this.channel === channel) this.#innFrame(frame); },
      onClose: code => { if (this.channel === channel) this.#innClosed(code); },
    });
    this.channel = channel;
  }

  /** `required`: the server asked for this wait, so nothing may shorten it. */
  #scheduleReconnect(message, delay = reconnectDelay(this.reconnectAttempt++), required = false) {
    if (this.closed || this.stopped) return;
    this.holdUntil = required ? now() + delay : 0;
    this.#status(message);
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => this.#openInn(), delay);
  }

  #innClosed(code) {
    this.channel = null;
    this.online = false;
    this.#settleWelcome(false);
    if (this.closed) return;
    const retryAfter = this.retryAfter;
    this.retryAfter = null;
    if (code === 4001) {
      this.stopped = true;
      this.#status('새 버전이 나왔습니다. 페이지를 새로 고쳐 주세요.');
    } else if (code === 4009) {
      this.stopped = true;
      this.#status('다른 창에서 여관에 접속했습니다.');
    } else if (code === 4000) {
      // The server rejected what this client sent; retrying would only repeat it.
      this.stopped = true;
      this.#status('여관 서버와 통신하지 못했습니다. 페이지를 새로 고쳐 주세요.');
    } else if (code === 4013) {
      this.#scheduleReconnect('여관이 가득 찼습니다. 자리가 나면 입장합니다.',
        (Number.isFinite(retryAfter) ? retryAfter : 30) * 1000 * (0.75 + Math.random() / 2), true);
    } else if (code === 4029) {
      this.#scheduleReconnect('같은 곳에서 접속한 모험가가 많습니다. 잠시 뒤 다시 연결합니다.', 30000, true);
    } else if (code === 4008) {
      this.#scheduleReconnect('접속 요청이 많습니다. 잠시 뒤 다시 연결합니다.', 10000, true);
    } else {
      this.#scheduleReconnect('여관 서버에 다시 연결하는 중…');
    }
    if (!this.battle && this.inn) this.onInn(this.inn);
  }

  #innFrame(frame) {
    if (frame.t === 'welcome') {
      this.#welcome(frame);
      return;
    }
    if (frame.t === 'error') {
      if (frame.code === 'match_failed' && !this.battle) this.#status('동료와 연결하지 못했습니다. 다시 시도해 주세요.');
      if (frame.code === 'inn_full') this.retryAfter = frame.retryAfter;
      return;
    }
    // Inn updates are not shown during an expedition; the next welcome carries a fresh roster.
    if (!this.inn || this.battle || !this.online) return;
    switch (frame.t) {
      case 'person': return this.#upsertPerson(frame.person);
      case 'gone': return this.#setInn({ ...this.inn, people: this.inn.people.filter(person => person.id !== frame.id) });
      case 'moved': {
        const person = this.inn.people.find(candidate => candidate.id === frame.id);
        if (person && frame.id !== this.inn.selfId && walkable(this.inn.map, frame.x, frame.y)) {
          this.#showPerson({ ...person, x: frame.x, y: frame.y });
        }
        return;
      }
      case 'you': {
        const self = this.inn.people.find(candidate => candidate.id === this.inn.selfId);
        if (self && walkable(this.inn.map, frame.x, frame.y)) this.#showPerson({ ...self, x: frame.x, y: frame.y });
        return;
      }
      case 'event':
        this.#addEvents([frame.event]);
        return this.#setInn({ ...this.inn, now: frame.now, events: this.history, after: this.history.at(-1)?.id ?? 0 });
      case 'preparation': return this.#setInn({ ...this.inn, preparation: frame.preparation ?? null });
      case 'matched':
        this.#setInn({ ...this.inn, match: frame.match, queued: false });
        if (frame.match && frame.match.id !== this.ignoredMatch) {
          this.#beginBattle({ matchId: frame.match.id, matchRole: frame.match.role });
        }
    }
  }

  #welcome(frame) {
    this.shard = frame.shard;
    if (!frame.present || !this.layout || this.battle) {
      // Reconnected while away (in a dungeon or a solo expedition), or entering a party run.
      this.reconnectAttempt = 0;
      this.online = false;
      this.#status('');
      return;
    }
    let fresh;
    try {
      fresh = localInnState({ ...frame, events: [] }, this.layout);
    } catch {
      // Keep the back-off growing: an unusable state must not turn into a fast reconnect loop.
      this.channel?.close(1000, 'invalid_state');
      return;
    }
    this.reconnectAttempt = 0;
    // A welcome can still name a match this client already finished or gave up (its old socket
    // was taken over before the server noticed it was gone). Leave it behind with `enter`.
    const staleMatch = Boolean(frame.match) && frame.match.id === this.ignoredMatch;
    if (staleMatch) fresh.match = null;
    this.history = [];
    this.#addEvents(frame.events ?? []);
    const self = fresh.people.find(person => person.id === fresh.selfId);
    this.position = { x: self.x, y: self.y };
    this.online = true;
    this.#status('');
    this.inn = { ...fresh, events: this.history, queued: Boolean(frame.queued) };
    this.onInn(this.inn);
    this.#settleWelcome(true);
    if (staleMatch) {
      this.channel?.request({ t: 'enter', ...this.position }).catch(() => this.channel?.close(1000, 'retry_enter'));
    } else if (frame.match && !this.battle) {
      this.#beginBattle({ matchId: frame.match.id, matchRole: frame.match.role });
    }
  }

  #addEvents(events) {
    const known = new Set(this.history.map(event => event.id));
    const fresh = events.filter(event => Number.isSafeInteger(event?.id) && !known.has(event.id));
    this.history = [...this.history, ...fresh].sort((a, b) => a.id - b.id).slice(-MAX_HISTORY);
  }

  #upsertPerson(person) {
    if (!person || typeof person.id !== 'string') return;
    const self = person.id === this.inn.selfId;
    const previous = this.inn.people.find(candidate => candidate.id === person.id);
    // Keep the locally predicted position of this player.
    const updated = self && previous ? { ...person, x: previous.x, y: previous.y } : person;
    if (!self && !walkable(this.inn.map, updated.x, updated.y)) return;
    const people = previous
      ? this.inn.people.map(candidate => candidate.id === person.id ? updated : candidate)
      : [...this.inn.people, updated];
    this.#setInn({ ...this.inn, people, queued: self ? Boolean(person.queued) : this.inn.queued });
  }

  #showPerson(updated) {
    if (updated.id === this.inn?.selfId) this.position = { x: updated.x, y: updated.y };
    this.inn = { ...this.inn, people: this.inn.people.map(person => person.id === updated.id ? updated : person) };
    this.onInn(this.inn, updated);
  }

  #setInn(inn) {
    this.inn = inn;
    this.onInn(inn);
  }

  async #innAction(frame) {
    if (!this.online || this.battle || this.closed || this.innSuspended || !this.channel?.open) return false;
    try {
      await this.channel.request(frame);
      if (!this.closed && !this.battle && !this.innSuspended) this.#status('');
      return true;
    } catch (error) {
      if (!this.closed && !this.battle && !this.innSuspended) this.#innActionError(error);
      return false;
    }
  }

  #innActionError(error) {
    const code = error?.code;
    if (code === 'partner_unavailable') this.#status('이 모험가는 지금 참가할 수 없습니다.');
    else if (code === 'protocol_mismatch') this.#status('이 모험가와는 게임 버전이 달라 함께할 수 없습니다.');
    else if (code === 'activity_rate_limited') this.#status('잠시 뒤 다시 시도해 주세요.');
    else if (code === 'room_busy') this.#status('여관이 붐빕니다. 잠시 뒤 다시 보내 주세요.');
    else if (code === 'preparation_used') this.#status('다음 원정 준비는 여관에 다시 들어온 뒤에 할 수 있습니다.');
    else if (code === 'invalid_message') this.#status('대화는 한 줄, 160자까지 보낼 수 있습니다.');
    else if (code === 'invalid_profile') this.#status('닉네임을 1~20자로 입력하세요.');
    else if (code === 'offline' || code === 'timeout') this.#status('여관 서버에 다시 연결하는 중…');
    else if (code !== 'move_rejected') this.#status('여관 요청을 처리하지 못했습니다.');
  }

  #beginBattle({ matchId = null, matchRole = null, localPlayer = null, localMode = null }) {
    this.ignoredMatch = matchId;
    this.leftInn = false;
    const battle = new BattleSession({ key: this.key, matchId, matchRole, localPlayer, localMode,
      theme: this.theme, friendlyFire: this.friendlyFire,
      runtimeLoader: this.runtimeLoader, onLoading: this.onLoading, listener: frame => this.#battleFrame(battle, frame),
      originalParty: matchId && this.onOriginalParty ? event => this.#originalPartyEvent(battle, event) : null });
    this.battle = battle;
    this.#status(matchId ? '파티 던전에 들어가는 중…' :
      localMode === 'practice' ? '길잡이와 던전에 들어가는 중…' : '혼자 던전에 들어가는 중…');
    battle.start();
  }

  #battleFrame(battle, frame) {
    if (this.closed || battle !== this.battle) return;
    if (!frame.state) {
      void this.returnToInn().then(() => { if (!this.closed && !this.battle) this.#status(frame.notice); });
      return;
    }
    if (!this.leftInn && !frame.state.waitingForPartner && frame.state.status === 'active') {
      this.#leaveInn();
      this.#status('');
    }
    this.frame = frame;
    this.onBattle(frame);
  }

  #originalPartyEvent(battle, event) {
    if (this.closed || battle !== this.battle) return;
    if (event.type === 'paired' && !this.leftInn) this.#leaveInn();
    const handled = this.onOriginalParty(event);
    if (event.type === 'ended' && !handled) void this.leaveOriginalParty(event.notice);
  }

  async leaveOriginalParty(notice) {
    await this.returnToInn();
    if (notice && !this.closed && !this.battle) this.#status(notice);
  }

  /** Hides this player from the inn for the expedition; the socket stays open for the return. */
  #leaveInn() {
    this.leftInn = true;
    this.online = false;
    this.inn = null;
    this.channel?.send({ t: 'away' });
  }

  #status(message) {
    if (message === this.status) return;
    this.status = message;
    this.onStatus(message);
  }
}

class BattleSession {
  constructor({ key, matchId, matchRole, localPlayer, localMode, theme, friendlyFire,
    runtimeLoader, onLoading, listener, originalParty }) {
    this.key = key;
    this.matchId = matchId;
    this.matchRole = matchRole;
    this.localPlayer = localPlayer;
    this.localMode = localMode;
    this.theme = theme;
    this.friendlyFire = friendlyFire;
    this.runtimeLoader = runtimeLoader;
    this.onLoading = onLoading;
    this.listener = listener;
    this.originalParty = originalParty;
    this.startedAt = now();
    this.frame = this.link = this.engine = this.runtime = this.channel = null;
    this.loadAbort = new AbortController();
    this.loadingVersion = null;
    this.loadingFinished = false;
    this.host = Boolean(localPlayer) || matchRole === 'host';
    this.closed = this.terminal = false;
    this.nextHeartbeatAt = this.nextCommandAt = this.lastSnapshotAt = this.lastSnapshotSequence = 0;
    this.packetSequence = this.commandSequence = this.reconnectAttempt = this.lostAt = 0;
    this.reconnectTimer = this.hostTimer = this.hostPending = null;
    this.hostSentAt = 0;
    this.pending = [];
    this.unacked = [];
    if (originalParty) {
      this.original = { launched: false, ready: false, peerReady: false, paired: false,
        deadline: this.startedAt + ENTRY_TIMEOUT_MS };
    }
  }

  start() {
    this.timer = setInterval(() => this.#tick(), this.original ? 250 : 25);
    if (this.originalParty) this.onLoading({ phase: 'loading', mode: 'party' });
    else void this.runtimeLoader({ simulate: this.host, signal: this.loadAbort.signal, onLoading: update => {
      if (this.loadingFinished) return;
      this.loadingVersion = update.version ?? this.loadingVersion;
      this.onLoading({ ...update, mode: this.localMode, practice: this.localMode === 'practice', cancelled: false });
    } }).then(runtime => {
      if (this.closed || this.terminal) return;
      this.runtime = runtime;
      this.#createEngine();
      this.#settleLoading('ready');
      if (this.frame) this.#publish(this.frame.state, this.frame.wallTime(), this.frame.notice);
      this.#tick();
    }).catch(() => this.#finish('던전 데이터를 불러오지 못했습니다. 다시 시도해 주세요.'));
    if (this.localPlayer) return;
    this.#connect();
  }

  action(action) {
    if (this.closed || this.terminal || !maySend(action, this.frame?.state) || this.pending.length >= MAX_COMMANDS) return false;
    this.pending.push(action);
    return true;
  }

  originalReady() {
    if (!this.original?.launched || this.closed || this.terminal) return;
    this.original.ready = true;
    this.original.deadline = now() + ORIGINAL_ENTRY_TIMEOUT_MS;
    this.channel?.send({ t: 'ready' });
  }

  sendOriginal(data) {
    if (!this.original?.ready || !this.original.peerReady || this.closed || this.terminal || typeof data !== 'string' || !data ||
        encoder.encode(data).byteLength > MAX_MESSAGE_BYTES) return;
    this.#sendData(data);
  }

  /**
   * The guest's frames are commands: every one is sent at once, in order. The host's frames are
   * full snapshots: at most one per HOST_FRAME_MS, and a newer one replaces one still waiting.
   */
  #sendData(data) {
    if (!this.host) {
      this.channel?.send({ t: 'data', d: data });
      return;
    }
    this.hostPending = data;
    const wait = this.hostSentAt + HOST_FRAME_MS - now();
    if (wait <= 0) this.#flushHost();
    else this.hostTimer ??= setTimeout(() => {
      this.hostTimer = null;
      this.#flushHost();
    }, wait);
  }

  #flushHost() {
    if (this.hostPending === null || this.closed || this.terminal) return;
    const data = this.hostPending;
    this.hostPending = null;
    this.hostSentAt = now();
    this.channel?.send({ t: 'data', d: data });
  }

  resume() { this.#tick(); }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.timer);
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.hostTimer);
    this.#settleLoading('failed', true);
    this.engine?.close(now());
    this.#leaveLink();
  }

  // ---- link socket ----

  #connect() {
    if (this.closed || this.terminal || this.channel) return;
    const channel = new Channel(socketUrl(LINK_PATH, { id: this.matchId }), {
      onOpen: () => channel.send({ t: 'hello', v: WIRE_VERSION, key: this.key }),
      onFrame: frame => { if (this.channel === channel) this.#linkFrame(frame); },
      onClose: code => { if (this.channel === channel) this.#linkClosed(code); },
    });
    this.channel = channel;
  }

  #linkClosed(code) {
    this.channel = null;
    if (this.closed || this.terminal) return;
    if (code === 4004 || code === 4010) {
      this.#finish('파티 연결이 종료되었습니다.');
    } else if (code === 4009) {
      this.#finish('다른 창에서 원정에 접속했습니다.');
    } else if (code === 4000 || code === 4001 || code === 4003) {
      this.#finish('파티 연결 정보를 확인할 수 없습니다.');
    } else if (code === 4008 || code === 1003 || code === 1009) {
      // The server ended the run for both sides.
      this.#finish('파티 연결이 종료되었습니다.');
    } else {
      this.lostAt ||= now();
      if (now() - this.lostAt >= LINK_RECONNECT_WINDOW_MS) {
        this.#finish('파티 연결이 끊겼습니다.');
        return;
      }
      this.reconnectTimer = setTimeout(() => this.#connect(), reconnectDelay(this.reconnectAttempt++));
    }
  }

  #linkFrame(frame) {
    if (this.closed || this.terminal) return;
    switch (frame.t) {
      case 'link': return this.#acceptLink(frame);
      case 'paired':
        if (this.link) {
          this.link = { ...this.link, paired: true };
          this.#paired();
        }
        return;
      case 'peer':
        if (frame.ready) this.#peerReady();
        return;
      case 'data': return this.#receive(frame.d);
      case 'ended': return this.#finish(({
        partner_left: '동료가 원정을 종료했습니다.',
        partner_lost: '동료와의 연결이 끊겼습니다.',
        pair_timeout: '동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.',
      })[frame.reason] ?? '파티 연결이 종료되었습니다.');
    }
  }

  #acceptLink(link) {
    if (link.id !== this.matchId || link.role !== this.matchRole || (link.role !== 'host' && link.role !== 'guest') ||
        typeof link.selfId !== 'string' || typeof link.partnerId !== 'string' ||
        typeof link.selfName !== 'string' || typeof link.partnerName !== 'string' ||
        !Number.isSafeInteger(link.seed) || link.seed < 0 || link.seed > 0xFFFFFFFF || typeof link.friendlyFire !== 'boolean') {
      this.#finish('파티 연결 정보를 확인할 수 없습니다.');
      return;
    }
    this.lostAt = this.reconnectAttempt = 0;
    this.link = link;
    // After a reconnect the server has forgotten nothing; repeat our own readiness for safety.
    if (this.original?.ready) this.channel?.send({ t: 'ready' });
    if (link.paired) this.#paired();
    if (link.partnerReady) this.#peerReady();
  }

  #paired() {
    if (this.original) {
      if (this.original.paired) return;
      this.original.paired = true;
      this.original.deadline = now() + ORIGINAL_ENTRY_TIMEOUT_MS;
      this.originalParty({ type: 'paired' });
      // The guest loads first; the host starts its world once the guest reports ready.
      if (!this.host) this.#launchOriginal();
    } else {
      this.#createEngine();
    }
  }

  #peerReady() {
    if (!this.original) return;
    this.original.peerReady = true;
    if (this.host && this.original.paired) this.#launchOriginal();
  }

  #launchOriginal() {
    if (this.original.launched) return;
    this.original.launched = true;
    this.original.deadline = now() + ORIGINAL_ENTRY_TIMEOUT_MS;
    this.onLoading({ phase: 'ready', mode: 'party' });
    this.originalParty({ type: 'start', link: this.link, seed: String(this.link.seed), friendlyFire: this.link.friendlyFire });
  }

  #leaveLink() {
    const channel = this.channel;
    this.channel = null;
    if (!channel) return;
    channel.send({ t: 'leave' });
    channel.close(1000, 'leave');
  }

  // ---- dungeon ----

  #tick() {
    if (this.closed || this.terminal) return;
    const time = now();
    const entryExpired = this.original
      ? !(this.original.ready && this.original.peerReady) && time >= this.original.deadline
      : (!this.runtime || !this.frame) && time - this.startedAt >= ENTRY_TIMEOUT_MS;
    if (entryExpired) {
      this.#finish(this.localPlayer ? '던전 데이터를 불러오지 못했습니다. 다시 시도해 주세요.' :
        '동료와 던전 연결을 열지 못했습니다. 여관에서 다시 시도해 주세요.');
      return;
    }
    if (this.localPlayer) {
      if (this.engine) this.#advanceHost(time, false);
      return;
    }
    if (!this.link?.paired || this.original) return;
    if (this.host) {
      if (this.engine) this.#advanceHost(time, time >= this.nextHeartbeatAt);
    } else {
      if (this.frame && time - this.lastSnapshotAt >= HOST_TIMEOUT_MS) {
        this.#finish('파티장과의 연결이 끊겼습니다.');
        return;
      }
      this.#sendGuestCommands(time);
    }
  }

  #createEngine() {
    if (this.engine || !this.runtime?.HostBattle) return;
    const { HostBattle } = this.runtime;
    if (this.localPlayer) {
      const practice = this.localMode === 'practice';
      this.engine = new HostBattle(crypto.randomUUID(), this.localPlayer, null, now(), newSeed(), this.theme,
        practice && this.friendlyFire, practice);
    } else if (this.host && this.link?.paired) {
      const { id, selfId, selfName, selfAppearance, selfPreparation,
        partnerId, partnerName, partnerAppearance, partnerPreparation, seed, theme, friendlyFire } = this.link;
      this.engine = new HostBattle(id, { id: selfId, name: selfName, appearance: selfAppearance, preparation: selfPreparation },
        { id: partnerId, name: partnerName, appearance: partnerAppearance, preparation: partnerPreparation },
        now(), seed, theme === PRISON ? PRISON : SEWERS, friendlyFire);
    }
  }

  #settleLoading(phase, cancelled = false) {
    if (this.loadingFinished) return;
    this.loadingFinished = true;
    if (phase === 'failed') this.loadAbort.abort();
    this.onLoading({ phase, version: this.runtime?.version ?? this.loadingVersion, cached: this.runtime?.cached ?? false,
      mode: this.localMode, practice: this.localMode === 'practice', cancelled });
  }

  #advanceHost(time, heartbeat) {
    const selfId = this.localPlayer?.id ?? this.link.selfId;
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
      if (!maySend(action, this.frame?.state)) continue;
      this.unacked.push({ sequence: ++this.commandSequence, action });
      this.nextCommandAt = 0;
    }
    if (!this.unacked.length || time < this.nextCommandAt) return;
    this.#transmit({ type: 'input', sequence: this.unacked.at(-1).sequence, commands: this.unacked });
    this.nextCommandAt = time + COMMAND_RETRY_MS;
  }

  #transmit(wire) {
    const message = JSON.stringify(wire);
    if (encoder.encode(message).byteLength > MAX_MESSAGE_BYTES) return;
    this.#sendData(message);
  }

  #receive(message) {
    if (this.closed || this.terminal || !this.link?.paired || typeof message !== 'string' || !message ||
        message.length > MAX_MESSAGE_BYTES || encoder.encode(message).byteLength > MAX_MESSAGE_BYTES) return;
    if (this.original) {
      // Original-engine payloads are opaque InnOriginalParty packets.
      if (this.original.ready) this.originalParty({ type: 'payload', data: message });
      return;
    }
    let wire;
    try { wire = JSON.parse(message); } catch { return; }
    if (!wire) return;
    if (this.engine && wire.type === 'input' && Number.isSafeInteger(wire.sequence) &&
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
      const hostTime = Math.max(wire.state.wallNow, this.frame?.wallTime() ?? 0);
      const state = wire.state.revision === this.frame?.state.revision
        ? this.frame.state : this.#withPartyAppearances(wire.state);
      this.#publish(state, hostTime);
    }
  }

  #withPartyAppearances(state) {
    const { selfId, partnerId, selfAppearance, partnerAppearance } = this.link;
    if (!selfAppearance && !partnerAppearance) return state;
    return { ...state, actors: state.actors.map(actor => {
      const appearance = actor.id === selfId ? selfAppearance : actor.id === partnerId ? partnerAppearance : null;
      return appearance ? { ...actor, appearance } : actor;
    }) };
  }

  #validSnapshot(state) {
    return state && state.id === this.matchId && state.selfId === this.link.selfId &&
      Number.isSafeInteger(state.revision) && state.revision >= (this.frame?.state.revision ?? 0) &&
      Number.isSafeInteger(state.commandAck) && Number.isSafeInteger(state.now) &&
      Number.isSafeInteger(state.wallNow) && Number.isSafeInteger(state.pausedUntil) &&
      Number.isSafeInteger(state.pauseReadyAt) &&
      ['active', 'won', 'lost'].includes(state.status) &&
      (state.encounter == null || (state.inCombat ? ['combat', 'pursuit'] : ['alert', 'exploration']).includes(state.encounter)) &&
      Array.isArray(state.map) && state.map.length > 0 && typeof state.map[0] === 'string' && state.map[0].length > 0 &&
      state.map.every(row => typeof row === 'string' && row.length === state.map[0].length) &&
      Array.isArray(state.actors) && state.actors.some(actor => actor?.id === state.selfId) && state.actors.every(actor =>
        typeof actor?.id === 'string' && typeof actor.name === 'string' && typeof actor.kind === 'string' && actor.kind !== 'dummy' &&
        Number.isFinite(actor.energy) && Number.isSafeInteger(actor.rations) && actor.rations >= 0 && validInventory(actor.inventory)) &&
      validKnowledge(state.identifiedItems) &&
      Array.isArray(state.drops) && state.drops.every(drop => typeof drop?.id === 'string' && typeof drop.item === 'string') &&
      (state.trainingTools == null || (Array.isArray(state.trainingTools) && state.trainingTools.length === 0)) &&
      Array.isArray(state.messages) && state.messages.length <= 16 && state.messages.every(message =>
        Number.isSafeInteger(message?.id) && message.id > 0 && typeof message.actorId === 'string' &&
        typeof message.name === 'string' && typeof message.text === 'string' &&
        (message.time == null || (Number.isSafeInteger(message.time) && message.time >= 0))) &&
      typeof state.friendlyFire === 'boolean' && (!this.frame || state.friendlyFire === this.frame.state.friendlyFire) &&
      Array.isArray(state.attackEvents) && state.attackEvents.length <= 16 && state.attackEvents.every(event =>
        Number.isSafeInteger(event?.id) && event.id > 0 && Number.isSafeInteger(event.at) && event.at >= 0 &&
        typeof event.actorId === 'string' && (event.targetId == null || typeof event.targetId === 'string') &&
        Number.isSafeInteger(event.amount) && event.amount >= 0 && attackResults.has(event.result) &&
        (event.action === 'attack' || event.action === 'shoot') && mapCell(state.map, event.x, event.y)) &&
      Array.isArray(state.projectiles) && state.projectiles.length <= 8 && state.projectiles.every(projectile =>
        Number.isSafeInteger(projectile?.id) && projectile.id > 0 && typeof projectile.ownerId === 'string' &&
        Number.isSafeInteger(projectile.stepAt) && projectile.stepAt >= 0 &&
        mapCell(state.map, projectile.x, projectile.y) && mapCell(state.map, projectile.nextX, projectile.nextY) &&
        Math.max(Math.abs(projectile.nextX - projectile.x), Math.abs(projectile.nextY - projectile.y)) === 1);
  }

  #publish(state, hostTime, notice = '') {
    if (this.closed) return;
    const receivedAt = now();
    const wallTime = () => state ? hostTime + (now() - receivedAt) : 0;
    this.frame = {
      state, notice,
      assetVersion: this.runtime?.version ?? null,
      connection: this.localPlayer ? 'local' : 'relay',
      wallTime,
      serverTime: () => state ? state.now + (state.pausedUntil > 0 ? 0 : Math.max(0, wallTime() - state.wallNow)) : 0,
    };
    if (this.runtime || !state) this.listener(this.frame);
  }

  #finish(notice) {
    if (this.closed || this.terminal) return;
    this.terminal = true;
    clearInterval(this.timer);
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.hostTimer);
    // Tell the server, so the partner's run ends too when this side gives up first.
    this.#leaveLink();
    if (this.original) {
      if (!this.original.launched) this.#settleLoading('failed');
      this.originalParty({ type: 'ended', notice });
      return;
    }
    this.#settleLoading('failed');
    const state = this.runtime ? this.frame?.state : null;
    const time = this.frame?.serverTime() ?? 0;
    const wallTime = this.frame?.wallTime() ?? 0;
    this.#publish(active(state?.status)
      ? { ...state, now: time, wallNow: wallTime, status: 'closed', queued: null, inCombat: false, pausedUntil: 0, projectiles: [] }
      : state, wallTime, notice);
  }
}
