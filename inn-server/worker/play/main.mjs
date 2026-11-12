// SPDX-License-Identifier: GPL-3.0-or-later
import { BattleRules, PRISON } from './rules.mjs';
import { Board, canStep, canStrike, nextToward } from './board.mjs';
import { PlaySession } from './session.mjs';

const byId = (id) => document.getElementById(id);
const board = new Board(byId('board'));
const fields = Object.fromEntries([
  'scene-title', 'connection', 'board-title', 'board-status', 'help', 'help-toggle', 'pause', 'battle-overlay', 'notice',
  'inn-panel', 'name-heading', 'name-form', 'player-name', 'name-button', 'dungeon-theme', 'friendly-fire', 'match', 'guide', 'drink', 'seekers',
  'chat-form', 'chat-message', 'chat-send', 'chat-log', 'dungeon-panel', 'party', 'party-rules', 'queued', 'attack', 'shoot', 'eat', 'return',
  'touch-controls', 'touch-battle', 'touch-attack', 'touch-shoot', 'touch-eat', 'backpack', 'backpack-hint', 'attack-log', 'aim-hint',
  'dungeon-chat-log', 'dungeon-chat-form', 'dungeon-chat-message', 'dungeon-chat-send',
  'inn-dialogue', 'npc-name', 'npc-dialogue', 'npc-close',
  'loading-overlay', 'loading-ember', 'loading-phase', 'loading-tip', 'loading-retry', 'loading-close',
].map((id) => [id, byId(id)]));

const keys = new Map([
  ['KeyW', [0, -1]], ['ArrowUp', [0, -1]], ['Numpad8', [0, -1]],
  ['KeyA', [-1, 0]], ['ArrowLeft', [-1, 0]], ['Numpad4', [-1, 0]],
  ['KeyS', [0, 1]], ['ArrowDown', [0, 1]], ['Numpad2', [0, 1]],
  ['KeyD', [1, 0]], ['ArrowRight', [1, 0]], ['Numpad6', [1, 0]],
  ['Numpad7', [-1, -1]], ['Numpad9', [1, -1]], ['Numpad1', [-1, 1]], ['Numpad3', [1, 1]],
]);
const MOVE_QUEUE_LEAD = 70;
const ACK_WAIT = 550;
const MOVE_RETRY = 60;
const ATTACK_FEEDBACK_AGE = 1800;
const ATTACK_RESULTS = {
  out_of_reach: '사거리 벗어남',
  blocked: '벽에 막힘',
  target_gone: '대상 쓰러짐',
  miss: '빗나감',
  ally_blocked: '아군에 막힘',
};
const LOADING_TIPS = [
  '대장장이가 무기를 제련하는 중…',
  '길잡이가 맥주를 한 잔 더 주문하는 중…',
  '하수도 쥐가 순찰 경로를 확인하는 중…',
  '등불에 기름을 채우는 중…',
];
const held = new Map();
const busy = new Set();
const party = new Map();
const itemNames = { ration: '식량', gold: '금화' };
const backpack = ['ration', 'gold', null, null, null, null];
const slotViews = [];
let direction;
let tap;
let destination;
let selected;
let aimCell = null;
let submitted;
let queuedPreview;
let lastMoveAt = 0;
let noticeUntil = 0;
let localNotice = '';
let connectionNotice = '';
let lastBattle;
let lastAttackEventId = 0;
let seekersKey = '';
let frozenAt = null;
let frozenDuration = 0;
let pauseRequest = null;
let selectedSlot = -1;
let wheelTotal = 0;
let lastWheelAt = 0;
let loading = null;
let loadingTipIndex = 0;
let loadingTipTimer = null;

const play = new PlaySession({
  onInn(state) {
    if (play.frame) return;
    board.set(state, false);
    updateInn();
  },
  onBattle(frame) {
    if (!frame) {
      clearMovement();
      setAim(null);
      clearAttackLog();
      lastBattle = selected = submitted = queuedPreview = null;
      resetVisualClock();
      board.set(play.inn, false);
      updateScene();
      return;
    }
    const state = frame.state;
    const prior = lastBattle;
    if (prior?.id !== state.id) {
      clearMovement();
      resetVisualClock();
      clearAttackLog();
      setAim(null);
      showDialogue();
      selected = submitted = queuedPreview = null;
      selectedSlot = -1;
      party.clear();
      fields.party.replaceChildren();
      fields['dungeon-chat-message'].value = '';
      fields['dungeon-chat-log'].replaceChildren();
      delete fields['dungeon-chat-log'].dataset.events;
    }
    const artChanged = prior?.id !== state.id || board.assetVersion !== frame.assetVersion;
    const now = performance.now();
    syncVisualClock(isPaused(state), now);
    board.set(state, true, visualTime(now), frame.assetVersion);
    if (artChanged) fields.backpack.style.setProperty('--items-atlas', `url("${board.images.items.src}")`);
    const attackEvents = recentAttackEvents(state, frame.serverTime());
    board.showAttackEvents(attackEvents, visualTime(now));
    showAttackResults(state, attackEvents);
    if (submitted && state.commandAck > submitted.ack) {
      const actor = ownActor(state);
      const old = ownActor(prior);
      if (submitted.type === 'move' && actor && old && actor.x === old.x && actor.y === old.y &&
          !sameAction(state.queued, submitted)) board.cancel(visualTime(now));
      submitted = null;
    }
    showDrops(state, prior);
    lastBattle = state;
    updateScene();
  },
  onStatus(message) {
    connectionNotice = message;
    if (!play.frame) updateInn();
    updateNotice(performance.now());
  },
  onLoading: updateLoading,
});

