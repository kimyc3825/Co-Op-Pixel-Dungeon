// SPDX-License-Identifier: GPL-3.0-or-later
import { dungeonLayout } from './layout.mjs';
import { actionGate, BattleRules, isPotion, offensive, POTIONS, PRISON, SEWERS, VIOLET_POTION } from './rules.mjs';
import { normalizeAppearance } from './avatar-catalog.mjs';

export { BattleRules };

const { ENERGY_CAP, MOVE_COOLDOWN_MS, ATTACK_COOLDOWN_MS, SHOOT_RANGE, SHOOT_DAMAGE, PROJECTILE_STEP_MS,
  REGEN_DELAY_MS, REGEN_POINT_MS, RATION_HEAL, POTION_HEAL, POTION_ENERGY, PAUSE_LIMIT_MS, PAUSE_COOLDOWN_MS,
  CHAT_LIMIT, cost } = BattleRules;
const RAT_MOVE_COOLDOWN_MS = 380;
const AWARENESS = 4;
const SIGHT_RANGE = 6;
const CONTACT_HOLD_MS = 450;
const PURSUIT_HOLD_MS = 2400;
const CHAT_HISTORY = 16;
const ATTACK_HISTORY = 16;
const ENERGY_CAP_UNITS = ENERGY_CAP * REGEN_POINT_MS;
const DUMMY_ID = 'training-dummy';
const DUMMY_COMBAT_RANGE = 2;
const PRACTICE_RATIONS = 3;

const DIRECTIONS = [
  [1, 0], [-1, 0], [0, 1], [0, -1],
  [1, 1], [-1, 1], [1, -1], [-1, -1],
];
const DAMAGE = { player: 4, guide: 3, enemy: 2 };

function move(dx, dy) {
  return { type: 'move', dx, dy, target: null };
}

function attack(target) {
  return { type: 'attack', dx: 0, dy: 0, target };
}

function shotCell(target) {
  const parts = typeof target === 'string' && /^([0-9]{1,3}),([0-9]{1,3})$/.exec(target);
  return parts ? { x: Number(parts[1]), y: Number(parts[2]) } : null;
}

function projectilePath(x, y, aim) {
  const path = [];
  const dx = Math.abs(aim.x - x);
  const dy = -Math.abs(aim.y - y);
  const sx = x < aim.x ? 1 : -1;
  const sy = y < aim.y ? 1 : -1;
  let error = dx + dy;
  while (x !== aim.x || y !== aim.y) {
    const doubled = error * 2;
    if (doubled >= dy) {
      error += dy;
      x += sx;
    }
    if (doubled <= dx) {
      error += dx;
      y += sy;
    }
    path.push({ x, y });
  }
  return path;
}

function normalize(action) {
  if (action?.type === 'move') {
    const { dx, dy } = action;
    if (Number.isInteger(dx) && Number.isInteger(dy) && Math.max(Math.abs(dx), Math.abs(dy)) === 1) {
      return move(dx, dy);
    }
  } else if (action?.type === 'attack' && typeof action.target === 'string') {
    return attack(action.target);
  } else if (action?.type === 'shoot' && shotCell(action.target)) {
    return { type: 'shoot', dx: 0, dy: 0, target: action.target };
  } else if (action?.type === 'eat') {
    return { type: 'eat', dx: 0, dy: 0, target: null };
  } else if (action?.type === 'use-item' && isPotion(action.target)) {
    return { type: 'use-item', dx: 0, dy: 0, target: action.target };
  } else if (action?.type === 'pause' && (action.target === 'begin' || action.target === 'resume')) {
    return { type: 'pause', dx: 0, dy: 0, target: action.target };
  } else if (action?.type === 'chat' && typeof action.target === 'string') {
    const text = action.target.replace(/[\p{C}\p{Z}]+/gu, ' ').trim();
    if (!text) return null;
    return { type: 'chat', dx: 0, dy: 0, target: [...text].slice(0, CHAT_LIMIT).join('').trimEnd() };
  }
  return null;
}

