// SPDX-License-Identifier: GPL-3.0-or-later
export const SEWERS = 'sewers';
export const PRISON = 'prison';
export const VIOLET_POTION = 'violet-potion';
export const AMBER_POTION = 'amber-potion';
export const POTIONS = Object.freeze([VIOLET_POTION, AMBER_POTION]);

const ENERGY_CAP = 10;
const MOVE_COST = 1;
const ATTACK_COST = 1;
const SHOOT_COST = 2;
const EAT_COST = 3;
const ITEM_USE_COST = 3;
const MOVE_COOLDOWN_MS = 180;
const ATTACK_COOLDOWN_MS = 800;
const SHOOT_RANGE = 5;
const SHOOT_DAMAGE = 3;
const PROJECTILE_STEP_MS = 100;
const REGEN_DELAY_MS = 500;
const REGEN_POINT_MS = 800;
const RATION_HEAL = 6;
const POTION_HEAL = 6;
const POTION_ENERGY = 5;
const PAUSE_LIMIT_MS = 60_000;
const PAUSE_COOLDOWN_MS = 60_000;
const CHAT_LIMIT = 160;

export function isPotion(item) {
  return POTIONS.includes(item);
}

export function offensive(type) {
  return type === 'attack' || type === 'shoot';
}

function cost(type, inCombat) {
  switch (type) {
    case 'move': return inCombat ? MOVE_COST : 0;
    case 'attack': return ATTACK_COST;
    case 'shoot': return SHOOT_COST;
    case 'eat': return EAT_COST;
    case 'use-item': return ITEM_USE_COST;
    default: throw new TypeError('Unknown battle action');
  }
}

export function actionGate(actor, type) {
  if (type === 'move') return actor.moveReadyAt;
  if (offensive(type)) return actor.attackReadyAt;
  return 0;
}

function energyAt(actor, snapshotNow, displayNow) {
  const elapsed = Math.max(0, displayNow - Math.max(snapshotNow, actor.energyAt));
  return Math.min(ENERGY_CAP, actor.energy + elapsed / REGEN_POINT_MS);
}

function lacksEnergy(actor, type, snapshotNow, displayNow, inCombat) {
  return energyAt(actor, snapshotNow, displayNow) + 1e-8 < cost(type, inCombat);
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
  ENERGY_CAP, MOVE_COST, ATTACK_COST, SHOOT_COST, EAT_COST, ITEM_USE_COST, MOVE_COOLDOWN_MS, ATTACK_COOLDOWN_MS,
  SHOOT_RANGE, SHOOT_DAMAGE, PROJECTILE_STEP_MS,
  REGEN_DELAY_MS, REGEN_POINT_MS, RATION_HEAL, POTION_HEAL, POTION_ENERGY, PAUSE_LIMIT_MS, PAUSE_COOLDOWN_MS,
  CHAT_LIMIT, cost, energyAt, lacksEnergy, readyAt,
});