function text(element, value) {
  if (element.textContent !== value) element.textContent = value;
}

function status(label, tone) {
  text(fields.connection, label);
  fields.connection.dataset.tone = tone;
}

function flash(message) {
  localNotice = message;
  noticeUntil = performance.now() + 2300;
  updateNotice(performance.now());
}

function updateNotice(now) {
  text(fields.notice, connectionNotice || play.frame?.notice || (now < noticeUntil ? localNotice : ''));
}

const isPaused = (state) => state?.pausedUntil > 0;
const visualTime = (now) => (frozenAt ?? now) - frozenDuration;

function resetVisualClock() {
  frozenAt = null;
  frozenDuration = 0;
  pauseRequest = null;
}

function syncVisualClock(paused, now) {
  if (paused && frozenAt === null) {
    frozenAt = now;
    clearMovement();
    selectedSlot = -1;
  } else if (!paused && frozenAt !== null) {
    frozenDuration += now - frozenAt;
    frozenAt = null;
  }
  if (pauseRequest && pauseRequest.paused !== paused) pauseRequest = null;
}

async function run(name, task) {
  if (busy.has(name)) return;
  busy.add(name);
  updateScene();
  try { return await task(); }
  finally {
    busy.delete(name);
    updateScene();
  }
}

function saveName(name) {
  try { localStorage.setItem('noxac.playerName', name); }
  catch { /* The game can run without persistent storage. */ }
}

function loadName() {
  try { return localStorage.getItem('noxac.playerName') ?? ''; }
  catch { return ''; }
}

function focusBoard() { board.canvas.focus({ preventScroll: true }); }

function isRuntimeLoading() {
  return loading?.phase === 'checking' || loading?.phase === 'loading';
}

function updateLoading(state) {
  loading = state.phase === 'ready' || state.cancelled ? null : state;
  const active = isRuntimeLoading();
  const failed = loading?.phase === 'failed';
  fields['loading-overlay'].hidden = !active && !failed;
  fields['loading-ember'].hidden = !active;
  fields['loading-retry'].hidden = fields['loading-close'].hidden = !failed;
  board.canvas.setAttribute('aria-busy', String(active));
  if (active) {
    text(fields['loading-phase'], state.phase === 'checking' ? '게임 버전 확인 중' : '던전 불러오는 중');
    if (loadingTipTimer === null) {
      clearMovement();
      text(fields['loading-tip'], LOADING_TIPS[loadingTipIndex]);
      loadingTipTimer = setInterval(() => {
        loadingTipIndex = (loadingTipIndex + 1) % LOADING_TIPS.length;
        text(fields['loading-tip'], LOADING_TIPS[loadingTipIndex]);
      }, 1800);
    }
  } else {
    clearInterval(loadingTipTimer);
    loadingTipTimer = null;
    if (failed) {
      text(fields['loading-phase'], '던전을 불러오지 못했습니다');
      text(fields['loading-tip'], '');
      text(fields['loading-retry'], state.practice ? '다시 시도' : '다시 매칭');
    }
  }
  updateScene();
}

function updateScene() {
  const dungeon = Boolean(play.frame);
  const title = dungeon ? (play.frame.state.theme === PRISON ? '버려진 감옥' : '하수도') : '여관';
  fields['inn-panel'].hidden = dungeon;
  fields['dungeon-panel'].hidden = !dungeon;
  fields['touch-battle'].hidden = !dungeon;
  fields.pause.hidden = !dungeon;
  board.canvas.dataset.paused = String(isPaused(play.frame?.state));
  text(fields['scene-title'], title);
  text(fields['board-title'], dungeon ? `${title} 던전` : '여관 홀');
  const tabTitle = `${title} · Noxac Pixel Dungeon`;
  if (document.title !== tabTitle) document.title = tabTitle;
  board.canvas.setAttribute('aria-label', dungeon
    ? `${title} 지도. 방향키로 이동하고 스페이스로 평타, R로 사격을 조준합니다.` : '여관 지도. 방향키나 WASD로 이동합니다.');
  if (dungeon) updateBattle();
  else updateInn();
}

function updateInn() {
  const state = play.inn;
  const online = Boolean(play.online && state);
  const entering = Boolean(play.battle || state?.match || isRuntimeLoading());
  const returning = busy.has('return');
  const ready = online && !entering && !returning;
  fields['touch-controls'].hidden = !ready;
  if (entering || returning || busy.has('name')) status('연결 중', 'pending');
  else if (online) status('여관 접속', 'live');
  else if (play.name && connectionNotice) status('재접속 중', 'offline');
  else status('입장 대기', 'idle');
  text(fields['board-status'], online ? `모험가 ${state.people.length}명` : '모험가 대기 중');
  text(fields['name-heading'], online ? '모험가' : '여관 입장');
  text(fields['name-button'], online ? '이름 변경' : '입장');
  fields['name-button'].disabled = busy.has('name') || entering || returning;
  fields['player-name'].disabled = entering || returning;
  const queued = Boolean(state?.queued);
  const rulesLocked = queued || entering || returning || busy.has('match');
  fields['dungeon-theme'].value = play.theme;
  fields['dungeon-theme'].disabled = rulesLocked;
  fields['friendly-fire'].checked = play.friendlyFire === true;
  fields['friendly-fire'].disabled = rulesLocked;
  text(fields.match, queued ? '동료 찾는 중 · 취소' : '동료 찾기');
  fields.match.dataset.waiting = String(queued);
  fields.match.disabled = !ready || busy.has('match');
  fields.drink.disabled = !ready || busy.has('drink');
  fields.guide.disabled = !ready;
  fields['chat-message'].disabled = !ready;
  fields['chat-send'].disabled = !ready || busy.has('chat');
  fields['battle-overlay'].hidden = isRuntimeLoading() || !entering && !returning;
  if (entering || returning) text(fields['battle-overlay'], returning ? '여관으로 돌아가는 중…' : '던전에 들어가는 중…');
  if (!ready) showDialogue();
  fields['loading-retry'].disabled = !ready || busy.has('match');
  updateSeekers(state, ready);
  updateChat(fields['chat-log'], state?.events ?? []);
}