function createActor(id, name, kind, { x, y }, hp, now, appearance) {
  return {
    id, name, kind, x, y, hp, maxHp: hp, actionSeq: 0, lastAction: null, targetId: null, actionAt: 0,
    energyUnits: ENERGY_CAP_UNITS, energyAt: now,
    moveReadyAt: kind === 'enemy' ? now + RAT_MOVE_COOLDOWN_MS : now,
    attackReadyAt: kind === 'enemy' ? now + ATTACK_COOLDOWN_MS : now,
    rations: kind === 'player' ? 1 : 0, gold: 0, potions: new Set(), queued: null, commandAck: 0,
    appearance: kind === 'player' ? normalizeAppearance(appearance) : undefined,
  };
}

function createPlayer({ id, name, appearance, preparation }, start, now, practice = false) {
  const { hp, food } = (!practice && preparation) || {};
  const startingHp = 24 + (hp === 2 || hp === -2 ? hp : 0);
  const player = createActor(id, name, 'player', start, startingHp, now, appearance);
  if (food === 1 || food === -1) player.rations += food;
  return player;
}

function energyUnitsAt(actor, now) {
  return Math.min(ENERGY_CAP_UNITS, actor.energyUnits + Math.max(0, now - actor.energyAt));
}

function actorSnapshot(actor, now) {
  const { id, name, kind, x, y, hp, maxHp, actionSeq, lastAction, targetId, actionAt,
    energyAt, moveReadyAt, attackReadyAt, rations, appearance } = actor;
  return {
    id, name, kind, x, y, hp, maxHp, actionSeq, lastAction, targetId, actionAt,
    energy: energyUnitsAt(actor, now) / REGEN_POINT_MS, energyAt, moveReadyAt, attackReadyAt, rations,
    inventory: inventory(actor),
    ...(appearance && { appearance }),
  };
}

function inventory(actor) {
  const items = [];
  if (actor.rations > 0) items.push({ item: 'ration', amount: actor.rations });
  if (actor.gold > 0) items.push({ item: 'gold', amount: actor.gold });
  for (const item of actor.potions) items.push({ item, amount: 1 });
  return items;
}

function walkable(map, x, y) {
  return map[y]?.[x] === '.';
}

function distance(x, y, target) {
  return Math.max(Math.abs(x - target.x), Math.abs(y - target.y));
}

function stepAllowed(map, x, y, dx, dy) {
  return walkable(map, x + dx, y + dy) &&
    (dx === 0 || dy === 0 || walkable(map, x + dx, y) || walkable(map, x, y + dy));
}

function inReach(map, x, y, target) {
  return distance(x, y, target) === 1 && walkable(map, target.x, target.y) &&
    (x === target.x || y === target.y || (walkable(map, target.x, y) && walkable(map, x, target.y)));
}

function opponents(a, b) {
  if (a.kind === 'dummy' || b.kind === 'dummy') return a.kind === 'player' || b.kind === 'player';
  return (a.kind === 'enemy') !== (b.kind === 'enemy');
}

function awareOf(observer, target) {
  return target.hp > 0 && opponents(observer, target) && distance(observer.x, observer.y, target) <= AWARENESS;
}

function visibleThrough(map, from, target) {
  let { x, y } = from;
  for (const cell of projectilePath(x, y, target)) {
    if (!stepAllowed(map, x, y, cell.x - x, cell.y - y)) return false;
    ({ x, y } = cell);
  }
  return true;
}

function lootRoll(seed, enemyId) {
  // Mix the seed and enemy ID independently so loot does not depend on kill order.
  let hash = 0x811c9dc5;
  for (const character of `${seed}:${enemyId}`) {
    hash = Math.imul(hash ^ character.codePointAt(0), 0x01000193);
  }
  hash = Math.imul(hash ^ (hash >>> 16), 0x85ebca6b);
  return (hash ^ (hash >>> 13)) >>> 0;
}

/** Browser host simulation kept in step with the Windows HostBattle. */
export class HostBattle {
  #id;
  #seed;
  #layout;
  #actors;
  #friendlyFire;
  #trainingTools = [];
  #drops = [];
  #messages = [];
  #attackEvents = [];
  #projectiles = [];
  #identifiedPotions = new Set();
  #battleAt;
  #gameObservedAt;
  #wallObservedAt;
  #pausedMillis = 0;
  #pauseStartedAt = 0;
  #pausedUntil = 0;
  #pauseReadyAt = 0;
  #nextMessageId = 0;
  #nextAttackEventId = 0;
  #nextProjectileId = 0;
  #status = 'active';
  #encounter = 'exploration';
  #contactUntil = 0;
  #pursuitUntil = 0;
  #revision = 1;

