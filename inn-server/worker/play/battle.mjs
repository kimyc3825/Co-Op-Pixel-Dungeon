// SPDX-License-Identifier: GPL-3.0-or-later
import { dungeonLayout, PRISON, SEWERS } from './layout.mjs';

const ENERGY_CAP = 10;
const MOVE_COST = 1;
const ATTACK_COST = 1;
const EAT_COST = 3;
const MOVE_COOLDOWN_MS = 180;
const ATTACK_COOLDOWN_MS = 800;
const REGEN_DELAY_MS = 500;
const REGEN_POINT_MS = 800;
const RATION_HEAL = 6;
const RAT_MOVE_COOLDOWN_MS = 380;
const AWARENESS = 4;
const ENERGY_CAP_UNITS = ENERGY_CAP * REGEN_POINT_MS;

const DIRECTIONS = [
  [1, 0], [-1, 0], [0, 1], [0, -1],
  [1, 1], [-1, 1], [1, -1], [-1, -1],
];
const DAMAGE = { player: 4, guide: 3, enemy: 2 };

function cost(type, inCombat) {
  switch (type) {
    case 'move': return inCombat ? MOVE_COST : 0;
    case 'attack': return ATTACK_COST;
    case 'eat': return EAT_COST;
    default: throw new TypeError('Unknown battle action');
  }
}

function actionGate(actor, type) {
  if (type === 'move') return actor.moveReadyAt;
  if (type === 'attack') return actor.attackReadyAt;
  return 0;
}

function energyAt(actor, snapshotNow, displayNow) {
  const elapsed = Math.max(0, displayNow - Math.max(snapshotNow, actor.energyAt));
  return Math.min(ENERGY_CAP, actor.energy + elapsed / REGEN_POINT_MS);
}

function readyAt(actor, type, snapshotNow, inCombat) {
  const gate = actionGate(actor, type);
  const missing = cost(type, inCombat) - actor.energy;
  if (missing <= 0) return gate;
  // Convert the displayed fraction back to the host's whole milliseconds.
  const refill = Math.max(snapshotNow, actor.energyAt) + Math.ceil(missing * REGEN_POINT_MS - 0.000001);
  return Math.max(gate, refill);
}

export const BattleRules = Object.freeze({
  ENERGY_CAP, MOVE_COST, ATTACK_COST, EAT_COST, MOVE_COOLDOWN_MS, ATTACK_COOLDOWN_MS,
  REGEN_DELAY_MS, REGEN_POINT_MS, RATION_HEAL, cost, energyAt, readyAt,
});

function move(dx, dy) {
  return { type: 'move', dx, dy, target: null };
}

function attack(target) {
  return { type: 'attack', dx: 0, dy: 0, target };
}

function normalize(action) {
  if (action?.type === 'move') {
    const { dx, dy } = action;
    if (Number.isInteger(dx) && Number.isInteger(dy) && Math.max(Math.abs(dx), Math.abs(dy)) === 1) {
      return move(dx, dy);
    }
  } else if (action?.type === 'attack' && typeof action.target === 'string') {
    return attack(action.target);
  } else if (action?.type === 'eat') {
    return { type: 'eat', dx: 0, dy: 0, target: null };
  }
  return null;
}

function createActor(id, name, kind, { x, y }, hp, now) {
  return {
    id, name, kind, x, y, hp, maxHp: hp, actionSeq: 0, lastAction: null, targetId: null, actionAt: 0,
    energyUnits: ENERGY_CAP_UNITS, energyAt: now,
    moveReadyAt: kind === 'enemy' ? now + RAT_MOVE_COOLDOWN_MS : now,
    attackReadyAt: kind === 'enemy' ? now + ATTACK_COOLDOWN_MS : now,
    rations: kind === 'player' ? 1 : 0, queued: null, commandAck: 0,
  };
}

function energyUnitsAt(actor, now) {
  return Math.min(ENERGY_CAP_UNITS, actor.energyUnits + Math.max(0, now - actor.energyAt));
}

function actorSnapshot(actor, now) {
  const { id, name, kind, x, y, hp, maxHp, actionSeq, lastAction, targetId, actionAt,
    energyAt, moveReadyAt, attackReadyAt, rations } = actor;
  return {
    id, name, kind, x, y, hp, maxHp, actionSeq, lastAction, targetId, actionAt,
    energy: energyUnitsAt(actor, now) / REGEN_POINT_MS, energyAt, moveReadyAt, attackReadyAt, rations,
  };
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
  return (a.kind === 'enemy') !== (b.kind === 'enemy');
}