function updateSeekers(state, ready) {
  const seekers = state?.people.filter((person) => person.queued && person.id !== state.selfId) ?? [];
  const disabled = !ready || busy.has('match');
  const key = JSON.stringify([disabled, seekers.map(({ id, name }) => [id, name])]);
  if (key === seekersKey) return;
  seekersKey = key;
  fields.seekers.hidden = seekers.length === 0;
  fields.seekers.replaceChildren(...seekers.map((person) => {
    const row = document.createElement('li');
    const button = document.createElement('button');
    const name = document.createElement('span');
    const label = document.createElement('span');
    name.textContent = person.name;
    label.textContent = '참가';
    button.type = 'button';
    button.disabled = disabled;
    button.setAttribute('aria-label', `${person.name} 님의 파티에 참가`);
    button.append(name, label);
    button.addEventListener('click', () => void match(person.id));
    row.append(button);
    return row;
  }));
}

function showDialogue(npc) {
  fields['inn-dialogue'].hidden = !npc;
  if (!npc) return;
  destination = null;
  text(fields['npc-name'], npc.name);
  text(fields['npc-dialogue'], npc.dialogue);
}

function talkNearby() {
  const state = play.inn;
  const self = state?.people.find((person) => person.id === state.selfId);
  if (!self || !play.online || play.battle || state.match) return;
  const npc = state.npcs?.find((person) => Math.max(Math.abs(person.x - self.x), Math.abs(person.y - self.y)) === 1);
  if (npc) showDialogue(npc);
}

function updateChat(log, events) {
  const recent = events.slice(-24);
  const key = recent.map((event) => event.id).join(',');
  if (key === log.dataset.events) return;
  log.dataset.events = key;
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 24;
  log.replaceChildren(...recent.map((event) => {
    const row = document.createElement('li');
    const who = document.createElement('strong');
    who.textContent = event.name;
    if (event.kind === 'drink') row.classList.add('drink');
    row.append(who, document.createTextNode(event.kind === 'drink' ? '님이 술을 마셨습니다.' : event.text ?? ''));
    return row;
  }));
  if (atBottom) log.scrollTop = log.scrollHeight;
}

function ownActor(state = play.frame?.state) {
  return state?.actors.find((actor) => actor.id === state.selfId);
}

function canAct(state, actor) {
  return !busy.has('return') && actor?.hp > 0 && !state.waitingForPartner && !isPaused(state) &&
    (state.status === 'active' || state.status === 'won');
}

function canAttack(state, actor) {
  return canAct(state, actor) && state.status === 'active';
}

function canTogglePause(state) {
  return !busy.has('return') && !state?.waitingForPartner && ['active', 'won'].includes(state?.status);
}

function canChat(state) {
  return !busy.has('return') && ['active', 'won', 'lost'].includes(state?.status);
}

function updateBattle() {
  const { state, connection } = play.frame;
  const player = ownActor(state);
  const enemies = state.actors.filter((actor) => actor.kind === 'enemy' && actor.hp > 0).length;
  status(({ direct: 'P2P 직접 연결', relay: '중계 연결', local: '길잡이 연습' })[connection], 'live');
  text(fields['board-status'], state.status === 'won' ? '소탕 완료' : `${state.inCombat ? '교전' : '탐색'} · 남은 적 ${enemies}`);
  const overlay = state.status === 'lost' ? '파티가 쓰러졌습니다' : state.status === 'closed' ? '원정이 끝났습니다' :
    state.waitingForPartner ? '동료가 들어오는 중…' : player?.hp === 0 ? '쓰러졌습니다 · 동료의 전투를 지켜봅니다' : '';
  fields['touch-controls'].hidden = !canAct(state, player);
  fields['battle-overlay'].hidden = !overlay;
  text(fields['battle-overlay'], overlay);
  const queued = state.queued;
  if (!canAttack(state, player) && aimCell) setAim(null);
  fields.queued.hidden = !queued;
  if (queued) text(fields.queued, `예약 · ${{ move: '이동', attack: '평타', shoot: '사격', eat: '식량' }[queued.type]}`);
  text(fields['party-rules'], state.friendlyFire ? '아군 공격 허용 · 동료를 클릭해 평타' : '아군 공격 차단');
  fields['party-rules'].dataset.enabled = String(state.friendlyFire === true);
  fields.return.disabled = busy.has('return');
  fields['dungeon-chat-message'].disabled = fields['dungeon-chat-send'].disabled = !canChat(state);
  updateChat(fields['dungeon-chat-log'], state.messages ?? []);
  updateParty(state);
  updateBackpack();
  updateCombatUi();
}

