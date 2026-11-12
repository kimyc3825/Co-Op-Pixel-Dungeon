// SPDX-License-Identifier: GPL-3.0-or-later
import { BattleRules, PRISON } from './rules.mjs';
import { Board, DIRECTIONS, canStep, canStrike, nextToward } from './board.mjs';
import { OriginalGame, warmOriginalRuntime } from './original.mjs';
import { PlaySession } from './session.mjs';
import { AvatarCatalog, normalizeAppearance } from './avatar-catalog.mjs';
import { drawAvatar, getAvatar } from './avatar.mjs';
import { loadBattleRuntime } from './runtime-loader.mjs';

const byId = (id) => document.getElementById(id);
const board = new Board(byId('board'));
const fields = Object.fromEntries([
  'scene-title', 'connection', 'board-title', 'board-status', 'help', 'help-toggle', 'pause', 'battle-overlay', 'notice',
  'inn-panel', 'name-heading', 'name-form', 'player-name', 'name-button', 'dungeon-theme', 'friendly-fire', 'match', 'guide', 'solo', 'drink', 'seekers',
  'profile-preview', 'profile-summary', 'profile-name', 'profile-job-label', 'profile-change', 'profile-cancel', 'profile-notice',
  'profile-job', 'profile-hair-style', 'profile-hair-color', 'profile-hair-swatch', 'profile-outfit-color', 'profile-outfit-swatch', 'profile-accessory',
  'chat-message', 'chat-send', 'chat-log', 'dungeon-panel', 'party', 'party-rules', 'queued', 'attack', 'shoot', 'eat', 'return',
  'touch-controls', 'touch-battle', 'touch-attack', 'touch-shoot', 'touch-eat', 'backpack', 'backpack-hint', 'attack-log', 'aim-hint',
  'dungeon-chat-log', 'dungeon-chat-message', 'dungeon-chat-send',
  'inn-dialogue', 'npc-name', 'npc-dialogue', 'npc-close',
  'loading-overlay', 'loading-ember', 'loading-phase', 'loading-tip', 'loading-retry', 'loading-close',
  'original-launch', 'expedition-window', 'expedition-toggle', 'backpack-window', 'backpack-toggle', 'chat-toggle', 'hud-scrim',
].map((id) => [id, byId(id)]));

const trainingHint = document.createElement('p');
trainingHint.className = 'party-rules';
trainingHint.textContent = '허수아비로 평타와 사격을 연습하세요. 식량 상자는 식량을 3개까지 채우고, 회복 도구는 체력과 행동력을 회복합니다. 도구가 놓인 칸을 밟으면 작동합니다.';
trainingHint.hidden = true;
fields['party-rules'].after(trainingHint);

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
const INN_TAP_MS = 350;
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
  '경비병이 여분의 열쇠를 세는 중…',
  '빵을 나누려다 한 조각을 더 먹는 중…',
  '하수도 쥐가 치즈를 임시 보관하는 중…',
  '낡은 지도에 물에 젖은 계단을 표시하는 중…',
  '음유시인이 아직 일어나지 않은 일을 노래하는 중…',
  '허수아비가 자기 앞의 발자국을 세는 중…',
  '여관주인이 원정 외상 장부를 덮는 중…',
  '마법사가 두루마리의 앞뒤를 확인하는 중…',
  '전사가 갑옷 속에서 양말을 찾는 중…',
  '활시위를 조이고 첫 화살을 고르는 중…',
  '감옥 쥐가 면회를 신청하는 중…',
  '배낭에서 빈 물약병이 달그락거리는 중…',
  '노점상이 지도에 난 구멍을 할인해 주는 중…',
  '사냥꾼이 모자를 두고 온 걸 깨닫는 중…',
  '도적이 문 손잡이에 귀를 대보는 중…',
  '길잡이가 잠깐 쉰다는 말을 연습하는 중…',
];
const held = new Map();
const busy = new Set();
const party = new Map();
const chatInputs = new Set([fields['chat-message'], fields['dungeon-chat-message']]);
const composingChat = new WeakSet();
const skipChatSubmit = new WeakSet();
const profileFields = [
  { input: fields['profile-job'], field: 'job', options: AvatarCatalog.jobs },
  { input: fields['profile-hair-style'], field: 'hairStyle', options: AvatarCatalog.hairStyles },
  { input: fields['profile-hair-color'], field: 'hairColor', options: AvatarCatalog.hairColors, swatch: fields['profile-hair-swatch'] },
  { input: fields['profile-outfit-color'], field: 'outfitColor', options: AvatarCatalog.outfitColors, swatch: fields['profile-outfit-swatch'] },
  { input: fields['profile-accessory'], field: 'accessory', options: AvatarCatalog.accessories },
];
const profileCard = fields['name-form'].closest('.profile-card');
const layout = profileCard.closest('.layout');
const gameWindows = [
  [fields['expedition-window'], fields['expedition-toggle']],
  [fields['backpack-window'], fields['backpack-toggle']],
];
const preview = fields['profile-preview'];
const previewContext = preview.getContext('2d');
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
let savedProfile = loadProfile();
let profileOpen = !savedProfile.name.trim();
let hudDungeon = false;
let previewAvatar;
let lastPreviewAvatar;
let lastPreviewFrame = -1;
const itemNames = { ration: '식량', gold: '금화' };
const backpack = ['ration', 'gold', null, null, null, null];
const slotViews = [];
let direction;
let tap;
let destination;
let dungeonRoute = null;
let interruptedRoute = null;
let selected;
let aimCell = null;
let examining = false;
let submitted;
let queuedPreview;
let waitingAction;
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
const boardTouches = new Map();
let boardDrag = null;
let pinchDistance = 0;
let ignoreBoardClickUntil = 0;
let loading = null;
let loadingTipIndex = Math.floor(Math.random() * LOADING_TIPS.length);
let loadingTipTimer = null;