function awareOf(observer, target) {
  return target.hp > 0 && opponents(observer, target) && distance(observer.x, observer.y, target) <= AWARENESS;
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

/** Browser host simulation for protocol 5, kept in step with the Windows HostBattle. */
export class HostBattle {
  #id;
  #seed;
  #layout;
  #actors;
  #drops = [];
  #battleAt;
  #observedAt;
  #status = 'active';
  #inCombat = false;
  #revision = 1;

  constructor(id, host, guest, now, seed, theme = SEWERS) {
    this.#id = id;
    this.#seed = seed;
    this.#layout = dungeonLayout(theme, seed);
    const { host: entrance, partner, enemy1, enemy2 } = this.#layout;
    const ratName = this.#layout.theme === PRISON ? '감옥 쥐' : '하수도 쥐';
    this.#battleAt = this.#observedAt = Math.trunc(now);
    this.#actors = [
      createActor(host.id, host.name, 'player', entrance, 24, this.#battleAt),
      guest ? createActor(guest.id, guest.name, 'player', partner, 24, this.#battleAt)
        : createActor('guide', '길잡이', 'guide', partner, 24, this.#battleAt),
      createActor('rat-1', ratName, 'enemy', enemy1, 12, this.#battleAt),
      createActor('rat-2', ratName, 'enemy', enemy2, 12, this.#battleAt),
    ];
  }

  get revision() {
    return this.#revision;
  }

  advance(now) {
    this.#observedAt = Math.max(this.#observedAt, Math.trunc(now));
    while (this.#status === 'active' || this.#status === 'won') {
      const turn = this.#nextTurn(this.#observedAt);
      if (!turn) break;
      turn.actor.queued = null;
      this.#battleAt = turn.at;
      this.#resolve(turn.actor, turn.action, turn.at);
      this.#revision++;
    }
  }

  input(actorId, sequence, action, now) {
    const actor = this.#find(actorId);
    if (actor?.kind !== 'player' || !Number.isSafeInteger(sequence) || sequence <= actor.commandAck) return;
    actor.commandAck = sequence;
    this.#revision++;
    this.advance(now);

    // A rejected action still acknowledges the command to end retransmissions.
    const normalized = normalize(action);
    if (!normalized || actor.hp === 0 || !this.#accepts(normalized)) return;
    const queued = this.#readyAt(actor, normalized) > this.#observedAt;
    if (!this.#actionAllowed(actor, normalized, queued)) return;
    if (queued) {
      if (normalized.type === 'move' && actor.queued?.type === 'attack') return;
      actor.queued = normalized;
      this.#battleAt = this.#observedAt;
      return;
    }
    this.#resolve(actor, normalized, this.#observedAt);
    this.advance(this.#observedAt);
  }

  snapshot(selfId, now) {
    const self = this.#find(selfId);
    if (self?.kind !== 'player') throw new TypeError('Unknown player');
    const snapshotTime = Math.max(this.#observedAt, Math.trunc(now));
    return {
      id: this.#id, selfId, now: snapshotTime, map: this.#layout.map,
      actors: this.#actors.map((actor) => actorSnapshot(actor, snapshotTime)),
      queued: self.queued ? { ...self.queued } : null, status: this.#status, waitingForPartner: false,
      revision: this.#revision, drops: this.#drops.map((drop) => ({ ...drop })),
      commandAck: self.commandAck, inCombat: this.#inCombat, theme: this.#layout.theme,
    };
  }

  close(now) {
    if (this.#status === 'closed') return;
    this.#observedAt = Math.max(this.#observedAt, Math.trunc(now));
    this.#status = 'closed';
    this.#inCombat = false;
    this.#clearQueue();
    this.#revision++;
  }

  #find(actorId) {
    return this.#actors.find((actor) => actor.id === actorId);
  }

  #canMove(x, y, dx, dy) {
    return stepAllowed(this.#layout.map, x, y, dx, dy) &&
      !this.#actors.some((actor) => actor.hp > 0 && actor.x === x + dx && actor.y === y + dy);
  }

  #accepts(action) {
    return this.#status === 'active' || (this.#status === 'won' && action.type !== 'attack');
  }

  #actionAllowed(actor, action, queued) {
    if (action.type === 'move') {
      const { dx, dy } = action;
      return queued ? stepAllowed(this.#layout.map, actor.x, actor.y, dx, dy) : this.#canMove(actor.x, actor.y, dx, dy);
    }
    if (action.type === 'eat') return actor.rations > 0 && actor.hp < actor.maxHp;
    const target = this.#find(action.target);
    return target && target.hp > 0 && opponents(actor, target) && inReach(this.#layout.map, actor.x, actor.y, target);
  }

  #costUnits(actor, action) {
    return actor.kind === 'enemy' ? 0 : cost(action.type, this.#inCombat) * REGEN_POINT_MS;
  }

  #readyAt(actor, action) {
    const gate = actionGate(actor, action.type);
    const missing = this.#costUnits(actor, action) - actor.energyUnits;
    return missing <= 0 ? gate : Math.max(gate, actor.energyAt + missing);
  }

  #resolve(actor, action, at) {
    if (!this.#actionAllowed(actor, action, false)) return;
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
      if (actor.kind === 'player') this.#pickUp(actor);
    } else if (action.type === 'attack') {
      const target = this.#find(action.target);
      target.hp = Math.max(0, target.hp - DAMAGE[actor.kind]);
      if (target.hp === 0) {
        target.queued = null;
        if (target.kind === 'enemy') this.#drop(target);
      }
      actor.targetId = target.id;
      actor.attackReadyAt = at + ATTACK_COOLDOWN_MS;
    } else {
      actor.rations--;
      actor.hp = Math.min(actor.maxHp, actor.hp + RATION_HEAL);
      actor.targetId = null;
    }
    this.#battleAt = at;
    actor.actionAt = at;
    actor.actionSeq++;
    actor.lastAction = action.type;
    this.#updateOutcome();
    this.#inCombat = this.#status === 'active' && this.#engaged();
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
      }
    }
  }

  #engaged() {
    return this.#actors.some((enemy) => enemy.kind === 'enemy' && enemy.hp > 0 &&
      this.#actors.some((partyMember) => awareOf(enemy, partyMember)));
  }

  #updateOutcome() {
    if (this.#status !== 'active') return;
    if (!this.#actors.some((actor) => actor.kind === 'player' && actor.hp > 0)) {
      this.#status = 'lost';
    } else if (!this.#actors.some((actor) => actor.kind === 'enemy' && actor.hp > 0)) {
      this.#status = 'won';
    }
    if (this.#status !== 'active') this.#clearQueue();
  }

  #clearQueue() {
    for (const actor of this.#actors) actor.queued = null;
  }

  #nextTurn(now) {
    let next = null;
    for (const actor of this.#actors) {
      if (actor.hp === 0 || (actor.kind !== 'player' && this.#status !== 'active')) continue;
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