function updateParty(state) {
  const actors = state.actors.filter((actor) => actor.kind !== 'enemy').sort((left, right) =>
    Number(right.id === state.selfId) - Number(left.id === state.selfId));
  const ids = new Set(actors.map((actor) => actor.id));
  for (const [id, slot] of party) {
    if (ids.has(id)) continue;
    slot.root.remove();
    party.delete(id);
  }
  for (const actor of actors) {
    const own = actor.id === state.selfId;
    let slot = party.get(actor.id);
    if (!slot) {
      const root = document.createElement('div');
      const name = document.createElement('strong');
      const inspect = document.createElement('button');
      const inspectLabel = document.createElement('span');
      const stats = document.createElement('div');
      const health = document.createElement('span');
      const energy = document.createElement('span');
      const hpMeter = document.createElement('progress');
      const energyMeter = document.createElement('progress');
      const details = document.createElement('dl');
      const detailValues = {};
      root.className = 'party-slot';
      inspect.type = 'button';
      inspect.className = 'party-inspect';
      inspect.setAttribute('aria-expanded', 'false');
      inspectLabel.textContent = '상태';
      stats.className = 'stats';
      energy.className = 'energy-text';
      energyMeter.className = 'energy';
      details.className = 'party-details';
      details.hidden = true;
      hpMeter.setAttribute('aria-label', '체력');
      energyMeter.setAttribute('aria-label', '행동력');
      energyMeter.max = BattleRules.ENERGY_CAP;
      if (own) name.className = 'own';
      for (const [key, title] of [['move', '이동'], ['attack', '평타'], ['shoot', '사격'], ['rations', '식량']]) {
        const label = document.createElement('dt');
        const value = document.createElement('dd');
        label.textContent = title;
        detailValues[key] = value;
        details.append(label, value);
      }
      inspect.addEventListener('click', () => {
        details.hidden = !details.hidden;
        inspect.setAttribute('aria-expanded', String(!details.hidden));
        text(inspectLabel, details.hidden ? '상태' : '닫기');
        updateCombatUi();
      });
      inspect.append(name, inspectLabel);
      stats.append(health, energy);
      root.append(inspect, stats, hpMeter, energyMeter, details);
      fields.party.append(root);
      slot = { root, name, health, energy, hpMeter, energyMeter, details, detailValues };
      party.set(actor.id, slot);
    }
    text(slot.name, `${actor.name}${own ? ' (나)' : ''}`);
    text(slot.health, `HP ${actor.hp}/${actor.maxHp}`);
    slot.hpMeter.max = actor.maxHp;
    slot.hpMeter.value = actor.hp;
    slot.actor = actor;
  }
}

function buttonLabel(button, label) {
  if (button.firstChild.nodeValue !== label + ' ') button.firstChild.nodeValue = label + ' ';
}

function backpackCounts() {
  const state = play.frame?.state;
  const actor = ownActor(state);
  const gold = (state?.drops ?? []).reduce((total, drop) =>
    actor && drop.item === 'gold' && drop.pickedBy === actor.id ? total + drop.amount : total, 0);
  return { ration: actor?.rations ?? 0, gold };
}

function updateBackpack() {
  const paused = isPaused(play.frame?.state);
  const counts = backpackCounts();
  if (paused || selectedSlot !== -1 && !counts[backpack[selectedSlot]]) selectedSlot = -1;
  for (let index = 0; index < slotViews.length; index++) {
    const { button, icon, label, count } = slotViews[index];
    const item = backpack[index];
    const amount = counts[item] ?? 0;
    icon.hidden = label.hidden = count.hidden = amount === 0;
    icon.dataset.item = item ?? '';
    button.dataset.empty = String(amount === 0);
    button.disabled = paused;
    button.draggable = !paused && amount > 0;
    button.setAttribute('aria-pressed', String(selectedSlot === index));
    button.setAttribute('aria-label', amount > 0 ? `${index + 1}번 칸 · ${itemNames[item]} ${amount}` : `${index + 1}번 빈 칸`);
    if (amount > 0) {
      text(label, itemNames[item]);
      text(count, String(amount));
    }
  }
  const hint = paused ? '일시정지 중 · 배낭 보기' : selectedSlot === -1 ? '아이템 칸을 누른 뒤 옮길 칸을 선택하세요.' :
    `${itemNames[backpack[selectedSlot]]}을 옮길 칸을 선택하세요.`;
  text(fields['backpack-hint'], hint);
}

function swapSlots(from, to) {
  if (isPaused(play.frame?.state)) return;
  [backpack[from], backpack[to]] = [backpack[to], backpack[from]];
  selectedSlot = -1;
  updateBackpack();
}