  constructor(id, host, guest, now, seed, theme = SEWERS, friendlyFire = false, practice = !guest) {
    this.#id = id;
    this.#seed = seed;
    this.#layout = dungeonLayout(theme, seed);
    this.#friendlyFire = friendlyFire === true;
    const { host: entrance, partner, enemy1, enemy2 } = this.#layout;
    const ratName = this.#layout.theme === PRISON ? '감옥 쥐' : '하수도 쥐';
    this.#battleAt = this.#gameObservedAt = this.#wallObservedAt = Math.trunc(now);
    const hostActor = createPlayer(host, entrance, this.#battleAt, practice && !guest);
    this.#actors = [hostActor];
    if (guest) {
      const guestActor = createPlayer(guest, partner, this.#battleAt);
      hostActor.potions = new Set(POTIONS);
      guestActor.potions = new Set(POTIONS);
      this.#actors.push(guestActor);
    }
    else if (practice) this.#actors.push(createActor('guide', '길잡이', 'guide', partner, 24, this.#battleAt));
    this.#actors.push(
      createActor('rat-1', ratName, 'enemy', enemy1, 12, this.#battleAt),
      createActor('rat-2', ratName, 'enemy', enemy2, 12, this.#battleAt),
    );
    if (practice && !guest) {
      this.#actors.push(createActor(DUMMY_ID, '연습용 허수아비', 'dummy', { x: partner.x + 1, y: partner.y - 1 }, 16, this.#battleAt));
      this.#trainingTools = [
        { id: 'ration-rack', x: partner.x - 1, y: partner.y + 1 },
        { id: 'recovery-kit', x: partner.x + 1, y: partner.y + 1 },
      ];
    }
    this.#updateEncounter(this.#battleAt);
  }

  get revision() {
    return this.#revision;
  }

  advance(now) {
    if (this.#status === 'closed') return;
    this.#wallObservedAt = Math.max(this.#wallObservedAt, Math.trunc(now));
    if (this.#pausedUntil > 0) {
      if (this.#wallObservedAt < this.#pausedUntil) return;
      this.#resume(this.#pausedUntil);
    }
    this.#advanceGame(this.#gameTime(this.#wallObservedAt));
  }

  #gameTime(wallNow) {
    return (this.#pausedUntil > 0 ? this.#pauseStartedAt : wallNow) - this.#pausedMillis;
  }

  #advanceGame(now) {
    this.#gameObservedAt = now;
    while (this.#status === 'active' || this.#status === 'won') {
      const turn = this.#nextTurn(this.#gameObservedAt);
      const projectile = this.#nextProjectile(this.#gameObservedAt);
      if (!turn && !projectile) break;
      this.#updateEncounter(Math.min(turn?.at ?? Infinity, projectile?.stepAt ?? Infinity));
      if (projectile && (!turn || projectile.stepAt < turn.at)) {
        this.#battleAt = projectile.stepAt;
        this.#stepProjectile(projectile);
      } else {
        turn.actor.queued = null;
        this.#battleAt = turn.at;
        this.#resolve(turn.actor, turn.action, turn.at);
      }
      this.#revision++;
    }
    this.#updateEncounter(now);
  }

  input(actorId, sequence, action, now) {
    const actor = this.#find(actorId);
    if (actor?.kind !== 'player' || !Number.isSafeInteger(sequence) || sequence <= actor.commandAck) return;
    actor.commandAck = sequence;
    this.#revision++;
    this.advance(now);

    // A rejected action still acknowledges the command to end retransmissions.
    const normalized = normalize(action);
    if (!normalized || this.#status === 'closed') return;
    if (normalized.type === 'chat') {
      this.#chat(actor, normalized.target);
      return;
    }
    if (normalized.type === 'pause') {
      this.#pause(normalized.target);
      return;
    }
    if (this.#pausedUntil > 0 || actor.hp === 0 || !this.#accepts(normalized)) return;
    const queued = this.#readyAt(actor, normalized) > this.#gameObservedAt;
    if (queued) {
      if (!this.#actionAllowed(actor, normalized, this.#gameObservedAt, true)) return;
      if (normalized.type === 'move' && offensive(actor.queued?.type)) return;
      actor.queued = normalized;
      this.#battleAt = this.#gameObservedAt;
      return;
    }
    this.#resolve(actor, normalized, this.#gameObservedAt);
    this.#advanceGame(this.#gameObservedAt);
  }

  snapshot(selfId, now) {
    const self = this.#find(selfId);
    if (self?.kind !== 'player') throw new TypeError('Unknown player');
    const wallNow = Math.max(this.#wallObservedAt, Math.trunc(now));
    const snapshotTime = this.#status === 'closed' ? this.#gameObservedAt : this.#gameTime(wallNow);
    return {
      id: this.#id, selfId, now: snapshotTime, map: this.#layout.map,
      actors: this.#actors.map((actor) => actorSnapshot(actor, snapshotTime)),
      queued: self.queued ? { ...self.queued } : null, status: this.#status, waitingForPartner: false,
      revision: this.#revision, drops: this.#drops.map((drop) => ({ ...drop })),
      identifiedItems: Array.from(this.#identifiedPotions, item => ({ item, effect: this.#potionEffect(item) })),
      commandAck: self.commandAck, inCombat: this.#combatCosts(), encounter: this.#encounter, theme: this.#layout.theme,
      ...(this.#trainingTools.length > 0 && { trainingTools: this.#trainingTools.map((tool) => ({ ...tool })) }),
      wallNow, pausedUntil: this.#pausedUntil, pauseReadyAt: this.#pauseReadyAt,
      messages: this.#messages.map((message) => ({ ...message })),
      friendlyFire: this.#friendlyFire,
      attackEvents: this.#attackEvents.map((event) => ({ ...event })),
      projectiles: this.#projectiles.map(({ id, owner, x, y, path, index, stepAt }) => ({
        id, ownerId: owner.id, x, y, nextX: path[index].x, nextY: path[index].y, stepAt,
      })),
    };
  }

  close(now) {
    if (this.#status === 'closed') return;
    this.#wallObservedAt = Math.max(this.#wallObservedAt, Math.trunc(now));
    this.#gameObservedAt = this.#gameTime(this.#wallObservedAt);
    this.#pausedUntil = 0;
    this.#status = 'closed';
    this.#updateEncounter(this.#gameObservedAt);
    this.#clearQueue();
    this.#projectiles.length = 0;
    this.#revision++;
  }

  #pause(intent) {
    if (intent === 'resume' && this.#pausedUntil > 0) {
      this.#resume(this.#wallObservedAt);
    } else if (intent === 'begin' && this.#pausedUntil === 0 && this.#wallObservedAt >= this.#pauseReadyAt &&
        (this.#status === 'active' || this.#status === 'won')) {
      this.#pauseStartedAt = this.#wallObservedAt;
      this.#pausedUntil = this.#wallObservedAt + PAUSE_LIMIT_MS;
    }
  }

  #resume(wallAt) {
    // With a late tick, only the portion after the exact deadline advances the simulation.
    this.#pausedMillis += wallAt - this.#pauseStartedAt;
    this.#pausedUntil = 0;
    this.#pauseReadyAt = wallAt + PAUSE_COOLDOWN_MS;
    this.#revision++;
  }

  #chat(actor, text) {
    if (this.#messages.length === CHAT_HISTORY) this.#messages.shift();
    this.#messages.push({
      id: ++this.#nextMessageId, actorId: actor.id, name: actor.name, text, time: this.#wallObservedAt
    });
  }

  #find(actorId) {
    return this.#actors.find((actor) => actor.id === actorId);
  }

  #canMove(x, y, dx, dy) {
    return stepAllowed(this.#layout.map, x, y, dx, dy) &&
      !this.#actors.some((actor) => actor.hp > 0 && actor.x === x + dx && actor.y === y + dy);
  }

  #accepts(action) {
    return this.#status === 'active' || (this.#status === 'won' && !offensive(action.type));
  }

  #actionAllowed(actor, action, at, queued) {
    if (action.type === 'move') {
      const { dx, dy } = action;
      return queued ? stepAllowed(this.#layout.map, actor.x, actor.y, dx, dy) : this.#canMove(actor.x, actor.y, dx, dy);
    }
    if (action.type === 'eat') return actor.rations > 0 && actor.hp < actor.maxHp;
    if (action.type === 'use-item') return actor.potions.has(action.target);
    if (action.type === 'shoot') {
      const aim = shotCell(action.target);
      const inBounds = this.#layout.map[aim.y]?.[aim.x] !== undefined;
      const range = distance(actor.x, actor.y, aim);
      if (inBounds && range >= 1 && range <= SHOOT_RANGE) return true;
      this.#recordAttack(actor, 'shoot', 'out_of_reach', at, inBounds ? aim : actor);
      return false;
    }
    const target = this.#find(action.target);
    const result = this.#meleeRejection(actor, target);
    if (result && actor.kind === 'player') {
      this.#recordAttack(actor, 'attack', result, at, target ?? actor, target?.id);
    }
    return !result;
  }

  #meleeRejection(actor, target) {
    if (!target || target.hp === 0) return 'target_gone';
    if (actor === target || (!opponents(actor, target) && (!this.#friendlyFire || actor.kind !== 'player'))) {
      return 'ally_blocked';
    }
    if (distance(actor.x, actor.y, target) !== 1) return 'out_of_reach';
    return inReach(this.#layout.map, actor.x, actor.y, target) ? null : 'blocked';
  }

  #costUnits(actor, action) {
    return actor.kind === 'enemy' ? 0 : cost(action.type, this.#combatCosts()) * REGEN_POINT_MS;
  }

  #readyAt(actor, action) {
    const gate = actionGate(actor, action.type);
    const missing = this.#costUnits(actor, action) - actor.energyUnits;
    return missing <= 0 ? gate : Math.max(gate, actor.energyAt + missing);
  }

  #resolve(actor, action, at) {
    if (!this.#actionAllowed(actor, action, at, false)) return;
    const units = this.#costUnits(actor, action);
    if (units > 0) {
      actor.energyUnits = energyUnitsAt(actor, at) - units;
      actor.energyAt = at + REGEN_DELAY_MS;
    }

    if (action.type === 'move') {
      actor.x += action.dx;
      actor.y += action.dy;
      actor.targetId = null;
      actor.moveReadyAt = at + (actor.kind === 'enemy' ? RAT_MOVE_COOLDOWN_MS : MOVE_COOLDOWN_MS);
      if (actor.kind === 'player') {
        this.#pickUp(actor);
        this.#useTrainingTool(actor, at);
      }
    } else if (action.type === 'attack') {
      const target = this.#find(action.target);
      this.#hit(actor, 'attack', target, DAMAGE[actor.kind], at);
      actor.targetId = target.id;
      actor.attackReadyAt = at + ATTACK_COOLDOWN_MS;
    } else if (action.type === 'shoot') {
      this.#projectiles.push({
        id: ++this.#nextProjectileId, owner: actor, x: actor.x, y: actor.y,
        path: projectilePath(actor.x, actor.y, shotCell(action.target)), index: 0, stepAt: at + PROJECTILE_STEP_MS,
      });
      actor.targetId = null;
      actor.attackReadyAt = at + ATTACK_COOLDOWN_MS;
    } else if (action.type === 'eat') {
      actor.rations--;
      actor.hp = Math.min(actor.maxHp, actor.hp + RATION_HEAL);
      actor.targetId = null;
    } else {
      this.#usePotion(actor, action.target);
    }
    this.#battleAt = at;
    actor.actionAt = at;
    actor.actionSeq++;
    actor.lastAction = action.type;
    this.#updateOutcome();
    this.#updateEncounter(at);
  }

  #potionEffect(item) {
    const violetHeals = this.#seed % 2 === 0;
    const heals = item === VIOLET_POTION ? violetHeals : !violetHeals;
    return heals ? 'healing' : 'energy';
  }