const play = new PlaySession({
  onInn(state, moved) {
    if (play.frame) return;
    if (moved) {
      board.moveInn(state, moved);
      return;
    }
    board.set(state, false);
    updateInn();
  },
  onBattle(frame) {
    if (!frame) {
      clearMovement();
      setAim(null);
      setExamining(false);
      interruptedRoute = null;
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
      setExamining(false);
      interruptedRoute = null;
      showDialogue();
      selected = submitted = queuedPreview = null;
      selectedSlot = -1;
      party.clear();
      fields.party.replaceChildren();
      fields['dungeon-chat-message'].value = '';
      fields['dungeon-chat-log'].replaceChildren();
      delete fields['dungeon-chat-log'].dataset.after;
    }
    const artChanged = prior?.id !== state.id || board.assetVersion !== frame.assetVersion;
    const now = performance.now();
    syncVisualClock(isPaused(state), now);
    board.set(state, true, visualTime(now), frame.assetVersion, frame.wallTime());
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
    showTrainingUse(state, prior);
    if (prior?.id === state.id && dungeonRoute) {
      const previous = ownActor(prior);
      const player = ownActor(state);
      if (player?.hp < previous?.hp || dungeonRoute.type !== 'attack' && !prior.inCombat && state.inCombat) {
        stopAndRememberRoute();
      }
    }
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

const original = new OriginalGame({
  suspend: () => play.suspendInn(),
  resume: () => play.resumeInn(),
  canLaunch: canLaunchOriginal,
  getProfile: ownProfile,
  onChange(active) {
    clearMovement();
    setExamining(false);
    showDialogue();
    updateScene();
    if (!active) focusBoard();
  },
  tips: LOADING_TIPS,
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
    stopAndRememberRoute();
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

function saveProfile(profile) {
  savedProfile = profile;
  try { localStorage.setItem('noxac.playerProfile', JSON.stringify(profile)); }
  catch { /* The game can run without persistent storage. */ }
}

function loadProfile() {
  let name = '';
  let appearance;
  try {
    name = localStorage.getItem('noxac.playerName') ?? '';
    const profile = JSON.parse(localStorage.getItem('noxac.playerProfile'));
    if (typeof profile?.name === 'string') name = profile.name;
    appearance = profile?.appearance;
  } catch { /* Retain defaults if preferences are unavailable. */ }
  return { name, appearance: normalizeAppearance(appearance) };
}

function readAppearance() {
  return normalizeAppearance(Object.fromEntries(profileFields.map(({ input, field }) => [field, input.value])));
}

function ownProfile(state = play.inn) {
  const person = state?.people.find(({ id }) => id === state.selfId);
  return person ? { name: person.name, appearance: normalizeAppearance(person.appearance) } : savedProfile;
}

function setPreview(appearance) {
  previewAvatar = getAvatar(appearance);
  const job = AvatarCatalog.jobs.find(({ id }) => id === appearance.job);
  preview.setAttribute('aria-label', `${job.label} 아바타 미리보기`);
  for (const { field, options, swatch } of profileFields) {
    if (!swatch) continue;
    const color = options.find(({ id }) => id === appearance[field]).swatch;
    if (color) swatch.style.setProperty('--swatch', color);
    else swatch.style.removeProperty('--swatch');
  }
}

function profileNotice(message = '') {
  text(fields['profile-notice'], message);
  fields['profile-notice'].hidden = !message;
}

function writeProfile(profile) {
  fields['player-name'].value = profile.name;
  for (const { input, field } of profileFields) input.value = profile.appearance[field];
  setPreview(profile.appearance);
  profileNotice();
}

function updateProfile(state, online, locked) {
  layout.dataset.profileEntry = String(!online && profileOpen);
  fields['profile-summary'].hidden = !online || profileOpen;
  fields['profile-cancel'].hidden = !online;
  fields.drink.hidden = !online || profileOpen;
  fields['profile-change'].disabled = !online || locked;
  fields['profile-cancel'].disabled = busy.has('name');
  fields['name-button'].disabled = locked;
  fields['player-name'].disabled = locked;
  for (const { input } of profileFields) input.disabled = locked;
  text(fields['name-heading'], online ? '프로필 변경' : '캐릭터 생성');
  text(fields['name-button'], online ? '저장' : '여관에 입장');
  if (profileOpen && !profileCard.open) profileCard.showModal();
  else if (!profileOpen && profileCard.open) profileCard.close();
  if (profileOpen) return;
  const profile = ownProfile(state);
  text(fields['profile-name'], profile.name);
  text(fields['profile-job-label'], AvatarCatalog.jobs.find(({ id }) => id === profile.appearance.job).label);
  setPreview(profile.appearance);
}

function drawProfile(now) {
  const frame = !reduceMotion.matches && Math.floor(now / 620) % 4 === 3 ? 1 : 0;
  if (lastPreviewAvatar === previewAvatar && lastPreviewFrame === frame && previewAvatar?.sheet) return;
  previewContext.clearRect(0, 0, preview.width, preview.height);
  previewContext.fillStyle = '#191b16a8';
  previewContext.fillRect(5, 16, 10, 2);
  if (drawAvatar(previewContext, previewAvatar, frame, 4, 2, 12, 15)) {
    lastPreviewAvatar = previewAvatar;
    lastPreviewFrame = frame;
  }
}

function focusBoard() { board.canvas.focus({ preventScroll: true }); }

function closePartyDetails() {
  for (const slot of party.values()) {
    slot.details.hidden = true;
    slot.inspect.setAttribute('aria-expanded', 'false');
    text(slot.inspectLabel, '상태');
  }
}

function closeGameWindows() {
  for (const [panel, button] of gameWindows) {
    panel.hidden = true;
    button.setAttribute('aria-expanded', 'false');
  }
  fields['hud-scrim'].hidden = true;
  closePartyDetails();
}

function toggleGameWindow(index) {
  const [panel, button] = gameWindows[index];
  const open = panel.hidden;
  closeGameWindows();
  if (open) {
    clearMovement();
    panel.hidden = false;
    fields['hud-scrim'].hidden = false;
    button.setAttribute('aria-expanded', 'true');
    panel.querySelector('button').focus({ preventScroll: true });
  } else {
    button.focus({ preventScroll: true });
  }
}

function toggleBackpack() {
  if (play.frame) toggleGameWindow(1);
}

function toggleHeroStatus() {
  const slot = party.get(ownActor()?.id);
  if (slot) slot.inspect.click();
}

function gameUiBlocksMovement() {
  return profileCard.open || gameWindows.some(([panel]) => !panel.hidden) ||
    [...party.values()].some(({ details }) => !details.hidden);
}

function chatFocused() { return chatInputs.has(document.activeElement); }

function preserveChatFocus(event) {
  if (chatFocused()) event.preventDefault();
}

function isRuntimeLoading() {
  return loading?.phase === 'checking' || loading?.phase === 'loading';
}

function canLaunchOriginal() {
  return play.online && !profileOpen && !play.frame && !play.battle && !play.inn?.match && !play.inn?.queued && !isRuntimeLoading() &&
    !busy.has('match') && !busy.has('name') && !busy.has('return');
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
      closeGameWindows();
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
      text(fields['loading-retry'], state.mode ? '다시 시도' : '다시 매칭');
    }
  }
  updateScene();
}

function updateScene() {
  const dungeon = Boolean(play.frame);
  if (dungeon !== hudDungeon) {
    hudDungeon = dungeon;
    closeGameWindows();
  }
  const title = dungeon ? (play.frame.state.theme === PRISON ? '버려진 감옥' : '하수도') : '여관';
  fields['inn-panel'].hidden = dungeon;
  fields['dungeon-panel'].hidden = !dungeon;
  fields['touch-battle'].hidden = !dungeon;
  fields.pause.hidden = !dungeon;
  board.canvas.dataset.paused = String(isPaused(play.frame?.state));
  text(fields['scene-title'], title);
  text(fields['board-title'], dungeon ? `${title} 던전` : '여관 홀');
  const tabTitle = `${original.active ? '원본 녹픽던' : title} · Noxac Pixel Dungeon`;
  if (document.title !== tabTitle) document.title = tabTitle;
  board.canvas.setAttribute('aria-label', dungeon
    ? `${title} 지도. 클릭이나 방향키로 이동하고 Q로 평타, 1로 활을 조준합니다.` : '여관 지도. 클릭이나 방향키, WASD로 이동합니다.');
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
  const queued = Boolean(state?.queued);
  const rulesLocked = queued || entering || returning || busy.has('match');
  updateProfile(state, online, rulesLocked || busy.has('name'));
  fields['dungeon-theme'].value = play.theme;
  fields['dungeon-theme'].disabled = rulesLocked;
  fields['friendly-fire'].checked = play.friendlyFire === true;
  fields['friendly-fire'].disabled = rulesLocked;
  text(fields.match, queued ? '동료 찾는 중 · 취소' : '동료 찾기');
  fields.match.dataset.waiting = String(queued);
  fields['expedition-toggle'].dataset.waiting = String(queued);
  text(fields['expedition-toggle'], queued ? '동료 찾는 중' : '원정');
  fields.match.disabled = !ready || busy.has('match');
  fields.drink.disabled = !ready || busy.has('drink');
  fields.guide.disabled = !ready || rulesLocked || profileOpen;
  fields.solo.disabled = original.active || !canLaunchOriginal();
  fields['original-launch'].disabled = original.active || !canLaunchOriginal();
  fields['chat-message'].disabled = !ready;
  fields['chat-send'].disabled = !ready || busy.has('chat');
  fields['chat-toggle'].disabled = !ready;
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
  const after = Number(log.dataset.after) || 0;
  const fresh = events.filter(({ id }) => id > after);
  if (!fresh.length) return;
  log.dataset.after = String(fresh.at(-1).id);
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 24;
  log.append(...fresh.map((event) => {
    const row = document.createElement('li');
    const who = document.createElement('strong');
    who.textContent = event.name;
    if (event.kind === 'drink') row.classList.add('drink');
    row.append(who, document.createTextNode(event.kind === 'drink' ? '님이 술을 마셨습니다.' : event.text ?? ''));
    return row;
  }));
  while (log.childElementCount > 80) log.firstElementChild.remove();
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
  const practice = Boolean(state.trainingTools?.length);
  const solo = connection === 'local' && !practice;
  status(({ direct: 'P2P 직접 연결', relay: '중계 연결', local: practice ? '길잡이 연습' : '1인 원정' })[connection], 'live');
  text(fields['board-status'], state.status === 'won' ? '소탕 완료' : practice && enemies === 0 ? '소탕 완료 · 표적 연습' :
    `${state.inCombat ? '교전' : '탐색'} · 남은 적 ${enemies}`);
  trainingHint.hidden = !practice;
  const overlay = state.status === 'lost' ? (solo ? '쓰러졌습니다' : '파티가 쓰러졌습니다') : state.status === 'closed' ? '원정이 끝났습니다' :
    state.waitingForPartner ? '동료가 들어오는 중…' : player?.hp === 0 ? '쓰러졌습니다 · 동료의 전투를 지켜봅니다' : '';
  fields['touch-controls'].hidden = !canAct(state, player);
  fields['battle-overlay'].hidden = !overlay;
  text(fields['battle-overlay'], overlay);
  const queued = state.queued;
  if (!canAttack(state, player) && aimCell) setAim(null);
  fields.queued.hidden = !queued;
  if (queued) text(fields.queued, `예약 · ${{ move: '이동', attack: '평타', shoot: '사격', eat: '식량' }[queued.type]}`);
  text(fields['party-rules'], state.friendlyFire ? '아군 공격 허용 · 동료를 클릭해 평타' : '아군 공격 차단');
  fields['party-rules'].hidden = solo;
  fields['party-rules'].dataset.enabled = String(state.friendlyFire === true);
  fields.return.disabled = busy.has('return');
  fields['dungeon-chat-message'].disabled = fields['dungeon-chat-send'].disabled = !canChat(state);
  fields['chat-toggle'].disabled = !canChat(state);
  updateChat(fields['dungeon-chat-log'], state.messages ?? []);
  updateParty(state);
  updateBackpack();
  updateCombatUi();
}

function updateParty(state) {
  const actors = state.actors.filter((actor) => actor.kind === 'player' || actor.kind === 'guide').sort((left, right) =>
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
        const open = details.hidden;
        closeGameWindows();
        if (open) {
          clearMovement();
          details.hidden = false;
          inspect.setAttribute('aria-expanded', 'true');
          text(inspectLabel, '닫기');
        } else focusBoard();
        updateCombatUi();
      });
      inspect.append(name, inspectLabel);
      stats.append(health, energy);
      root.append(inspect, stats, hpMeter, energyMeter, details);
      fields.party.append(root);
      slot = { root, name, health, energy, hpMeter, energyMeter, details, detailValues, inspect, inspectLabel };
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
    const ratio = energy / BattleRules.ENERGY_CAP;
    const band = ratio > .5 ? 'high' : ratio > .25 ? 'low' : 'critical';
    if (slot.root.dataset.energy !== band) slot.root.dataset.energy = band;
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

function showTrainingUse(state, prior) {
  if (!state.trainingTools?.length || prior?.id !== state.id) return;
  const player = ownActor(state);
  const previous = ownActor(prior);
  if (!player || !previous || player.lastAction !== 'move' ||
      (player.x === previous.x && player.y === previous.y)) return;
  const tool = state.trainingTools.find(({ x, y }) => player.x === x && player.y === y);
  if (tool?.id === 'ration-rack') flash(`식량 상자 · 식량 ${player.rations}개`);
  if (tool?.id === 'recovery-kit') flash('회복 도구 · 체력과 행동력 회복');
}

function match(id) {
  return run('match', async () => {
    if (id) await play.match(id);
    else if (play.inn?.queued) await play.cancelMatch();
    else await play.match();
    focusBoard();
  });
}

function isAttackTarget(actor) {
  return actor?.kind === 'enemy' || actor?.kind === 'dummy';
}

function selectableTarget(state, actor) {
  return actor?.hp > 0 && actor.id !== state.selfId && (isAttackTarget(actor) || state.friendlyFire);
}

function inReach(state, player, target) {
  if (!selectableTarget(state, target)) return false;
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
  clearMovement();
  setExamining(false);
  setAim(null);
  const selectedTarget = state.actors.find((actor) => actor.id === (targetId ?? selected));
  const target = targetId ? selectedTarget : inReach(state, player, selectedTarget) ? selectedTarget :
    state.actors.find((actor) => isAttackTarget(actor) && inReach(state, player, actor));
  if (!selectableTarget(state, target)) {
    flash(state.trainingTools?.length ? '가까운 허수아비나 적에게 평타를 쓸 수 있습니다.' : '가까운 적에게 평타를 쓸 수 있습니다.');
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
  setExamining(false);
  const target = state.actors.find((actor) => actor.id === selected && isAttackTarget(actor) && actor.hp > 0) ?? player;
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
  clearMovement();
  setAim(null);
  setExamining(false);
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
  const occupied = state.actors.some((actor) => actor.hp > 0 && actor.x === player.x + dx && actor.y === player.y + dy);
  if (!ready && !occupied) waitingAction = 'move';
  const pending = submitted && now - submitted.sent < ACK_WAIT;
  if (!fresh && (pending || readyAt > gameNow + MOVE_QUEUE_LEAD ||
      state.queued && (state.queued.type === 'move' || !ready))) return;
  if (pending && submitted.type !== 'move' && now - submitted.sent < MOVE_RETRY) return;
  if (now - lastMoveAt < MOVE_RETRY) return;
  if (ready && occupied) return;
  if (!requestAction('move', dx, dy, null, now)) return;
  lastMoveAt = now;
  if (ready && state.queued?.type !== 'move') board.predict(dx, dy, visualTime(now));
}

function clearMovement() {
  held.clear();
  direction = tap = destination = null;
  dungeonRoute = null;
  lastMoveAt = 0;
}

function stopAndRememberRoute() {
  const route = dungeonRoute;
  clearMovement();
  if (route) interruptedRoute = route;
}

function beginDungeonRoute(route) {
  clearMovement();
  dungeonRoute = route;
  interruptedRoute = null;
  if (route.type === 'attack') selected = route.target;
  followDungeonRoute(performance.now());
}

function findDungeonPath(state, player, reached) {
  const { map } = state;
  const width = map[0].length;
  const occupied = new Uint8Array(width * map.length);
  for (const actor of state.actors) {
    if (actor.hp > 0 && actor.id !== player.id) occupied[actor.y * width + actor.x] = 1;
  }
  const seen = new Uint8Array(occupied.length);
  const queue = [{ x: player.x, y: player.y, first: null }];
  seen[player.y * width + player.x] = 1;
  for (let index = 0; index < queue.length; index++) {
    const { x, y, first } = queue[index];
    if (first && reached(x, y)) return { x, y, first };
    for (const step of DIRECTIONS) {
      const [dx, dy] = step;
      if (!canStep(map, x, y, dx, dy, true)) continue;
      const nx = x + dx;
      const ny = y + dy;
      const key = ny * width + nx;
      if (occupied[key] || seen[key]) continue;
      seen[key] = 1;
      queue.push({ x: nx, y: ny, first: first ?? step });
    }
  }
  return null;
}

function followDungeonRoute(now) {
  const frame = play.frame;
  const state = frame?.state;
  const player = ownActor(state);
  const route = dungeonRoute;
  if (!route || !canAct(state, player) || state.queued || submitted && now - submitted.sent < ACK_WAIT) return;

  let reached;
  let availableDrops;
  if (route.type === 'attack') {
    const target = state.actors.find(({ id }) => id === route.target);
    if (!canAttack(state, player) || !selectableTarget(state, target)) {
      dungeonRoute = null;
      return;
    }
    reached = (x, y) => canStrike(state.map, x, y, target.x - x, target.y - y);
    if (reached(player.x, player.y)) {
      if (BattleRules.readyAt(player, 'attack', state.now, state.inCombat) <= frame.serverTime() &&
          requestAction('attack', 0, 0, target.id, now)) dungeonRoute = null;
      else waitingAction = 'attack';
      return;
    }
  } else if (route.type === 'loot') {
    availableDrops = state.drops.filter(({ id, pickedBy }) => pickedBy == null && (!route.target || id === route.target));
    if (!availableDrops.length) {
      dungeonRoute = null;
      return;
    }
    reached = (x, y) => availableDrops.some((drop) => drop.x === x && drop.y === y);
  } else {
    reached = (x, y) => x === route.x && y === route.y;
  }

  if (reached(player.x, player.y)) {
    dungeonRoute = null;
    return;
  }
  const gameNow = frame.serverTime();
  const ready = BattleRules.readyAt(player, 'move', state.now, state.inCombat) <= gameNow;
  if (!ready && !BattleRules.lacksEnergy(player, 'move', state.now, gameNow, state.inCombat)) return;
  // Reuse the same path until the party's positions change in the next snapshot.
  if (route.pathState !== state) {
    route.path = findDungeonPath(state, player, reached);
    route.pathState = state;
  }
  const path = route.path;
  if (path) {
    // Send automatic steps only once ready, so stopping cannot leave a queued move at the host.
    if (!ready) {
      waitingAction = 'move';
      return;
    }
    if (availableDrops && !route.target) {
      const drop = availableDrops.find(({ x, y }) => x === path.x && y === path.y);
      route.target = drop.id;
      route.x = path.x;
      route.y = path.y;
    }
    moveDungeon(...path.first, false, now);
  } else if (ready) {
    stopAndRememberRoute();
    flash('이동할 수 있는 길이 없습니다.');
  }
}

function waitingForEnergy(now, gameNow) {
  const state = play.frame?.state;
  const player = ownActor(state);
  if (!canAct(state, player)) return false;
  const pending = submitted && now - submitted.sent < ACK_WAIT ? submitted : null;
  const action = state.queued ?? pending;
  if (action?.type === 'shoot' && !state.queued) {
    const [x, y] = action.target.split(',').map(Number);
    const range = Math.max(Math.abs(player.x - x), Math.abs(player.y - y));
    if (range === 0 || range > BattleRules.SHOOT_RANGE) return false;
  }
  const type = action?.type ?? waitingAction;
  return Boolean(type && BattleRules.lacksEnergy(player, type, state.now, gameNow, state.inCombat));
}

function setExamining(enabled) {
  examining = enabled;
  board.canvas.dataset.examining = String(enabled);
}

function toggleExamine() {
  if (examining) {
    setExamining(false);
    return;
  }
  clearMovement();
  setAim(null);
  setExamining(true);
  flash('살펴볼 칸이나 대상을 선택하세요.');
}

function examineCell(x, y) {
  const state = play.frame?.state ?? play.inn;
  const tile = state?.map[y]?.[x];
  if (tile == null) return;
  const actor = (state.actors ?? state.people)?.find((person) => person.x === x && person.y === y && person.hp !== 0);
  const npc = state.npcs?.find((person) => person.x === x && person.y === y);
  const tool = state.trainingTools?.find((item) => item.x === x && item.y === y);
  const drop = state.drops?.find((item) => item.x === x && item.y === y && item.pickedBy == null);
  if (actor) flash(actor.hp == null ? actor.name : `${actor.name} · HP ${actor.hp}/${actor.maxHp}`);
  else if (npc) flash(`${npc.name} · ${npc.dialogue}`);
  else if (tool) flash(tool.id === 'ration-rack' ? '식량 상자 · 식량을 3개까지 채웁니다.' : '회복 도구 · 체력과 행동력이 찹니다.');
  else if (drop) flash(`${itemNames[drop.item] ?? drop.item} · ${drop.amount}개`);
  else flash(tile === '#' ? '벽' : tile === 'D' ? '문' : '통로');
  setExamining(false);
}

function changeDirection(now, releasing = false) {
  const sides = [...held.values()];
  const dx = Number(sides.some((item) => item[0] > 0)) - Number(sides.some((item) => item[0] < 0));
  const dy = Number(sides.some((item) => item[1] > 0)) - Number(sides.some((item) => item[1] < 0));
  if (direction?.dx === dx && direction?.dy === dy) return;
  if (!dx && !dy) {
    if (!held.size && direction?.fresh && direction.tap) tap = { ...direction, expires: now + INN_TAP_MS };
    direction = null;
    return;
  }
  const cardinalRelease = releasing && direction?.dx && direction?.dy && !(dx && dy);
  const chordWait = !(dx && dy) && (play.frame || !direction) ? 75 : 0;
  tap = !play.frame && cardinalRelease && direction.fresh ? { ...direction, expires: now + INN_TAP_MS } : null;
  direction = { dx, dy, at: now + (cardinalRelease ? 45 : chordWait), fresh: true, tap: !cardinalRelease };
  destination = null;
  dungeonRoute = interruptedRoute = null;
}

function movement(now) {
  if (aimCell || examining || gameUiBlocksMovement() || isRuntimeLoading() ||
      isPaused(play.frame?.state) || typing(document.activeElement)) return;
  if (!play.frame && tap?.expires <= now) tap = null;
  const command = tap ?? (direction?.at <= now ? direction : null);
  if (command) {
    if (play.frame && tap && now - lastMoveAt < MOVE_RETRY) return;
    if (play.frame) moveDungeon(command.dx, command.dy, command.fresh, now);
    else if (play.moveInn(command.dx, command.dy) === 'waiting') return;
    command.fresh = false;
    tap = null;
  } else if (play.frame && !held.size) {
    followDungeonRoute(now);
  } else if (!held.size && destination && play.inn) {
    const state = play.inn;
    const player = state.people.find((person) => person.id === state.selfId);
    const step = player && nextToward(state.map, player.x, player.y, ...destination);
    if (step) play.moveInn(...step);
    else if (!play.innMovePending) destination = null;
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

for (const { input, options } of profileFields) {
  input.replaceChildren(...options.map(({ id, label }) => new Option(label, id)));
  input.addEventListener('change', () => setPreview(readAppearance()));
}
writeProfile(savedProfile);
fields['profile-change'].addEventListener('click', () => {
  closeGameWindows();
  clearMovement();
  writeProfile(ownProfile());
  profileOpen = true;
  updateInn();
  fields['player-name'].focus();
});
fields['profile-cancel'].addEventListener('click', () => {
  profileOpen = false;
  updateInn();
  if (fields['profile-change'].disabled) focusBoard();
  else fields['profile-change'].focus();
});
profileCard.addEventListener('cancel', (event) => {
  event.preventDefault();
  if (!fields['profile-cancel'].hidden && !fields['profile-cancel'].disabled) fields['profile-cancel'].click();
});
fields['expedition-toggle'].addEventListener('click', () => toggleGameWindow(0));
fields['backpack-toggle'].addEventListener('click', toggleBackpack);
for (const close of document.querySelectorAll('[data-close-window]')) {
  close.addEventListener('click', () => toggleGameWindow(close.dataset.closeWindow === 'expedition' ? 0 : 1));
}
fields['hud-scrim'].addEventListener('click', () => { closeGameWindows(); focusBoard(); });
document.addEventListener('keydown', (event) => {
  const editing = typing(event.target);
  const close = event.code === 'Escape' || event.key === 'Escape' ||
    (event.code === 'Backspace' || event.key === 'Backspace') && !editing;
  if (!close || profileCard.open || !gameUiBlocksMovement()) return;
  if (editing && (event.isComposing || event.keyCode === 229 || composingChat.has(event.target))) return;
  event.preventDefault();
  event.stopPropagation();
  closeGameWindows();
  focusBoard();
}, { capture: true });
fields['chat-toggle'].addEventListener('click', () => {
  closeGameWindows();
  clearMovement();
  fields[play.frame ? 'dungeon-chat-message' : 'chat-message'].focus({ preventScroll: true });
});
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
  if (fields['name-button'].disabled) return;
  const name = fields['player-name'].value.trim();
  const appearance = readAppearance();
  profileNotice();
  void run('name', async () => {
    if (!await (play.online ? play.rename(name, appearance) : play.join(name, appearance))) {
      profileNotice(connectionNotice || '프로필을 저장하지 못했습니다. 다시 시도해 주세요.');
      return;
    }
    saveProfile(ownProfile());
    profileOpen = false;
    updateInn();
    focusBoard();
  });
});
for (const input of chatInputs) {
  input.addEventListener('focus', () => fields['chat-toggle'].setAttribute('aria-expanded', 'true'));
  input.addEventListener('blur', () => fields['chat-toggle'].setAttribute('aria-expanded', 'false'));
  input.addEventListener('compositionstart', () => composingChat.add(input));
  input.addEventListener('compositionend', () => composingChat.delete(input));
  input.addEventListener('blur', () => composingChat.delete(input));
  input.form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (input.disabled || composingChat.has(input) || skipChatSubmit.has(input)) return;
    const draft = input.value;
    const message = draft.trim();
    if (!message) { board.canvas.focus(); return; }
    if ([...message].length > BattleRules.CHAT_LIMIT || /[\p{C}\p{Zl}\p{Zp}]/u.test(message)) {
      flash(`대화는 한 줄, ${BattleRules.CHAT_LIMIT}자까지 보낼 수 있습니다.`);
      return;
    }
    if (input === fields['dungeon-chat-message']) {
      if (!canChat(play.frame?.state) || !play.action('chat', 0, 0, message)) return;
      input.value = '';
      board.canvas.focus();
      return;
    }
    if (busy.has('chat')) return;
    input.value = '';
    board.canvas.focus();
    void run('chat', async () => {
      if (!await play.chat(message) && !input.value) input.value = draft;
    });
  });
}
fields.match.addEventListener('click', () => void match());
fields.guide.addEventListener('click', () => { if (play.practice()) focusBoard(); });
fields.solo.addEventListener('click', () => original.start(true));
for (const [id, action, returnFocus] of [
  ['drink', () => void run('drink', () => play.drink()), true],
  ['attack', attack, true], ['shoot', toggleAim, true], ['eat', eat, true], ['pause', togglePause, true],
  ['touch-attack', attack, false], ['touch-shoot', toggleAim, true], ['touch-eat', eat, false],
]) {
  fields[id].addEventListener('pointerdown', preserveChatFocus);
  fields[id].addEventListener('click', () => {
    if (chatFocused()) return;
    action();
    if (returnFocus) focusBoard();
  });
}
fields.return.addEventListener('click', () => void run('return', async () => { await play.returnToInn(); focusBoard(); }));
fields['help-toggle'].addEventListener('click', toggleHelp);
fields['npc-close'].addEventListener('click', () => { showDialogue(); focusBoard(); });
fields['loading-retry'].addEventListener('click', () => {
  if (loading?.phase !== 'failed' || fields['loading-retry'].disabled) return;
  const { mode } = loading;
  updateLoading({ phase: 'ready' });
  if (mode === 'practice') play.practice();
  else if (mode === 'solo') play.solo();
  else void match();
  focusBoard();
});
fields['loading-close'].addEventListener('click', () => { updateLoading({ phase: 'ready' }); focusBoard(); });
board.canvas.addEventListener('pointerdown', (event) => {
  preserveChatFocus(event);
  if (gameUiBlocksMovement() || chatFocused()) return;
  if (event.pointerType === 'touch') {
    boardTouches.set(event.pointerId, [event.clientX, event.clientY]);
    if (boardTouches.size >= 2) {
      boardDrag = null;
      pinchDistance = touchSeparation();
      ignoreBoardClickUntil = performance.now() + 350;
      return;
    }
  }
  if (event.button === 0 && !aimCell && !examining) {
    const { clientX, clientY, pointerId } = event;
    boardDrag = { pointerId, startX: clientX, startY: clientY, clientX, clientY, moved: false };
    if (event.isTrusted && !board.canvas.hasPointerCapture(pointerId)) board.canvas.setPointerCapture(pointerId);
  }
});
board.canvas.addEventListener('click', (event) => {
  if (performance.now() < ignoreBoardClickUntil || gameUiBlocksMovement()) return;
  if (chatFocused()) { focusBoard(); return; }
  const [px, py] = board.point(event);
  const [x, y] = board.cellAt(px, py);
  focusBoard();
  if (examining) { examineCell(x, y); return; }
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
  const target = canAttack(state, player) &&
    state.actors.find((actor) => selectableTarget(state, actor) && actor.x === x && actor.y === y);
  if (target) beginDungeonRoute({ type: 'attack', target: target.id });
  else if (x === player.x && y === player.y) stopAndRememberRoute();
  else if (state.map[y]?.[x] === '.') beginDungeonRoute({ type: 'move', x, y });
  else clearMovement();
});

function touchSeparation() {
  const [a, b] = boardTouches.values();
  return b ? Math.hypot(a[0] - b[0], a[1] - b[1]) : 0;
}

board.canvas.addEventListener('pointermove', (event) => {
  if (boardTouches.has(event.pointerId)) {
    boardTouches.set(event.pointerId, [event.clientX, event.clientY]);
    if (boardTouches.size >= 2) {
      const distance = touchSeparation();
      if (pinchDistance > 0 && (distance > pinchDistance * 1.18 || distance < pinchDistance / 1.18)) {
        board.zoomView(distance > pinchDistance ? 1 : -1, performance.now());
        pinchDistance = distance;
      }
      ignoreBoardClickUntil = performance.now() + 350;
    }
  }
  if (boardDrag?.pointerId === event.pointerId) {
    const { clientX, clientY } = event;
    if (boardDrag.moved || Math.hypot(clientX - boardDrag.startX, clientY - boardDrag.startY) >= 8) {
      if (!boardDrag.moved) stopAndRememberRoute();
      const [x, y] = board.point(event);
      const [previousX, previousY] = board.point(boardDrag);
      const now = performance.now();
      board.panView(x - previousX, y - previousY, now);
      Object.assign(boardDrag, { clientX, clientY, moved: true });
      ignoreBoardClickUntil = now + 350;
    }
  }
  if (aimCell && event.pointerType !== 'touch') aimAt(...board.cellAt(...board.point(event)));
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  board.canvas.addEventListener(type, (event) => {
    if (boardDrag?.pointerId === event.pointerId) {
      if (boardDrag.moved) ignoreBoardClickUntil = performance.now() + 350;
      boardDrag = null;
    }
    if (!boardTouches.has(event.pointerId)) return;
    if (boardTouches.size >= 2) ignoreBoardClickUntil = performance.now() + 350;
    boardTouches.delete(event.pointerId);
    pinchDistance = touchSeparation();
  });
}
board.canvas.addEventListener('wheel', (event) => {
  if (event.ctrlKey || event.metaKey || !event.deltaY || gameUiBlocksMovement()) return;
  event.preventDefault();
  const now = performance.now();
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 80 : 1);
  if (now - lastWheelAt > 140 || Math.sign(delta) !== Math.sign(wheelTotal)) wheelTotal = 0;
  lastWheelAt = now;
  wheelTotal += delta;
  if (Math.abs(wheelTotal) >= 32) {
    board.zoomView(-Math.sign(wheelTotal), now);
    wheelTotal = 0;
  }
}, { passive: false });

function typing(target) { return target instanceof HTMLElement && (target.matches('input, textarea, select') || target.isContentEditable); }

window.addEventListener('keydown', (event) => {
  if (original.active || event.defaultPrevented) return;
  const enter = event.key === 'Enter' || event.code === 'Enter' || event.code === 'NumpadEnter';
  const escape = event.key === 'Escape' || event.code === 'Escape' ||
    (event.key === 'Backspace' || event.code === 'Backspace') && !typing(event.target);
  const chatInput = chatInputs.has(event.target) ? event.target : null;
  // Only the text editor needs IME events. Game controls use the physical key code.
  if (chatInput && (event.isComposing || event.keyCode === 229 || composingChat.has(chatInput))) {
    if (enter) {
      skipChatSubmit.add(chatInput);
      setTimeout(() => skipChatSubmit.delete(chatInput), 0);
    }
    return;
  }
  if (event.altKey || event.ctrlKey || event.metaKey) return;
  if (chatInput) {
    if (enter || escape) {
      event.preventDefault();
      if (!event.repeat) {
        if (enter && chatInput.value.trim()) {
          skipChatSubmit.delete(chatInput);
          chatInput.form.requestSubmit();
        } else board.canvas.focus();
      }
    }
    return;
  }
  if (typing(event.target) || profileCard.open) return;
  if (event.code === 'F1' && !event.repeat) { event.preventDefault(); toggleHelp(); return; }
  if (play.frame && !event.repeat) {
    if (event.code === 'KeyF' || event.code === 'KeyI' || event.code === 'KeyH') {
      event.preventDefault();
      setAim(null);
      setExamining(false);
      if (event.code === 'KeyH') toggleHeroStatus();
      else toggleBackpack();
      return;
    }
    if (event.code === 'KeyP') {
      event.preventDefault();
      togglePause();
      return;
    }
  }
  if (gameUiBlocksMovement()) return;
  if (!event.repeat && (event.code === 'Equal' || event.code === 'NumpadAdd' ||
      event.code === 'Minus' || event.code === 'NumpadSubtract')) {
    event.preventDefault();
    board.zoomView(event.code === 'Equal' || event.code === 'NumpadAdd' ? 1 : -1, performance.now());
    return;
  }
  if (!play.frame && !event.repeat) {
    if (event.code === 'KeyF' && event.target === board.canvas) { event.preventDefault(); talkNearby(); return; }
    if (escape && !fields['inn-dialogue'].hidden) { event.preventDefault(); showDialogue(); focusBoard(); return; }
  }
  if (event.code === 'KeyE' && !event.repeat && !isRuntimeLoading()) {
    event.preventDefault();
    toggleExamine();
    return;
  }
  if (escape && examining) {
    event.preventDefault();
    setExamining(false);
    return;
  }
  const step = keys.get(event.code);
  if (aimCell) {
    if (escape) { event.preventDefault(); setAim(null); return; }
    if (enter && event.target === board.canvas && !event.repeat) {
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
  if (escape && dungeonRoute) {
    event.preventDefault();
    stopAndRememberRoute();
    return;
  }
  if (enter && !aimCell && !loading && fields['inn-dialogue'].hidden &&
      !event.target?.closest?.('button, a, summary, [role="button"]')) {
    const input = fields[play.frame ? 'dungeon-chat-message' : 'chat-message'];
    if (!input.disabled) {
      event.preventDefault();
      if (!event.repeat) input.focus();
    }
    return;
  }
  if (step) {
    event.preventDefault();
    setExamining(false);
    if (!isPaused(play.frame?.state) && !event.repeat && !held.has(event.code)) {
      held.set(event.code, step);
      changeDirection(performance.now());
    }
  } else if (play.frame && !event.repeat) {
    if (event.code === 'KeyQ') { event.preventDefault(); attack(); }
    if (event.code === 'KeyZ' || event.code === 'Numpad5' ||
        event.code === 'Space' && !event.target?.closest?.('button')) {
      event.preventDefault();
      setAim(null);
      setExamining(false);
      stopAndRememberRoute();
    }
    if (event.code === 'KeyR' && interruptedRoute && canAct(play.frame.state, ownActor())) {
      event.preventDefault();
      setAim(null);
      setExamining(false);
      beginDungeonRoute(interruptedRoute);
    }
    if (event.code === 'KeyC' && canAct(play.frame.state, ownActor())) {
      event.preventDefault();
      setAim(null);
      setExamining(false);
      beginDungeonRoute({ type: 'loot' });
    }
    if (event.code === 'Digit1') { event.preventDefault(); toggleAim(); focusBoard(); }
    if (event.code === 'Digit2') { event.preventDefault(); eat(); }
  }
});
window.addEventListener('keyup', (event) => {
  if (held.delete(event.code)) { event.preventDefault(); changeDirection(performance.now(), true); }
});
window.addEventListener('blur', () => {
  clearMovement();
  boardTouches.clear();
  boardDrag = null;
  pinchDistance = 0;
});
document.addEventListener('focusin', (event) => { if (typing(event.target)) clearMovement(); });

for (const button of document.querySelectorAll('[data-direction]')) {
  const step = button.dataset.direction.split(',').map(Number);
  button.addEventListener('pointerdown', (event) => {
    if (chatFocused()) { event.preventDefault(); return; }
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
    if (chatFocused() || event.detail !== 0 || aimCell) return;
    if (play.frame) moveDungeon(...step, true, performance.now());
    else if (play.moveInn(...step) === 'waiting') {
      tap = { dx: step[0], dy: step[1], fresh: true, expires: performance.now() + INN_TAP_MS };
    }
  });
}

function render(now) {
  if (!original.active) {
    waitingAction = null;
    movement(now);
    const marker = dungeonRoute?.x != null ? [dungeonRoute.x, dungeonRoute.y] : destination;
    const gameNow = play.frame?.serverTime();
    board.draw(visualTime(now), selected, marker, aimCell, gameNow, now, waitingForEnergy(now, gameNow));
    if (!play.frame) drawProfile(now);
    updateCombatUi();
    updateNotice(now);
  }
  requestAnimationFrame(render);
}

buildBackpack();
updateScene();
if (!profileOpen) {
  void run('name', async () => {
    if (await play.join(savedProfile.name, savedProfile.appearance)) {
      saveProfile(ownProfile());
      focusBoard();
    } else {
      profileOpen = true;
      profileNotice(connectionNotice || '접속하지 못했습니다. 다시 시도해 주세요.');
    }
  });
}
requestAnimationFrame(render);

function warmExpeditionAssets() {
  for (const { id: job } of AvatarCatalog.jobs) getAvatar({ job });
  void loadBattleRuntime({ simulate: true }).catch(() => {
    // Dungeon entry checks the current version again and handles an unavailable runtime.
  });
  void warmOriginalRuntime().catch(() => {
    // The original game verifies the current build again at launch.
  });
}

if (typeof requestIdleCallback === 'function') requestIdleCallback(warmExpeditionAssets, { timeout: 1500 });
else setTimeout(warmExpeditionAssets, 0);