function buildBackpack() {
  for (let index = 0; index < backpack.length; index++) {
    const button = document.createElement('button');
    const icon = document.createElement('span');
    const label = document.createElement('span');
    const count = document.createElement('span');
    button.type = 'button';
    button.className = 'bag-slot';
    icon.className = 'bag-icon';
    icon.setAttribute('aria-hidden', 'true');
    label.className = 'bag-name';
    count.className = 'bag-count';
    button.append(icon, label, count);
    button.addEventListener('click', () => {
      if (isPaused(play.frame?.state)) return;
      if (selectedSlot !== -1) {
        swapSlots(selectedSlot, index);
      } else if (backpackCounts()[backpack[index]] > 0) {
        selectedSlot = index;
        updateBackpack();
      }
    });
    button.addEventListener('dragstart', (event) => {
      if (isPaused(play.frame?.state) || !(backpackCounts()[backpack[index]] > 0)) {
        event.preventDefault();
        return;
      }
      selectedSlot = index;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', `noxac-bag:${index}`);
      updateBackpack();
    });
    button.addEventListener('dragend', () => {
      selectedSlot = -1;
      updateBackpack();
    });
    button.addEventListener('dragover', (event) => { if (selectedSlot !== -1) event.preventDefault(); });
    button.addEventListener('drop', (event) => {
      if (selectedSlot === -1 || event.dataTransfer.getData('text/plain') !== `noxac-bag:${selectedSlot}`) return;
      event.preventDefault();
      swapSlots(selectedSlot, index);
    });
    fields.backpack.append(button);
    slotViews.push({ button, icon, label, count });
  }
}

function pauseStatus(frame, now = performance.now()) {
  const paused = isPaused(frame.state);
  const wallTime = frame.wallTime();
  const cooldown = Math.max(0, (frame.state.pauseReadyAt ?? 0) - wallTime);
  const waiting = pauseRequest?.paused === paused && now < pauseRequest.until;
  return { paused, cooldown, remaining: Math.max(0, (frame.state.pausedUntil ?? 0) - wallTime),
    canToggle: canTogglePause(frame.state) && (paused || cooldown === 0) && !waiting };
}

function updatePauseUi(frame) {
  const { paused, cooldown, remaining, canToggle } = pauseStatus(frame);
  const label = paused ? '계속하기' : cooldown > 0 ? `일시정지 · ${Math.ceil(cooldown / 1000)}초` : '일시정지';
  buttonLabel(fields.pause, label);
  fields.pause.dataset.paused = String(paused);
  fields.pause.disabled = !canToggle;
  if (paused) {
    fields['battle-overlay'].hidden = false;
    text(fields['battle-overlay'], remaining > 0 ? `전술 일시정지 · ${Math.ceil(remaining / 1000)}초` : '재개하는 중…');
  }
}

function togglePause() {
  const frame = play.frame;
  if (!frame) return;
  const now = performance.now();
  const { paused, canToggle } = pauseStatus(frame, now);
  if (canToggle && play.action('pause')) {
    pauseRequest = { paused, until: now + 1500 };
    updatePauseUi(frame);
  }
}

function updateCombatUi() {
  const frame = play.frame;
  if (!frame) return;
  const { state } = frame;
  const now = frame.serverTime();
  for (const slot of party.values()) {
    const actor = slot.actor;
    const energy = actor.hp > 0 ? BattleRules.energyAt(actor, state.now, now) : 0;
    if (slot.energyMeter.value !== energy) slot.energyMeter.value = energy;
    text(slot.energy, actor.hp > 0 ? `행동력 ${Math.floor(energy + 1e-8)}/${BattleRules.ENERGY_CAP}` : '쓰러짐');
    if (!slot.details.hidden) {
      for (const type of ['move', 'attack', 'shoot']) {
        const wait = Math.max(0, BattleRules.readyAt(actor, type, state.now, state.inCombat) - now);
        text(slot.detailValues[type], actor.hp === 0 ? '—' : wait > 0 ? `${(Math.ceil(wait / 100) / 10).toFixed(1)}초` : '준비');
      }
      text(slot.detailValues.rations, `${actor.rations ?? 0}개`);
    }
  }
  const player = ownActor(state);
  const active = canAct(state, player);
  const attacking = canAttack(state, player);
  const remaining = attacking ? Math.max(0, player.attackReadyAt - now) : 0;
  const attackWait = attacking && BattleRules.readyAt(player, 'attack', state.now, state.inCombat) > now;
  const attackLabel = remaining > 0 ? `평타 ${(Math.ceil(remaining / 100) / 10).toFixed(1)}초` :
    attackWait ? `평타 · 행동력 ${BattleRules.ATTACK_COST}` : '평타';
  buttonLabel(fields.attack, attackLabel);
  fields.attack.dataset.waiting = String(attackWait);
  fields.attack.disabled = fields['touch-attack'].disabled = !attacking;
  const shotWait = attacking && BattleRules.readyAt(player, 'shoot', state.now, state.inCombat) > now;
  const shootLabel = aimCell ? '조준 취소' : remaining > 0 ? `사격 ${(Math.ceil(remaining / 100) / 10).toFixed(1)}초` :
    shotWait ? `사격 · 행동력 ${BattleRules.SHOOT_COST}` : '사격';
  buttonLabel(fields.shoot, shootLabel);
  text(fields['touch-shoot'], aimCell ? '조준 취소' : '사격');
  fields.shoot.disabled = fields['touch-shoot'].disabled = !attacking;
  buttonLabel(fields.eat, `식량 ${player?.rations ?? 0}`);
  fields.eat.disabled = fields['touch-eat'].disabled = !active || !player.rations || player.hp >= player.maxHp;
  updatePauseUi(frame);
}

function clearAttackLog() {
  lastAttackEventId = 0;
  fields['attack-log'].replaceChildren();
  fields['attack-log'].hidden = true;
}