  #usePotion(actor, item) {
    actor.potions.delete(item);
    this.#identifiedPotions.add(item);
    if (this.#potionEffect(item) === 'healing') {
      actor.hp = Math.min(actor.maxHp, actor.hp + POTION_HEAL);
    } else {
      actor.energyUnits = Math.min(ENERGY_CAP_UNITS, actor.energyUnits + POTION_ENERGY * REGEN_POINT_MS);
    }
    actor.targetId = null;
  }

  #recordAttack(actor, action, result, at, { x, y }, targetId = null, amount = 0) {
    if (this.#attackEvents.length === ATTACK_HISTORY) this.#attackEvents.shift();
    this.#attackEvents.push({ id: ++this.#nextAttackEventId, action, result, actorId: actor.id, targetId, x, y, amount, at });
  }

  #hit(actor, action, target, damage, at) {
    const amount = Math.min(target.hp, damage);
    target.hp -= amount;
    if (target.hp === 0) {
      target.queued = null;
      if (target.kind === 'enemy') this.#drop(target);
      if (target.kind === 'dummy') target.hp = target.maxHp;
    }
    this.#recordAttack(actor, action, 'hit', at, target, target.id, amount);
  }

  #nextProjectile(now) {
    let next = null;
    for (const projectile of this.#projectiles) {
      if (projectile.stepAt <= now && (!next || projectile.stepAt < next.stepAt ||
          (projectile.stepAt === next.stepAt && projectile.id < next.id))) next = projectile;
    }
    return next;
  }

  #stepProjectile(projectile) {
    const { owner, stepAt, path, index } = projectile;
    const cell = path[index];
    const clear = stepAllowed(this.#layout.map, projectile.x, projectile.y, cell.x - projectile.x, cell.y - projectile.y);
    const target = clear && this.#actors.find((actor) => actor !== owner && actor.hp > 0 && actor.x === cell.x && actor.y === cell.y);
    if (clear && !target && index + 1 < path.length) {
      projectile.x = cell.x;
      projectile.y = cell.y;
      projectile.index++;
      projectile.stepAt += PROJECTILE_STEP_MS;
      return;
    }
    this.#projectiles.splice(this.#projectiles.indexOf(projectile), 1);
    if (!clear) {
      this.#recordAttack(owner, 'shoot', 'blocked', stepAt, cell);
    } else if (!target) {
      this.#recordAttack(owner, 'shoot', 'miss', stepAt, cell);
    } else if (!this.#friendlyFire && !opponents(owner, target)) {
      this.#recordAttack(owner, 'shoot', 'ally_blocked', stepAt, cell, target.id);
    } else {
      this.#hit(owner, 'shoot', target, SHOOT_DAMAGE, stepAt);
      this.#updateOutcome();
      this.#updateEncounter(stepAt);
    }
  }

  #drop(enemy) {
    const roll = lootRoll(this.#seed, enemy.id);
    const gold = (roll & 1) === 1;
    this.#drops.push({
      id: `${this.#id}:${enemy.id}`, item: gold ? 'gold' : 'ration', x: enemy.x, y: enemy.y,
      amount: gold ? 3 + (roll >>> 1) % 5 : 1, pickedBy: null,
    });
  }

  #pickUp(actor) {
    for (const drop of this.#drops) {
      if (drop.pickedBy === null && drop.x === actor.x && drop.y === actor.y) {
        drop.pickedBy = actor.id;
        if (drop.item === 'ration') actor.rations += drop.amount;
        else if (drop.item === 'gold') actor.gold += drop.amount;
      }
    }
  }

  #useTrainingTool(actor, at) {
    const tool = this.#trainingTools.find(({ x, y }) => x === actor.x && y === actor.y);
    if (tool?.id === 'ration-rack') {
      actor.rations = Math.max(actor.rations, PRACTICE_RATIONS);
    } else if (tool?.id === 'recovery-kit') {
      actor.hp = actor.maxHp;
      actor.energyUnits = ENERGY_CAP_UNITS;
      actor.energyAt = at;
    }
  }

  #combatCosts() {
    return this.#encounter === 'combat' || this.#encounter === 'pursuit';
  }

  #updateEncounter(at) {
    const dummy = this.#find(DUMMY_ID);
    let activeEnemy = false;
    let visible = false;
    let aware = false;
    let contact = false;
    if (this.#status === 'active') {
      for (const enemy of this.#actors) {
        if (enemy.kind !== 'enemy' || enemy.hp <= 0) continue;
        activeEnemy = true;
        for (const member of this.#actors) {
          if (member.hp <= 0 || member.kind !== 'player' && member.kind !== 'guide') continue;
          const range = distance(enemy.x, enemy.y, member);
          if (range > SIGHT_RANGE) continue;
          const seen = visibleThrough(this.#layout.map, member, enemy);
          visible ||= seen;
          if (range <= AWARENESS) {
            aware = true;
            contact ||= seen;
          }
        }
      }
    }
    if (activeEnemy) {
      if (contact) this.#contactUntil = at + CONTACT_HOLD_MS;
      if (aware) this.#pursuitUntil = at + PURSUIT_HOLD_MS;
    } else {
      this.#contactUntil = this.#pursuitUntil = 0;
    }
    const training = this.#status === 'active' && dummy && this.#actors.some(actor =>
      actor.kind === 'player' && actor.hp > 0 && distance(actor.x, actor.y, dummy) <= DUMMY_COMBAT_RANGE);
    const next = training || contact || at < this.#contactUntil ? 'combat' :
      aware || at < this.#pursuitUntil ? 'pursuit' : visible ? 'alert' : 'exploration';
    if (next !== this.#encounter) {
      this.#encounter = next;
      this.#revision++;
    }
  }

  #updateOutcome() {
    if (this.#status !== 'active') return;
    if (!this.#actors.some((actor) => actor.kind === 'player' && actor.hp > 0)) {
      this.#status = 'lost';
    } else if (this.#trainingTools.length === 0 && !this.#actors.some((actor) => actor.kind === 'enemy' && actor.hp > 0)) {
      this.#status = 'won';
    }
    if (this.#status !== 'active') {
      this.#clearQueue();
      this.#projectiles.length = 0;
    }
  }

  #clearQueue() {
    for (const actor of this.#actors) actor.queued = null;
  }

  #nextTurn(now) {
    let next = null;
    for (const actor of this.#actors) {
      if (actor.kind === 'dummy' || actor.hp === 0 || (actor.kind !== 'player' && this.#status !== 'active')) continue;
      const action = actor.kind === 'player' ? actor.queued : this.#botAction(actor);
      if (!action) continue;
      const at = Math.max(this.#battleAt, this.#readyAt(actor, action));
      // If deadlines coincide, the earlier actor in the stable party order acts first.
      if (at <= now && (!next || at < next.at)) next = { actor, action, at };
    }
    return next;
  }

  #botAction(actor) {
    const targets = this.#actors.filter((target) => awareOf(actor, target));
    const adjacent = targets.find((target) => inReach(this.#layout.map, actor.x, actor.y, target));
    if (adjacent) return attack(adjacent.id);
    if (targets.length) {
      const action = this.#approach(actor, targets);
      if (action) return action;
    }
    const host = this.#actors[0];
    if (actor.kind === 'guide' && host.hp > 0 && distance(actor.x, actor.y, host) > 1) {
      return this.#approach(actor, [host]);
    }
    return null;
  }

  #approach(actor, targets) {
    const { map } = this.#layout;
    const width = map[0].length;
    const cells = [{ x: actor.x, y: actor.y, first: null }];
    const visited = new Uint8Array(width * map.length);
    visited[actor.y * width + actor.x] = 1;
    for (let index = 0; index < cells.length; index++) {
      const cell = cells[index];
      if (cell.first && targets.some((target) => inReach(map, cell.x, cell.y, target))) return cell.first;
      for (const [dx, dy] of DIRECTIONS) {
        if (!this.#canMove(cell.x, cell.y, dx, dy)) continue;
        const x = cell.x + dx;
        const y = cell.y + dy;
        const key = y * width + x;
        if (visited[key]) continue;
        visited[key] = 1;
        cells.push({ x, y, first: cell.first ?? move(dx, dy) });
      }
    }
    return null;
  }
}
