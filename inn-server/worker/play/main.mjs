// SPDX-License-Identifier: GPL-3.0-or-later
import { BattleRules } from './battle.mjs';
import { Board, canStep, canStrike, nextToward } from './board.mjs';
import { PRISON } from './layout.mjs';
import { PlaySession } from './session.mjs';

const byId = (id) => document.getElementById(id);
const board = new Board(byId('board'));
const fields = Object.fromEntries([
  'scene-title', 'connection', 'board-title', 'board-status', 'help', 'help-toggle', 'battle-overlay', 'notice',
  'inn-panel', 'name-heading', 'name-form', 'player-name', 'name-button', 'dungeon-theme', 'match', 'guide', 'drink', 'seekers',
  'chat-form', 'chat-message', 'chat-send', 'chat-log', 'dungeon-panel', 'party', 'queued', 'attack', 'eat', 'return',
  'touch-controls', 'touch-battle', 'touch-attack', 'touch-eat',
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
const held = new Map();
const busy = new Set();
const party = new Map();
let direction;
let tap;
let destination;
let selected;
let submitted;
let queuedPreview;
let lastMoveAt = 0;
let noticeUntil = 0;
let localNotice = '';
let connectionNotice = '';
let lastBattle;
let chatKey = '';
let seekersKey = '';

const play = new PlaySession({
  onInn(state) {
    if (play.frame) return;
    board.set(state, false);
    updateInn();
  },
  onBattle(frame) {
    if (!frame) {
      clearMovement();
      lastBattle = selected = submitted = queuedPreview = null;
      board.set(play.inn, false);
      updateScene();
      return;
    }
    const state = frame.state;
    const prior = lastBattle;
    if (prior?.id !== state.id) {
      clearMovement();
      selected = submitted = queuedPreview = null;
      party.clear();
      fields.party.replaceChildren();
    }
    board.set(state, true);
    if (submitted && state.commandAck > submitted.ack) {
      const actor = ownActor(state);
      const old = ownActor(prior);
      if (submitted.type === 'move' && actor && old && actor.x === old.x && actor.y === old.y &&
          !sameAction(state.queued, submitted)) board.cancel(performance.now());
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

function updateScene() {
  const dungeon = Boolean(play.frame);
  const title = dungeon ? (play.frame.state.theme === PRISON ? '버려진 감옥' : '하수도') : '여관';
  fields['inn-panel'].hidden = dungeon;
  fields['dungeon-panel'].hidden = !dungeon;
  fields['touch-battle'].hidden = !dungeon;
  text(fields['scene-title'], title);
  text(fields['board-title'], dungeon ? `${title} 던전` : '여관 홀');
  const tabTitle = `${title} · Noxac Pixel Dungeon`;
  if (document.title !== tabTitle) document.title = tabTitle;
  board.canvas.setAttribute('aria-label', dungeon
    ? `${title} 지도. 방향키로 이동하고 스페이스로 평타를 사용합니다.` : '여관 지도. 방향키나 WASD로 이동합니다.');
  if (dungeon) updateBattle();
  else updateInn();
}

function updateInn() {
  const state = play.inn;
  const online = Boolean(play.online && state);
  const entering = Boolean(play.battle || state?.match);
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
  fields['dungeon-theme'].value = play.theme;
  fields['dungeon-theme'].disabled = queued || entering || returning || busy.has('match');
  text(fields.match, queued ? '동료 찾는 중 · 취소' : '동료 찾기');
  fields.match.dataset.waiting = String(queued);
  fields.match.disabled = !ready || busy.has('match');
  fields.drink.disabled = !ready || busy.has('drink');
  fields.guide.disabled = !ready;
  fields['chat-message'].disabled = !ready;
  fields['chat-send'].disabled = !ready || busy.has('chat');
  fields['battle-overlay'].hidden = !entering && !returning;
  if (entering || returning) text(fields['battle-overlay'], returning ? '여관으로 돌아가는 중…' : '던전에 들어가는 중…');
  updateSeekers(state, ready);
  updateChat(state?.events ?? []);
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

function updateChat(events) {
  const recent = events.slice(-24);
  const log = fields['chat-log'];
  const key = recent.map((event) => event.id).join(',');
  if (key === chatKey) return;
  chatKey = key;
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
  return !busy.has('return') && actor?.hp > 0 && !state.waitingForPartner &&
    (state.status === 'active' || state.status === 'won');
}

function updateBattle() {
  const { state, connection } = play.frame;
  const player = ownActor(state);
  const enemies = state.actors.filter((actor) => actor.kind === 'enemy' && actor.hp > 0).length;
  status(({ direct: 'P2P 직접 연결', relay: '중계 연결', local: '길잡이 훈련' })[connection], 'live');
  text(fields['board-status'], state.status === 'won' ? '소탕 완료' : `${state.inCombat ? '교전' : '탐색'} · 남은 적 ${enemies}`);
  const overlay = state.status === 'lost' ? '파티가 쓰러졌습니다' : state.status === 'closed' ? '원정이 끝났습니다' :
    state.waitingForPartner ? '동료가 들어오는 중…' : player?.hp === 0 ? '쓰러졌습니다 · 동료의 전투를 지켜봅니다' : '';
  fields['touch-controls'].hidden = !canAct(state, player);
  fields['battle-overlay'].hidden = !overlay;
  text(fields['battle-overlay'], overlay);
  const queued = state.queued;
  fields.queued.hidden = !queued;
  if (queued) text(fields.queued, `예약 · ${{ move: '이동', attack: '평타', eat: '식량' }[queued.type]}`);
  fields.return.disabled = busy.has('return');
  updateParty(state);
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
      const stats = document.createElement('div');
      const health = document.createElement('span');
      const energy = document.createElement('span');
      const hpMeter = document.createElement('progress');
      const energyMeter = document.createElement('progress');
      root.className = 'party-slot';
      stats.className = 'stats';
      energy.className = 'energy-text';
      energyMeter.className = 'energy';
      hpMeter.setAttribute('aria-label', '체력');
      energyMeter.setAttribute('aria-label', '행동력');
      energyMeter.max = BattleRules.ENERGY_CAP;
      if (own) name.className = 'own';
      stats.append(health, energy);
      root.append(name, stats, hpMeter, energyMeter);
      fields.party.append(root);
      slot = { root, name, health, energy, hpMeter, energyMeter };
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
  }
  const player = ownActor(state);
  const active = canAct(state, player);
  const attacking = active && state.status === 'active';
  const remaining = attacking ? Math.max(0, player.attackReadyAt - now) : 0;
  const attackWait = attacking && BattleRules.readyAt(player, 'attack', state.now, state.inCombat) > now;
  const attackLabel = remaining > 0 ? `평타 ${(Math.ceil(remaining / 100) / 10).toFixed(1)}초` :
    attackWait ? `평타 · 행동력 ${BattleRules.ATTACK_COST}` : '평타';
  buttonLabel(fields.attack, attackLabel);
  fields.attack.dataset.waiting = String(attackWait);
  fields.attack.disabled = fields['touch-attack'].disabled = !attacking;
  buttonLabel(fields.eat, `식량 ${player?.rations ?? 0}`);
  fields.eat.disabled = fields['touch-eat'].disabled = !active || !player.rations || player.hp >= player.maxHp;
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
  if (!canAct(state, player) || state.status !== 'active') return;
  let target = state.actors.find((actor) => actor.id === (targetId ?? selected));
  if (!inReach(state, player, target) && !targetId) target = state.actors.find((actor) => inReach(state, player, actor));
  if (!inReach(state, player, target)) {
    flash('가까운 적에게 평타를 쓸 수 있습니다.');
    return;
  }
  selected = target.id;
  requestAction('attack', 0, 0, target.id, performance.now());
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
  if (ready && state.queued?.type !== 'move') board.predict(dx, dy, now);
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
      board.predict(queued.dx, queued.dy, now);
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
fields.match.addEventListener('click', () => void match());
fields.drink.addEventListener('click', () => void run('drink', async () => { await play.drink(); focusBoard(); }));
fields.guide.addEventListener('click', () => { if (play.practice()) focusBoard(); });
fields.attack.addEventListener('click', () => { attack(); focusBoard(); });
fields.eat.addEventListener('click', () => { eat(); focusBoard(); });
fields['touch-attack'].addEventListener('click', () => attack());
fields['touch-eat'].addEventListener('click', eat);
fields.return.addEventListener('click', () => void run('return', async () => { await play.returnToInn(); focusBoard(); }));
fields['help-toggle'].addEventListener('click', toggleHelp);
board.canvas.addEventListener('click', (event) => {
  const [px, py] = board.point(event);
  const [x, y] = board.cellAt(px, py);
  focusBoard();
  if (!play.frame) {
    const id = board.signAt(px, py);
    if (id) void match(id);
    else if (['.', 'D'].includes(play.inn?.map[y]?.[x])) destination = [x, y];
    return;
  }
  const state = play.frame.state;
  const player = ownActor(state);
  if (!canAct(state, player)) return;
  const target = state.actors.find((actor) => actor.kind === 'enemy' && actor.hp > 0 && actor.x === x && actor.y === y);
  if (target) {
    selected = target.id;
    attack(target.id);
  } else if (Math.max(Math.abs(x - player.x), Math.abs(y - player.y)) === 1) {
    moveDungeon(x - player.x, y - player.y, true, performance.now());
  }
});

function typing(target) { return target instanceof HTMLElement && (target.matches('input, textarea, select') || target.isContentEditable); }

window.addEventListener('keydown', (event) => {
  if (event.code === 'F1' && !event.repeat) { event.preventDefault(); toggleHelp(); return; }
  if (event.altKey || event.ctrlKey || event.metaKey || typing(event.target)) return;
  const step = keys.get(event.code);
  if (step) {
    event.preventDefault();
    if (!held.has(event.code)) { held.set(event.code, step); changeDirection(performance.now()); }
  } else if (play.frame && !event.repeat) {
    if (event.code === 'Space' && !event.target?.closest?.('button')) { event.preventDefault(); attack(); }
    if (event.code === 'KeyE') { event.preventDefault(); eat(); }
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
    if (event.button !== 0) return;
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
    if (event.detail !== 0) return;
    if (play.frame) moveDungeon(...step, true, performance.now());
    else moveInn(...step, performance.now());
  });
}

function render(now) {
  movement(now);
  board.draw(now, selected, destination);
  updateCombatUi();
  updateNotice(now);
  requestAnimationFrame(render);
}

updateScene();
requestAnimationFrame(render);