function recentAttackEvents(state, now) {
  const recent = [];
  for (const event of state.attackEvents ?? []) {
    if (event.id <= lastAttackEventId) continue;
    lastAttackEventId = event.id;
    if (now - event.at <= ATTACK_FEEDBACK_AGE) recent.push(event);
  }
  return recent;
}

function showAttackResults(state, events) {
  const log = fields['attack-log'];
  const actorName = (id) => id === state.selfId ? '나' : state.actors.find((actor) => actor.id === id)?.name;
  for (const event of events.slice(-4)) {
    const action = event.action === 'shoot' ? '사격' : '평타';
    const result = event.result === 'hit'
      ? `${actorName(event.targetId) ?? '대상'}에게 피해 ${event.amount}`
      : ATTACK_RESULTS[event.result];
    if (!result) continue;
    const row = document.createElement('li');
    row.dataset.result = event.result;
    row.textContent = `${actorName(event.actorId) ?? '모험가'} · ${action} · ${result}`;
    if (log.childElementCount === 4) log.firstElementChild.remove();
    log.hidden = false;
    log.append(row);
  }
}

function showDrops(state, prior) {
  if (prior?.id !== state.id) return;
  for (const drop of state.drops) {
    if (!drop.pickedBy || prior.drops.some((item) => item.id === drop.id && item.pickedBy)) continue;
    const player = state.actors.find((actor) => actor.id === drop.pickedBy);
    if (player) flash(`${player.name} · ${drop.item === 'gold' ? '금화' : '식량'} ${drop.amount} 획득`);
  }
}

function match(id) {
  return run('match', async () => {
    if (id) await play.match(id);
    else if (play.inn?.queued) await play.cancelMatch();
    else await play.match();
    focusBoard();
  });
}

function inReach(state, player, target) {
  if (target?.kind !== 'enemy' || target.hp <= 0) return false;
  const dx = target.x - player.x;
  const dy = target.y - player.y;
  return canStrike(state.map, player.x, player.y, dx, dy);
}

function sameAction(a, b) {
  return a?.type === b.type && a.dx === b.dx && a.dy === b.dy && (a.target ?? null) === b.target;
}

function requestAction(type, dx, dy, target, now) {
  const { state } = play.frame;
  const action = { type, dx, dy, target };
  if (sameAction(state.queued, action) || submitted && now - submitted.sent < ACK_WAIT && sameAction(submitted, action)) return false;
  if (!play.action(type, dx, dy, target)) return false;
  submitted = { ...action, sent: now, ack: state.commandAck };
  return true;
}

function attack(targetId) {
  const state = play.frame?.state;
  const player = ownActor(state);
  if (!canAttack(state, player)) return;
  setAim(null);
  const selectedTarget = state.actors.find((actor) => actor.id === (targetId ?? selected));
  const target = targetId ? selectedTarget : inReach(state, player, selectedTarget) ? selectedTarget :
    state.actors.find((actor) => inReach(state, player, actor));
  if (!target || target.id === player.id || target.kind !== 'enemy' && !state.friendlyFire) {
    flash('가까운 적에게 평타를 쓸 수 있습니다.');
    return;
  }
  selected = target.id;
  requestAction('attack', 0, 0, target.id, performance.now());
}

function setAim(cell) {
  const changed = Boolean(aimCell) !== Boolean(cell);
  aimCell = cell;
  if (!changed) return;
  board.canvas.dataset.aiming = String(Boolean(cell));
  fields['aim-hint'].hidden = !cell;
  for (const button of [fields.shoot, fields['touch-shoot']]) button.setAttribute('aria-pressed', String(Boolean(cell)));
}

function toggleAim() {
  const state = play.frame?.state;
  const player = ownActor(state);
  if (!canAttack(state, player)) return;
  if (aimCell) {
    setAim(null);
    return;
  }
  clearMovement();
  const target = state.actors.find((actor) => actor.id === selected && actor.kind === 'enemy' && actor.hp > 0) ?? player;
  setAim([target.x, target.y]);
}

function aimAt(x, y) {
  if (!aimCell || play.frame?.state.map[y]?.[x] == null) return false;
  setAim([x, y]);
  return true;
}

function shoot(x, y) {
  const state = play.frame?.state;
  if (!canAttack(state, ownActor(state)) || !aimAt(x, y)) return;
  if (requestAction('shoot', 0, 0, `${x},${y}`, performance.now())) setAim(null);
}

function eat() {
  const state = play.frame?.state;
  const player = ownActor(state);
  if (!canAct(state, player)) return;
  if (!player.rations) flash('보유한 식량이 없습니다.');
  else if (player.hp >= player.maxHp) flash('체력이 이미 가득 찼습니다.');
  else requestAction('eat', 0, 0, null, performance.now());
}

function moveDungeon(dx, dy, fresh, now) {
  const frame = play.frame;
  const state = frame?.state;
  const player = ownActor(state);
  if (!canAct(state, player) || !canStep(state.map, player.x, player.y, dx, dy, true)) return;
  const gameNow = frame.serverTime();
  const readyAt = BattleRules.readyAt(player, 'move', state.now, state.inCombat);
  const ready = readyAt <= gameNow;
  const pending = submitted && now - submitted.sent < ACK_WAIT;
  if (!fresh && (pending || readyAt > gameNow + MOVE_QUEUE_LEAD ||
      state.queued && (state.queued.type === 'move' || !ready))) return;
  if (pending && submitted.type !== 'move' && now - submitted.sent < MOVE_RETRY) return;
  if (now - lastMoveAt < MOVE_RETRY) return;
  if (ready && state.actors.some((actor) => actor.hp > 0 && actor.x === player.x + dx && actor.y === player.y + dy)) return;
  if (!requestAction('move', dx, dy, null, now)) return;
  lastMoveAt = now;
  if (ready && state.queued?.type !== 'move') board.predict(dx, dy, visualTime(now));
}

function moveInn(dx, dy, now) {
  if (play.moveInn(dx, dy)) board.predict(dx, dy, now);
}

function clearMovement() {
  held.clear();
  direction = tap = destination = null;
  lastMoveAt = 0;
}

function changeDirection(now, releasing = false) {
  const sides = [...held.values()];
  const dx = Number(sides.some((item) => item[0] > 0)) - Number(sides.some((item) => item[0] < 0));
  const dy = Number(sides.some((item) => item[1] > 0)) - Number(sides.some((item) => item[1] < 0));
  if (direction?.dx === dx && direction?.dy === dy) return;
  if (!dx && !dy) {
    if (!held.size && direction?.fresh && direction.tap) tap = direction;
    direction = null;
    return;
  }
  const cardinalRelease = releasing && direction?.dx && direction?.dy && !(dx && dy);
  direction = { dx, dy, at: now + (cardinalRelease ? 45 : dx && dy ? 0 : 75), fresh: true, tap: !cardinalRelease };
  tap = null;
  destination = null;
}

function movement(now) {
  if (aimCell || isRuntimeLoading() || isPaused(play.frame?.state)) return;
  const command = tap ?? (direction?.at <= now ? direction : null);
  if (command) {
    if (play.frame) moveDungeon(command.dx, command.dy, command.fresh, now);
    else moveInn(command.dx, command.dy, now);
    command.fresh = false;
    tap = null;
  } else if (!play.frame && !held.size && destination && play.inn) {
    const state = play.inn;
    const player = state.people.find((person) => person.id === state.selfId);
    const step = player && nextToward(state.map, player.x, player.y, ...destination);
    if (step) moveInn(...step, now);
    else destination = null;
  }
  const frame = play.frame;
  const state = frame?.state;
  const player = ownActor(state);
  const queued = state?.queued;
  if (!canAct(state, player) || queued?.type !== 'move') {
    queuedPreview = null;
    return;
  }
  if (BattleRules.readyAt(player, 'move', state.now, state.inCombat) <= frame.serverTime()) {
    const key = `${player.actionSeq}/${queued.dx}/${queued.dy}`;
    if (key !== queuedPreview && canStep(state.map, player.x, player.y, queued.dx, queued.dy, true) &&
        !state.actors.some((actor) => actor.hp > 0 && actor.x === player.x + queued.dx && actor.y === player.y + queued.dy)) {
      queuedPreview = key;
      board.predict(queued.dx, queued.dy, visualTime(now));
    }
  }
}

function toggleHelp() {
  fields.help.hidden = !fields.help.hidden;
  fields['help-toggle'].setAttribute('aria-expanded', String(!fields.help.hidden));
}

fields['player-name'].value = loadName();
fields['dungeon-theme'].addEventListener('change', () => {
  play.setTheme(fields['dungeon-theme'].value);
  updateInn();
});
fields['friendly-fire'].addEventListener('change', () => {
  play.setFriendlyFire(fields['friendly-fire'].checked);
  updateInn();
});
fields['name-form'].addEventListener('submit', (event) => {
  event.preventDefault();
  const name = fields['player-name'].value.trim();
  void run('name', async () => {
    if (await (play.online ? play.rename(name) : play.join(name))) {
      saveName(name);
      focusBoard();
    }
  });
});
fields['chat-form'].addEventListener('submit', (event) => {
  event.preventDefault();
  const input = fields['chat-message'];
  const message = input.value.trim();
  if (message) void run('chat', async () => {
    if (await play.chat(message) && input.value.trim() === message) input.value = '';
  });
});
fields['dungeon-chat-form'].addEventListener('submit', (event) => {
  event.preventDefault();
  if (!canChat(play.frame?.state)) return;
  const input = fields['dungeon-chat-message'];
  const message = input.value.trim();
  if (!message) return;
  if ([...message].length > BattleRules.CHAT_LIMIT || /[\p{C}\p{Zl}\p{Zp}]/u.test(message)) {
    flash(`파티 대화는 한 줄, ${BattleRules.CHAT_LIMIT}자까지 보낼 수 있습니다.`);
  } else if (play.action('chat', 0, 0, message)) input.value = '';
});
fields.match.addEventListener('click', () => void match());
fields.drink.addEventListener('click', () => void run('drink', async () => { await play.drink(); focusBoard(); }));
fields.guide.addEventListener('click', () => { if (play.practice()) focusBoard(); });
fields.attack.addEventListener('click', () => { attack(); focusBoard(); });
fields.shoot.addEventListener('click', () => { toggleAim(); focusBoard(); });
fields.eat.addEventListener('click', () => { eat(); focusBoard(); });
fields['touch-attack'].addEventListener('click', () => attack());
fields['touch-shoot'].addEventListener('click', () => { toggleAim(); focusBoard(); });
fields['touch-eat'].addEventListener('click', eat);
fields.return.addEventListener('click', () => void run('return', async () => { await play.returnToInn(); focusBoard(); }));
fields['help-toggle'].addEventListener('click', toggleHelp);
fields.pause.addEventListener('click', () => { togglePause(); focusBoard(); });
fields['npc-close'].addEventListener('click', () => { showDialogue(); focusBoard(); });
fields['loading-retry'].addEventListener('click', () => {
  if (loading?.phase !== 'failed' || fields['loading-retry'].disabled) return;
  const practice = loading.practice;
  updateLoading({ phase: 'ready' });
  if (practice) play.practice();
  else void match();
  focusBoard();
});
fields['loading-close'].addEventListener('click', () => { updateLoading({ phase: 'ready' }); focusBoard(); });
board.canvas.addEventListener('click', (event) => {
  const [px, py] = board.point(event);
  const [x, y] = board.cellAt(px, py);
  focusBoard();
  if (!play.frame) {
    const id = board.signAt(px, py);
    const npc = board.npcAt(px, py);
    if (id) void match(id);
    else if (npc && play.online && !play.battle && !play.inn?.match) showDialogue(npc);
    else if (['.', 'D'].includes(play.inn?.map[y]?.[x])) destination = [x, y];
    return;
  }
  const state = play.frame.state;
  const player = ownActor(state);
  if (!canAct(state, player)) return;
  if (aimCell) {
    shoot(x, y);
    return;
  }
  const target = state.actors.find((actor) => actor.id !== state.selfId && actor.hp > 0 &&
    (actor.kind === 'enemy' || state.friendlyFire) && actor.x === x && actor.y === y);
  if (target) {
    selected = target.id;
    attack(target.id);
  } else if (Math.max(Math.abs(x - player.x), Math.abs(y - player.y)) === 1) {
    moveDungeon(x - player.x, y - player.y, true, performance.now());
  }
});
board.canvas.addEventListener('pointermove', (event) => {
  if (aimCell && event.pointerType !== 'touch') aimAt(...board.cellAt(...board.point(event)));
});
board.canvas.addEventListener('wheel', (event) => {
  if (event.ctrlKey || event.metaKey || !event.deltaY || !board.hasInnCamera()) return;
  event.preventDefault();
  const now = performance.now();
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 80 : 1);
  if (now - lastWheelAt > 140 || Math.sign(delta) !== Math.sign(wheelTotal)) wheelTotal = 0;
  lastWheelAt = now;
  wheelTotal += delta;
  if (Math.abs(wheelTotal) >= 32) {
    board.zoomInn(-Math.sign(wheelTotal), now);
    wheelTotal = 0;
  }
}, { passive: false });

function typing(target) { return target instanceof HTMLElement && (target.matches('input, textarea, select') || target.isContentEditable); }

window.addEventListener('keydown', (event) => {
  if (event.code === 'F1' && !event.repeat) { event.preventDefault(); toggleHelp(); return; }
  if (event.altKey || event.ctrlKey || event.metaKey || typing(event.target)) return;
  if (!play.frame && !event.repeat) {
    if (event.code === 'Enter' && event.target === board.canvas) { event.preventDefault(); talkNearby(); return; }
    if (event.code === 'Escape' && !fields['inn-dialogue'].hidden) { event.preventDefault(); showDialogue(); return; }
  }
  const step = keys.get(event.code);
  if (aimCell) {
    if (event.code === 'Escape') { event.preventDefault(); setAim(null); return; }
    if (event.code === 'Enter' && event.target === board.canvas && !event.repeat) {
      event.preventDefault();
      shoot(...aimCell);
      return;
    }
    if (step) {
      event.preventDefault();
      aimAt(aimCell[0] + step[0], aimCell[1] + step[1]);
      return;
    }
  }
  if (step) {
    event.preventDefault();
    if (!isPaused(play.frame?.state) && !event.repeat && !held.has(event.code)) {
      held.set(event.code, step);
      changeDirection(performance.now());
    }
  } else if (play.frame && !event.repeat) {
    if (event.code === 'Space' && !event.target?.closest?.('button')) { event.preventDefault(); attack(); }
    if (event.code === 'KeyR') { event.preventDefault(); toggleAim(); focusBoard(); }
    if (event.code === 'KeyE') { event.preventDefault(); eat(); }
    if (event.code === 'KeyP') { event.preventDefault(); togglePause(); }
  }
});
window.addEventListener('keyup', (event) => {
  if (held.delete(event.code)) { event.preventDefault(); changeDirection(performance.now(), true); }
});
window.addEventListener('blur', clearMovement);
document.addEventListener('focusin', (event) => { if (typing(event.target)) clearMovement(); });

for (const button of document.querySelectorAll('[data-direction]')) {
  const step = button.dataset.direction.split(',').map(Number);
  button.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || aimCell || isPaused(play.frame?.state)) return;
    event.preventDefault();
    button.setPointerCapture(event.pointerId);
    held.set(event.pointerId, step);
    changeDirection(performance.now());
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    button.addEventListener(type, (event) => {
      if (held.delete(event.pointerId)) changeDirection(performance.now(), true);
    });
  }
  button.addEventListener('click', (event) => {
    if (event.detail !== 0 || aimCell) return;
    if (play.frame) moveDungeon(...step, true, performance.now());
    else moveInn(...step, performance.now());
  });
}

function render(now) {
  movement(now);
  board.draw(visualTime(now), selected, destination, aimCell, play.frame?.serverTime());
  updateCombatUi();
  updateNotice(now);
  requestAnimationFrame(render);
}

buildBackpack();
updateScene();
requestAnimationFrame(render);
