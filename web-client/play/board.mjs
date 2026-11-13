// SPDX-License-Identifier: GPL-3.0-or-later
import { BattleRules, PRISON } from './rules.mjs';
import { runtimeImage } from './runtime-loader.mjs';
import { drawAvatar, getAvatar } from './avatar.mjs';
import { INN_MOVE_MS } from './session.mjs';
import { animateInn, bardNotes, decorateInn, innTile } from './inn-decor.mjs';
import { HALL_FX, HALL_FX_FRAMES, HALL_MAP } from './inn-hall.mjs';

const TILE = 32;
const ATLAS_TILE = 16;
const WALL_SOURCE_Y = 12 * ATLAS_TILE + 8;
const WALL_SOURCE_HEIGHT = 4;
const WALL_HEIGHT = TILE * WALL_SOURCE_HEIGHT / ATLAS_TILE;
const IMPACT_MS = 590;
const SPEECH_LINES = 3;
const SPEECH_SEGMENTER = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null;
const ENERGY_BANDS = {
  high: { name: 'high', color: '#91cbb0' },
  low: { name: 'low', color: '#e5b766' },
  critical: { name: 'critical', color: '#ef8580' },
};
const INN_VIEW_WIDTH = 15 * TILE;
const INN_VIEW_HEIGHT = 10 * TILE;
const INN_ZOOM = [.55, .75, 1, 1.25, 1.5, 2];
const DEFAULT_ZOOM = INN_ZOOM.indexOf(1);
const INN_ASSETS = ['warrior', 'rogue', 'looking-sign', 'inn-npcs', 'inn-hall', 'inn-hall-fx'];
const DUNGEON_ASSETS = ['rat', 'items', 'tiles_sewers', 'tiles_prison', 'training'];
const TRAINING_PROPS = new Map([['ration-rack', [1, '식량']], ['recovery-kit', [2, '회복']]]);
const NPC_ROLES = ['guard', 'innkeeper', 'vendor', 'bard', 'adventurer'];
const NPC_COLORS = ['#9a8a73', '#ba8452', '#74997a', '#a982a3', '#b9986e'];
const INN = ['############', '#BBBBBBBB..#', '#..........#', '#..TT..TT..#', '#..........#',
  '#..TT..TT..#', '#..........#', '#....DD....#', '############'];
export const DIRECTIONS = [[1, 0], [-1, 0], [0, -1], [0, 1], [1, -1], [-1, -1], [1, 1], [-1, 1]];

export function energyBand(energy) {
  const ratio = energy / BattleRules.ENERGY_CAP;
  return ratio > .5 ? ENERGY_BANDS.high : ratio > .25 ? ENERGY_BANDS.low : ENERGY_BANDS.critical;
}

function speechLines(context, text, width) {
  const letters = SPEECH_SEGMENTER ? [...SPEECH_SEGMENTER.segment(text)].map(({ segment }) => segment) : [...text];
  const lines = [];
  let line = [];
  for (const letter of letters) {
    if (!line.length && !letter.trim()) continue;
    if (line.length && context.measureText(line.join('') + letter).width > width) {
      if (lines.length === SPEECH_LINES - 1) {
        while (line.length && context.measureText(line.join('') + '…').width > width) line.pop();
        lines.push(line.join('').trimEnd() + '…');
        return lines;
      }
      lines.push(line.join('').trimEnd());
      line = [];
    }
    if (line.length || letter.trim()) line.push(letter);
  }
  if (line.length) lines.push(line.join(''));
  return lines;
}

function walkable(map, x, y, dungeon) {
  const tile = map[y]?.[x];
  return tile === '.' || (!dungeon && tile === 'D');
}

export function canStep(map, x, y, dx, dy, dungeon = false) {
  return Math.max(Math.abs(dx), Math.abs(dy)) === 1 && walkable(map, x + dx, y + dy, dungeon) &&
    (!dx || !dy || walkable(map, x + dx, y, dungeon) || walkable(map, x, y + dy, dungeon));
}

export function canStrike(map, x, y, dx, dy) {
  return canStep(map, x, y, dx, dy, true) &&
    (!dx || !dy || (walkable(map, x + dx, y, true) && walkable(map, x, y + dy, true)));
}

export function nextToward(map, x, y, targetX, targetY) {
  const queue = [{ x, y, first: null }];
  const seen = new Set([y * map[0].length + x]);
  for (let i = 0; i < queue.length; i++) {
    const cell = queue[i];
    if (cell.x === targetX && cell.y === targetY) return cell.first;
    for (const [dx, dy] of DIRECTIONS) {
      const nx = cell.x + dx;
      const ny = cell.y + dy;
      const key = ny * map[0].length + nx;
      if (seen.has(key) || !canStep(map, cell.x, cell.y, dx, dy)) continue;
      seen.add(key);
      queue.push({ x: nx, y: ny, first: cell.first ?? [dx, dy] });
    }
  }
  return null;
}

function assetPath(name, version) {
  return new URL(`${version ? `runtime/${version}/` : ''}assets/${name}.png`, import.meta.url).href;
}

function paint(context, color, x, y, width, height) {
  context.fillStyle = color;
  context.fillRect(x, y, width, height);
}

function tileArt(context, image, column, row, x, y, size) {
  context.drawImage(image, column * ATLAS_TILE, row * ATLAS_TILE, ATLAS_TILE, ATLAS_TILE, x, y, size, size);
}

class Figure {
  constructor(actor, now) {
    this.actor = actor;
    this.x = this.fromX = this.toX = actor.x;
    this.y = this.fromY = this.toY = actor.y;
    this.start = now;
    this.duration = 1;
    this.walkUntil = 0;
    this.attackUntil = 0;
    this.hurtUntil = 0;
    this.drinkUntil = 0;
    this.prediction = null;
  }

  advance(now) {
    const fraction = Math.min(1, Math.max(0, (now - this.start) / this.duration));
    this.x = this.fromX + (this.toX - this.fromX) * fraction;
    this.y = this.fromY + (this.toY - this.fromY) * fraction;
    if (this.prediction && this.prediction.expires <= now) this.cancel(now);
  }

  move(x, y, now, interval) {
    if (this.toX === x && this.toY === y) return;
    if (x !== this.toX) this.left = x < this.toX;
    this.fromX = this.x;
    this.fromY = this.y;
    this.toX = x;
    this.toY = y;
    this.start = now;
    const distance = Math.max(Math.abs(x - this.x), Math.abs(y - this.y));
    this.duration = distance > 3 ? 1 : Math.max(35, interval * Math.min(1, distance));
    this.walkUntil = now + this.duration + 60;
  }

  predict(dx, dy, now, interval, expires) {
    if (this.prediction) return;
    this.prediction = { expires, interval };
    this.move(this.actor.x + dx, this.actor.y + dy, now, interval);
  }

  cancel(now) {
    if (!this.prediction) return;
    const { interval } = this.prediction;
    this.prediction = null;
    this.move(this.actor.x, this.actor.y, now, interval);
  }

  attack(x, y, now) {
    this.attackUntil = now + 265;
    this.attackX = Math.sign(x - this.actor.x);
    this.attackY = Math.sign(y - this.actor.y);
    if (this.attackX) this.left = this.attackX < 0;
  }

  accept(actor, target, now, interval, attackFeedback) {
    this.advance(now);
    if (actor.x !== this.actor.x || actor.y !== this.actor.y) {
      this.prediction = null;
      this.move(actor.x, actor.y, now, interval);
    }
    if (!attackFeedback && actor.actionSeq !== this.actor.actionSeq && actor.lastAction === 'attack') {
      this.attack(target?.x ?? actor.x, target?.y ?? actor.y, now);
    }
    if (actor.kind !== 'dummy' && actor.hp !== this.actor.hp && (!attackFeedback || actor.hp > this.actor.hp) &&
        Number.isFinite(actor.hp) && Number.isFinite(this.actor.hp)) {
      this.hurtUntil = now + 590;
      this.hpChange = actor.hp - this.actor.hp;
    }
    if (actor.hp === 0 && this.actor.hp > 0) {
      this.deathAt = now;
      this.cancel(now);
    }
    this.actor = actor;
  }
}

export class Board {
  constructor(canvas, { fitMap = false } = {}) {
    this.canvas = canvas;
    this.context = canvas.getContext('2d');
    this.figures = new Map();
    this.screenCaptions = new WeakMap();
    this.impacts = [];
    this.zoom = DEFAULT_ZOOM;
    this.viewX = this.viewY = 0;
    this.panX = this.panY = 0;
    this.fitMap = fitMap;
    this.gameViewport = canvas.classList?.contains('game-board') === true;
    this.images = Object.fromEntries(INN_ASSETS.map((name) => [name, this.loadImage(name)]));
    this.set(null, false);
    if (this.gameViewport) {
      this.speechCanvas = canvas.ownerDocument.createElement('canvas');
      this.speechCanvas.className = 'speech-board';
      this.speechCanvas.setAttribute('aria-hidden', 'true');
      this.speechCanvas.hidden = true;
      this.speechContext = this.speechCanvas.getContext('2d');
      canvas.after(this.speechCanvas);
      const observer = new ResizeObserver(() => {
        this.resizeViewport();
        this.cameraAt = null;
      });
      observer.observe(canvas);
    }
  }

  loadImage(name, version) {
    const image = new Image();
    if (name.startsWith('tiles_')) image.addEventListener('load', () => {
      if (this.textureName === name) this.terrain = null;
    });
    if (name === 'inn-hall') image.addEventListener('load', () => {
      if (!this.dungeon) this.terrain = null;
    });
    image.src = assetPath(name, version);
    return image;
  }

  set(snapshot, dungeon, now = performance.now(), version, chatWall = snapshot?.wallNow) {
    const scene = dungeon ? snapshot?.id : snapshot?.selfId;
    const textureName = dungeon ? (snapshot?.theme === PRISON ? 'tiles_prison' : 'tiles_sewers') : null;
    const sceneChanged = scene !== this.scene || dungeon !== this.dungeon || textureName !== this.textureName;
    if (sceneChanged) {
      this.figures.clear();
      this.impacts.length = 0;
      this.lastEvent = 0;
      this.lastSpeechId = 0;
      this.terrain = null;
      this.scene = scene;
      this.zoom = DEFAULT_ZOOM;
      this.panX = this.panY = 0;
      this.cameraAt = null;
    }
    this.textureName = textureName;
    if (dungeon) {
      if (this.assetVersion !== version) {
        for (const name of DUNGEON_ASSETS) delete this.images[name];
        this.assetVersion = version;
        this.terrain = null;
      }
      for (const name of ['rat', 'items', textureName]) {
        this.images[name] ??= runtimeImage(version, name) ?? this.loadImage(name, version);
      }
      if (snapshot?.trainingTools?.length) this.images.training ??= runtimeImage(version, 'training') ?? this.loadImage('training', version);
    }
    this.dungeon = dungeon;
    this.snapshot = snapshot;
    const map = snapshot?.map ?? INN;
    const mapKey = map.join('|');
    if (mapKey !== this.mapKey || sceneChanged) {
      this.map = map;
      this.mapKey = mapKey;
      this.terrain = null;
      this.worldWidth = map[0].length * TILE;
      this.worldHeight = map.length * TILE;
      this.resizeViewport();
    }
    const actors = (dungeon ? snapshot?.actors : snapshot?.people) ?? [];
    const previousSelf = this.figures.get(snapshot?.selfId)?.actor;
    const self = actors.find(({ id }) => id === snapshot?.selfId);
    if (previousSelf && self && (previousSelf.x !== self.x || previousSelf.y !== self.y)) this.panX = this.panY = 0;
    const present = new Set(actors.map((actor) => actor.id));
    for (const id of this.figures.keys()) if (!present.has(id)) this.figures.delete(id);
    for (const actor of actors) {
      let figure = this.figures.get(actor.id);
      const interval = dungeon ? (actor.kind === 'enemy' ? 270 : 180) : 145;
      if (figure) figure.accept(actor, actors.find((other) => other.id === actor.targetId), now, interval,
        Array.isArray(snapshot.attackEvents));
      else {
        figure = new Figure(actor, now);
        this.figures.set(actor.id, figure);
      }
      if (!dungeon || actor.kind === 'player') figure.avatar = getAvatar(actor.appearance);
    }
    if (sceneChanged) this.updateCamera(now);
    if (!dungeon) {
      for (const event of snapshot?.events ?? []) {
        if (event.id <= this.lastEvent) continue;
        this.lastEvent = event.id;
        if (event.kind === 'drink' && snapshot.now - event.time < 1700) {
          const figure = this.figures.get(event.playerId);
          if (figure) figure.drinkUntil = now + 1100;
        }
      }
    }
    const chat = dungeon ? snapshot?.messages : snapshot?.events?.filter(({ kind }) => kind === 'chat');
    this.updateSpeech(chat, dungeon ? chatWall : snapshot?.now, sceneChanged && dungeon);
  }

  updateSpeech(messages = [], serverWall, newDungeon) {
    const receivedAt = performance.now();
    for (const message of messages) {
      if (!Number.isSafeInteger(message.id) || message.id <= this.lastSpeechId) continue;
      this.lastSpeechId = message.id;
      const figure = this.figures.get(message.actorId ?? message.playerId);
      if (!figure || typeof message.text !== 'string' || !message.text) continue;
      const timed = message.time > 0 && Number.isFinite(serverWall);
      if (!timed && newDungeon) continue;
      const duration = Math.min(8000, 3600 + 30 * [...message.text].length);
      const elapsed = timed ? Math.max(0, serverWall - message.time) : 0;
      if (elapsed < duration) figure.speech = { id: message.id, text: message.text, until: receivedAt + duration - elapsed };
    }
  }

  hasInnCamera() {
    return !this.fitMap && !this.dungeon &&
      (this.gameViewport || this.worldWidth > INN_VIEW_WIDTH || this.worldHeight > INN_VIEW_HEIGHT);
  }

  resizeViewport() {
    if (this.fitMap && !this.dungeon) {
      if (this.canvas.width !== this.worldWidth) this.canvas.width = this.worldWidth;
      if (this.canvas.height !== this.worldHeight) this.canvas.height = this.worldHeight;
      return;
    }
    const zoom = INN_ZOOM[this.zoom];
    let width = this.hasInnCamera() ? Math.round(INN_VIEW_WIDTH / zoom) : this.worldWidth;
    let height = this.hasInnCamera() ? Math.round(INN_VIEW_HEIGHT / zoom) : this.worldHeight;
    if (this.gameViewport) {
      const rect = this.canvas.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) return;
      let scale = Math.max(.75, Math.min(rect.width / (10 * TILE), rect.height / (10 * TILE), 3));
      if (this.dungeon && rect.height > rect.width) {
        // Keep a small dungeon close enough to read on a tall viewport. Pan and zoom can reveal the rest.
        scale = Math.max(scale, Math.min(rect.height * .78 / this.worldHeight, rect.width / (5 * TILE), 3));
      }
      scale *= zoom;
      width = Math.max(1, Math.round(rect.width / scale));
      height = Math.max(1, Math.round(rect.height / scale));
    }
    if (this.canvas.width !== width) this.canvas.width = width;
    if (this.canvas.height !== height) this.canvas.height = height;
  }

  zoomInn(direction, now) {
    return this.hasInnCamera() && this.zoomView(direction, now);
  }

  zoomView(direction, now) {
    if (!this.gameViewport && !this.hasInnCamera()) return false;
    const index = Math.max(0, Math.min(INN_ZOOM.length - 1, this.zoom + direction));
    if (index !== this.zoom) {
      this.zoom = index;
      this.resizeViewport();
      this.cameraAt = null;
      this.updateCamera(now);
    }
    return true;
  }

  cameraTarget() {
    const self = this.figures.get(this.snapshot?.selfId);
    const focusX = self ? (self.x + .5) * TILE : this.worldWidth / 2;
    const focusY = self ? (self.y + .5) * TILE : this.worldHeight / 2;
    return [
      cameraOffset(focusX, this.worldWidth, this.canvas.width),
      cameraOffset(focusY, this.worldHeight, this.canvas.height),
    ];
  }

  panView(dx, dy, now) {
    if (!this.gameViewport) return false;
    const [x, y] = this.cameraTarget();
    this.panX = cameraOffset(this.viewX + this.canvas.width / 2 - dx, this.worldWidth, this.canvas.width) - x;
    this.panY = cameraOffset(this.viewY + this.canvas.height / 2 - dy, this.worldHeight, this.canvas.height) - y;
    this.cameraAt = null;
    this.updateCamera(now);
    return true;
  }

  updateCamera(now) {
    if ((this.fitMap && !this.dungeon) || (!this.gameViewport && !this.hasInnCamera())) {
      this.viewX = this.viewY = 0;
      return;
    }
    const [baseX, baseY] = this.cameraTarget();
    const x = cameraOffset(baseX + this.canvas.width / 2 + this.panX, this.worldWidth, this.canvas.width);
    const y = cameraOffset(baseY + this.canvas.height / 2 + this.panY, this.worldHeight, this.canvas.height);
    const fraction = this.cameraAt == null ? 1 : 1 - Math.exp(-Math.min(100, now - this.cameraAt) / 65);
    this.cameraAt = now;
    this.cameraX = fraction === 1 ? x : this.cameraX + (x - this.cameraX) * fraction;
    this.cameraY = fraction === 1 ? y : this.cameraY + (y - this.cameraY) * fraction;
    this.viewX = Math.round(this.cameraX);
    this.viewY = Math.round(this.cameraY);
  }

  showAttackEvents(events, now) {
    for (const event of events) {
      const hit = event.result === 'hit';
      if (hit && event.action === 'attack') this.figures.get(event.actorId)?.attack(event.x, event.y, now);
      const target = this.figures.get(event.targetId);
      if (hit && target?.actor.kind === 'dummy') target.rattleAt = now;
      if (hit || event.action === 'shoot' && ['blocked', 'ally_blocked'].includes(event.result)) {
        this.impacts.push({ x: event.x, y: event.y, amount: hit ? event.amount : 0, since: now });
      }
    }
    this.impacts = this.impacts.slice(-16);
  }

  predict(dx, dy, now) {
    this.panX = this.panY = 0;
    this.figures.get(this.snapshot?.selfId)?.predict(dx, dy, now, 180, now + 550);
  }

  moveInn(snapshot, actor, now = performance.now()) {
    this.snapshot = snapshot;
    this.panX = this.panY = 0;
    this.figures.get(actor.id)?.accept(actor, null, now, INN_MOVE_MS + 10, false);
  }

  cancel(now) { this.figures.get(this.snapshot?.selfId)?.cancel(now); }

  // The painted hall replaces the drawn tiles only where it fits the served map.
  hallArt() {
    const art = this.images['inn-hall'];
    return !this.dungeon && art?.naturalWidth && this.map.length === HALL_MAP.length &&
      this.map.every((row, y) => row === HALL_MAP[y]) ? art : null;
  }

  animateHall(context, now) {
    const sheet = this.images['inn-hall-fx'];
    if (!sheet?.naturalWidth) return;
    const scale = TILE / ATLAS_TILE;
    for (const [x, y, width, height, sheetY] of HALL_FX) {
      const frame = Math.floor((now + x * 53) / 135) % HALL_FX_FRAMES;
      context.drawImage(sheet, frame * width, sheetY, width, height, x * scale, y * scale, width * scale, height * scale);
    }
  }

  getTerrain() {
    if (this.terrain) return this.terrain;
    const image = document.createElement('canvas');
    image.width = this.worldWidth;
    image.height = this.worldHeight;
    const context = image.getContext('2d');
    context.imageSmoothingEnabled = false;
    const tiles = this.images[this.textureName];
    const prison = this.textureName === 'tiles_prison';
    const palette = prison ? ['#5b4f55', '#40373d', '#362e33'] : ['#4e5144', '#333b31', '#2e352d'];
    const wallRows = [];
    const foreground = this.dungeon ? document.createElement('canvas') : null;
    let foregroundContext;
    if (foreground) {
      foreground.width = this.worldWidth;
      foreground.height = this.map.length * WALL_HEIGHT;
      foregroundContext = foreground.getContext('2d');
      foregroundContext.imageSmoothingEnabled = false;
    }
    const hall = this.hallArt();
    if (hall) {
      context.drawImage(hall, 0, 0, this.worldWidth, this.worldHeight);
      this.wallEdges = { image: null, rows: [] };
      this.terrain = image;
      return image;
    }
    this.map.forEach((row, y) => {
      [...row].forEach((cell, x) => {
        if (!this.dungeon) return innTile(context, this.map, x, y);
        const variation = (x * 31 + y * 17) % 9 === 0;
        const wall = cell === '#';
        if (tiles.naturalWidth) {
          tileArt(context, tiles, wall ? Number(variation) : variation ? 6 : 0, wall ? 3 : 0,
            x * TILE, y * TILE, TILE);
        } else {
          paint(context, palette[wall ? 0 : variation ? 1 : 2], x * TILE, y * TILE, TILE, TILE);
        }
        if (!wall || this.map[y - 1]?.[x] !== '.') return;
        // Upstream's stitched wall overhang uses columns 0–3. Keep just its top four masonry pixels.
        const variant = Number(row[x + 1] === '.') + 2 * Number(row[x - 1] === '.');
        if (tiles.naturalWidth) {
          foregroundContext.drawImage(tiles, variant * ATLAS_TILE, WALL_SOURCE_Y, ATLAS_TILE, WALL_SOURCE_HEIGHT,
            x * TILE, y * WALL_HEIGHT, TILE, WALL_HEIGHT);
        } else {
          paint(foregroundContext, palette[0], x * TILE, y * WALL_HEIGHT, TILE, WALL_HEIGHT);
        }
        if (wallRows.at(-1) !== y) wallRows.push(y);
      });
    });
    if (!this.dungeon) decorateInn(context, this.map);
    this.wallEdges = { image: foreground, rows: wallRows };
    this.terrain = image;
    return image;
  }

  draw(now, selected, destination, aim, gameNow, wallNow = performance.now(), waitingForEnergy = false) {
    const context = this.context;
    for (const figure of this.figures.values()) figure.advance(now);
    this.updateCamera(now);
    paint(context, '#121611', 0, 0, this.canvas.width, this.canvas.height);
    context.save();
    context.translate(-this.viewX, -this.viewY);
    context.imageSmoothingEnabled = false;
    context.drawImage(this.getTerrain(), 0, 0);
    if (!this.dungeon) {
      if (this.hallArt()) this.animateHall(context, now);
      else animateInn(context, this.map, now);
    }
    const snapshot = this.snapshot;
    const selfFigure = this.figures.get(snapshot?.selfId);
    const self = selfFigure?.actor;
    const fatigued = waitingForEnergy ? selfFigure : null;
    const target = this.figures.get(selected)?.actor;
    if (target?.hp > 0 && !aim) this.marker(target.x, target.y, '#f4876b');
    const queued = snapshot?.queued;
    if (this.dungeon && self && queued?.type === 'move') this.marker(self.x + queued.dx, self.y + queued.dy, '#89d1b2');
    if (this.dungeon && queued?.type === 'shoot') {
      const [x, y] = queued.target.split(',').map(Number);
      this.marker(x, y, '#f0c979');
    }
    if (destination) this.marker(destination[0], destination[1], '#edb776');
    const objects = [];
    for (const figure of this.figures.values()) {
      objects.push({ depth: (figure.y + .87) * TILE, figure });
    }
    if (!this.dungeon) {
      for (const npc of snapshot?.npcs ?? []) objects.push({ depth: (npc.y + .87) * TILE, npc });
    }
    for (const drop of snapshot?.drops ?? []) {
      if (drop.pickedBy == null) objects.push({ depth: (drop.y + .84) * TILE, drop });
    }
    for (const tool of snapshot?.trainingTools ?? []) {
      objects.push({ depth: (tool.y + .8) * TILE, tool });
    }
    for (const projectile of snapshot?.projectiles ?? []) {
      const fraction = Math.min(1, Math.max(0, 1 - (projectile.stepAt - gameNow) / BattleRules.PROJECTILE_STEP_MS));
      const dx = projectile.nextX - projectile.x;
      const dy = projectile.nextY - projectile.y;
      const x = projectile.x + dx * fraction;
      const y = projectile.y + dy * fraction;
      objects.push({ depth: (y + .61) * TILE, flight: { x, y, dx, dy } });
    }
    objects.sort((left, right) => left.depth - right.depth);
    const rows = this.wallEdges.rows;
    let rowIndex = 0;
    for (const object of objects) {
      while (rowIndex < rows.length && rows[rowIndex] * TILE <= object.depth) this.drawWallRow(rows[rowIndex++]);
      if (object.figure) this.drawFigure(object.figure, now);
      else if (object.drop) this.drawDrop(object.drop);
      else if (object.tool) {
        const prop = TRAINING_PROPS.get(object.tool.id);
        if (prop) this.drawTraining(prop[0], (object.tool.x + .5) * TILE, (object.tool.y + .8) * TILE);
      } else if (object.npc) this.drawNpc(object.npc, now);
      else this.drawProjectile(object.flight);
    }
    while (rowIndex < rows.length) this.drawWallRow(rows[rowIndex++]);
    for (const { figure, npc, tool } of objects) {
      if (figure) this.drawFigureLabels(figure, now, gameNow);
      if (figure && figure === fatigued) this.drawSweat(figure, now);
      if (npc && !this.speechCanvas) {
        this.caption(npc.name, (npc.x + .5) * TILE, Math.max(9, npc.y * TILE - 5), '#f5d9a9');
      }
      const prop = tool && TRAINING_PROPS.get(tool.id);
      if (prop) this.caption(prop[1], (tool.x + .5) * TILE, (tool.y + .97) * TILE, '#d4e7c5');
    }
    this.impacts = this.impacts.filter((impact) => now - impact.since < IMPACT_MS);
    for (const impact of this.impacts) this.drawImpact(impact, now);
    if (aim) this.drawAim(...aim);
    if (!this.dungeon) {
      for (const { figure } of objects) if (figure?.actor.queued) this.drawSign(figure);
    }
    context.restore();
    this.drawSpeech(wallNow, fatigued);
  }

  drawSpeech(now, fatigued) {
    const figures = [...this.figures.values()];
    const speakers = figures.filter(({ speech }) => speech?.until > now);
    const names = figures.filter((figure) => this.hasScreenName(figure));
    const overlay = this.speechCanvas;
    const npcs = overlay && !this.dungeon ? this.snapshot?.npcs ?? [] : [];
    const hideSpeech = speakers.length === 0 && names.length === 0 && npcs.length === 0 && !fatigued;
    if (overlay && overlay.hidden !== hideSpeech) overlay.hidden = hideSpeech;
    if (hideSpeech) return;
    speakers.sort((a, b) => b.speech.id - a.speech.id);
    const canvas = this.canvas;
    const context = overlay ? this.speechContext : this.context;
    const width = overlay ? canvas.clientWidth : canvas.width;
    const height = overlay ? canvas.clientHeight : canvas.height;
    if (!width || !height) return;
    if (overlay) {
      // Keep text at screen resolution while the board scales its pixel art.
      const ratio = Math.min(globalThis.devicePixelRatio || 1, 2);
      const pixelWidth = Math.round(width * ratio);
      const pixelHeight = Math.round(height * ratio);
      if (overlay.width !== pixelWidth) overlay.width = pixelWidth;
      if (overlay.height !== pixelHeight) overlay.height = pixelHeight;
      context.setTransform(pixelWidth / width, 0, 0, pixelHeight / height, 0, 0);
      context.clearRect(0, 0, width, height);
    }
    const scaleX = width / canvas.width;
    const scaleY = height / canvas.height;
    const fontSize = 14;
    const lineHeight = 20;
    const pad = 7;
    const tail = 5;
    const margin = pad + tail;
    const textWidth = Math.min(192, width - 2 * (margin + pad));
    if (textWidth < fontSize) return;
    const placed = [];
    context.save();
    const captions = this.drawScreenNames(context, names.concat(npcs), width, height, scaleX, scaleY);
    context.textBaseline = 'top';
    context.font = `400 ${fontSize}px "Malgun Gothic", system-ui, sans-serif`;
    context.textAlign = 'left';
    context.lineWidth = .75;
    for (const figure of speakers) {
      const headX = Math.round(((figure.x + .5) * TILE - this.viewX) * scaleX);
      const feetY = Math.round(((figure.y + .87) * TILE - this.viewY) * scaleY);
      if (headX < 0 || headX > width || feetY < 0 || feetY - TILE * scaleY > height) continue;
      const headY = Math.min(feetY - (figure.actor.queued ? 57 : 36) * scaleY, (captions.get(figure) ?? Infinity) - 4);
      const speech = figure.speech;
      if (!speech.layout || speech.layout.fontSize !== fontSize || speech.layout.textWidth !== textWidth) {
        const lines = speechLines(context, speech.text, textWidth);
        const width = Math.ceil(Math.max(0, ...lines.map(line => context.measureText(line).width)));
        speech.layout = { lines, width, fontSize, textWidth };
      }
      const { lines, width: lineWidth } = speech.layout;
      const boxWidth = lineWidth + pad * 2;
      const boxHeight = lines.length * lineHeight + pad * 2;
      const left = Math.max(margin, Math.min(width - margin - boxWidth, Math.round(headX - boxWidth / 2)));
      let top = Math.max(margin, Math.min(height - margin - boxHeight - tail, headY - boxHeight - tail));
      for (const other of placed) {
        if (left < other.right && left + boxWidth > other.left && top < other.bottom && top + boxHeight + tail > other.top) {
          top = Math.max(margin, other.top - boxHeight - tail - pad);
        }
      }
      const bottom = top + boxHeight;
      const right = left + boxWidth;
      const tip = Math.max(left + tail, Math.min(right - tail, headX));
      placed.push({ left, right, top, bottom: bottom + tail });
      context.globalAlpha = Math.min(1, (speech.until - now) / 250);
      context.fillStyle = '#242019';
      context.strokeStyle = '#9c8466';
      context.beginPath();
      context.moveTo(left, top);
      context.lineTo(right, top);
      context.lineTo(right, bottom);
      context.lineTo(tip + tail, bottom);
      context.lineTo(tip, bottom + tail);
      context.lineTo(tip - tail, bottom);
      context.lineTo(left, bottom);
      context.closePath();
      context.fill();
      context.stroke();
      context.fillStyle = '#fff0d0';
      for (const [index, line] of lines.entries()) context.fillText(line, left + pad, top + pad + index * lineHeight);
    }
    if (fatigued) {
      const headX = Math.round(((fatigued.x + .5) * TILE - this.viewX) * scaleX);
      const feetY = Math.round(((fatigued.y + .87) * TILE - this.viewY) * scaleY);
      if (headX >= 0 && headX <= width && feetY >= 0 && feetY - TILE * scaleY <= height) {
        const bottom = Math.min(feetY - 43 * scaleY, (captions.get(fatigued) ?? Infinity) - 3);
        this.drawFatigueLabel(context, headX, bottom, width, height, margin, placed);
      }
    }
    context.restore();
  }

  hasScreenName({ actor }) {
    return this.speechCanvas && actor.hp !== 0 && !actor.queued &&
      (!this.dungeon || actor.kind === 'player' || actor.kind === 'guide');
  }

  drawScreenNames(context, names, width, height, scaleX, scaleY) {
    const fontSize = Math.round(Math.max(15, Math.min(28, 10.5 * scaleY)));
    const maxWidth = Math.min(width - 12, 196, Math.max(156, 1.8 * TILE * scaleX));
    const tops = new Map();
    const placed = [];
    context.font = `600 ${fontSize}px "Malgun Gothic", system-ui, sans-serif`;
    context.textAlign = 'center';
    context.textBaseline = 'bottom';
    context.strokeStyle = '#151713';
    context.lineJoin = 'round';
    context.lineWidth = 2;
    for (const figure of names) {
      const npc = !figure.actor;
      const actor = figure.actor ?? figure;
      const x = ((figure.x + .5) * TILE - this.viewX) * scaleX;
      const feet = ((figure.y + .87) * TILE - this.viewY) * scaleY;
      if (x < 0 || x > width || feet < 0 || feet - TILE * scaleY > height) continue;
      let caption = this.screenCaptions.get(figure);
      if (!caption || caption.name !== actor.name || caption.fontSize !== fontSize || caption.maxWidth !== maxWidth) {
        const label = this.fitName(actor.name, context, maxWidth);
        caption = { name: actor.name, fontSize, maxWidth, label, width: context.measureText(label).width };
        this.screenCaptions.set(figure, caption);
      }
      const { label, width: labelWidth } = caption;
      const half = labelWidth / 2 + 4;
      const center = Math.round(Math.max(half, Math.min(width - half, x)));
      const labelY = npc ? (figure.y * TILE - 5 - this.viewY) * scaleY : feet - 31 * scaleY - 2;
      let bottom = Math.max(fontSize + 3, Math.round(labelY));
      for (const other of placed) {
        if (center - half < other.right && center + half > other.left &&
            bottom - fontSize < other.bottom && bottom > other.top) bottom = Math.max(fontSize + 3, other.top - 3);
      }
      const top = bottom - fontSize;
      placed.push({ left: center - half, right: center + half, top, bottom });
      if (!npc) tops.set(figure, top);
      context.fillStyle = npc ? '#f5d9a9' : actor.id === this.snapshot?.selfId ? '#ffe1a3' : '#e2efdb';
      context.strokeText(label, center, bottom);
      context.fillText(label, center, bottom);
    }
    return tops;
  }

  drawFatigueLabel(context, headX, bottom, width, height, margin, bubbles) {
    const label = '(지쳤음)';
    context.font = '400 13px "Malgun Gothic", system-ui, sans-serif';
    context.textAlign = 'center';
    context.globalAlpha = 1;
    const labelWidth = Math.ceil(context.measureText(label).width) + 12;
    const labelHeight = 20;
    const gap = 4;
    let left = Math.max(margin, Math.min(width - margin - labelWidth, headX - labelWidth / 2));
    let top = Math.max(margin, bottom - labelHeight);
    const fits = (x, y) => x >= margin && x + labelWidth <= width - margin &&
      y >= margin && y + labelHeight <= height - margin && bubbles.every(bubble =>
        x + labelWidth + gap <= bubble.left || x >= bubble.right + gap ||
        y + labelHeight + gap <= bubble.top || y >= bubble.bottom + gap);
    if (!fits(left, top)) {
      const beside = bubbles.flatMap(bubble => [bubble.left - gap - labelWidth, bubble.right + gap])
        .sort((a, b) => Math.abs(a - left) - Math.abs(b - left)).find(x => fits(x, top));
      if (beside != null) left = beside;
      else top = bubbles.map(bubble => bubble.bottom + gap)
        .sort((a, b) => a - b).find(y => fits(left, y));
    }
    if (top == null) return;
    paint(context, '#242019ed', left, top, labelWidth, labelHeight);
    context.fillStyle = '#f5d9a9';
    context.fillText(label, left + labelWidth / 2, top + 3);
  }

  drawNpc(npc, now) {
    const role = NPC_ROLES.indexOf(npc.role);
    const x = npc.x * TILE;
    const y = Math.round((npc.y + .87) * TILE);
    const image = this.images['inn-npcs'];
    const frame = Math.floor((now + npc.x * 179 + npc.y * 71) / 660) % 4 === 3 ? 1 : 0;
    paint(this.context, '#231a15a6', x + 7, y - 3, 18, 5);
    if (role >= 0 && image.naturalWidth) {
      tileArt(this.context, image, role, frame, x, y - TILE, TILE);
    } else {
      paint(this.context, NPC_COLORS[role] ?? '#a98b6c', x + 10, y - 22, 12, 22);
    }
    if (npc.role === 'bard') bardNotes(this.context, x, y, now);
  }

  drawProjectile({ x, y, dx, dy }) {
    const context = this.context;
    context.save();
    context.translate(Math.round((x + .5) * TILE), Math.round((y + .61) * TILE));
    context.rotate(Math.atan2(dy, dx));
    paint(context, '#171612', -9, -2, 16, 4);
    paint(context, '#edc77a', -8, -1, 12, 2);
    paint(context, '#fff0bf', 3, -2, 4, 4);
    context.restore();
  }

  drawImpact({ x, y, amount, since }, now) {
    const fraction = (now - since) / IMPACT_MS;
    const px = (x + .5) * TILE;
    const py = (y + .58) * TILE;
    const context = this.context;
    context.save();
    context.globalAlpha = 1 - fraction;
    const radius = 2 + Math.round(fraction * 10);
    const color = amount > 0 ? '#ffa68b' : '#f1bd71';
    for (const [dx, dy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
      paint(context, color, px + dx * radius, py + dy * radius, 2, 2);
    }
    if (amount > 0) this.caption(`-${amount}`, px, py - 15 - fraction * 14, color);
    context.restore();
  }

  drawAim(x, y) {
    const px = (x + .5) * TILE;
    const py = (y + .5) * TILE;
    const context = this.context;
    context.save();
    context.strokeStyle = '#171612';
    context.lineWidth = 3;
    context.strokeRect(px - 12, py - 12, 24, 24);
    context.strokeStyle = '#ffe1a3';
    context.lineWidth = 1;
    context.strokeRect(px - 12, py - 12, 24, 24);
    paint(context, '#ffe1a3', px - 4, py, 9, 1);
    paint(context, '#ffe1a3', px, py - 4, 1, 9);
    context.restore();
  }

  drawWallRow(row) {
    const width = this.worldWidth;
    this.context.drawImage(this.wallEdges.image, 0, row * WALL_HEIGHT, width, WALL_HEIGHT,
      0, row * TILE - WALL_HEIGHT, width, WALL_HEIGHT);
  }

  drawDrop(drop) {
    const image = this.images.items;
    if (image.naturalWidth) {
      const gold = drop.item === 'gold';
      tileArt(this.context, image, gold ? 2 : 5, gold ? 1 : 27, drop.x * TILE + 7, drop.y * TILE + 12, 19);
    } else {
      paint(this.context, '#ddad5c', drop.x * TILE + 12, drop.y * TILE + 18, 8, 6);
    }
  }

  drawTraining(frame, x, y) {
    const { context } = this;
    const image = this.images.training;
    if (image?.naturalWidth) {
      context.drawImage(image, frame * ATLAS_TILE, 0, ATLAS_TILE, ATLAS_TILE, Math.round(x) - 16, Math.round(y) - 32, 32, 32);
    }
  }

  marker(x, y, color) {
    const context = this.context;
    context.save();
    context.globalAlpha = .72;
    context.strokeStyle = color;
    context.lineWidth = 2;
    const bottom = this.dungeon && this.map[y]?.[x] === '.' && this.map[y + 1]?.[x] === '#' ? WALL_HEIGHT + 1 : 3;
    context.strokeRect(x * TILE + 3, y * TILE + 3, TILE - 6, TILE - 3 - bottom);
    context.restore();
  }

  drawSign(figure) {
    const image = this.images['looking-sign'];
    if (!image.naturalWidth) return;
    const x = Math.round((figure.x + .5) * TILE - 24);
    const y = Math.max(0, Math.round(figure.y * TILE - 25));
    this.context.drawImage(image, x, y, 48, 23);
    figure.sign = { x, y };
  }

  drawFigure(figure, now) {
    const { actor } = figure;
    const x = Math.round((figure.x + .5) * TILE);
    const y = Math.round((figure.y + .87) * TILE);
    if (actor.kind === 'dummy') {
      const elapsed = now - (figure.rattleAt ?? -Infinity);
      const rattle = elapsed < 160 ? Math.sin(elapsed / 160 * 2 * Math.PI) * 3 : 0;
      this.drawTraining(0, x + rattle, y);
      return;
    }
    const own = actor.id === this.snapshot?.selfId;
    const enemy = actor.kind === 'enemy';
    const first = actor.id === this.snapshot?.actors?.[0]?.id;
    const image = this.images[enemy ? 'rat' : own && !this.dungeon || first ? 'warrior' : 'rogue'];
    const context = this.context;
    const size = enemy ? 16 : 12;
    const attackTime = Math.max(0, figure.attackUntil - now);
    const dead = actor.hp === 0;
    let frame = Math.floor(now / 620) % 4 === 3 ? 1 : 0;
    if (dead) frame = (enemy ? 11 : 8) + Math.min(3, Math.floor((now - (figure.deathAt ?? 0)) / 70));
    else if (attackTime > 0) frame = (enemy ? 2 : 13) + Math.min(enemy ? 3 : 2, Math.floor((265 - attackTime) / 67));
    else if (now < figure.walkUntil) frame = (enemy ? 6 : 2) + Math.floor(now / 50) % (enemy ? 5 : 6);
    else if (now < figure.drinkUntil) frame = 16 + Math.floor(now / 125) % 2;
    const lunge = Math.sin(attackTime / 265 * Math.PI) * 6;
    context.save();
    context.globalAlpha = dead ? .5 : 1;
    paint(context, '#201b176e', x - 8, y - 3, 16, 5);
    context.translate(x + lunge * (figure.attackX ?? 0), y + lunge * (figure.attackY ?? 0));
    if (figure.left) context.scale(-1, 1);
    if (!drawAvatar(context, figure.avatar, frame, -size, -30, size * 2, 30)) {
      if (image.naturalWidth) context.drawImage(image, size * frame, 0, size, 15, -size, -30, size * 2, 30);
      else paint(context, own ? '#d8ad65' : '#95bfb4', -6, -20, 12, 20);
    }
    context.restore();
  }

  drawFigureLabels(figure, now, gameNow) {
    const { actor } = figure;
    if (actor.hp === 0) return;
    const own = actor.id === this.snapshot?.selfId;
    const enemy = actor.kind === 'enemy';
    const dummy = actor.kind === 'dummy';
    const context = this.context;
    const x = Math.round((figure.x + .5) * TILE);
    const y = Math.round((figure.y + .87) * TILE);
    if (this.dungeon) {
      paint(context, '#222620', x - 11, y + 1, 22, 3);
      paint(context, enemy || dummy ? '#dd957b' : own ? '#dfb96c' : '#9bd2c0', x - 11, y + 1, 22 * actor.hp / actor.maxHp, 3);
      if (actor.kind === 'player' || actor.kind === 'guide') {
        const snapshotNow = this.snapshot.now;
        const energy = BattleRules.energyAt(actor, snapshotNow, gameNow ?? snapshotNow);
        const { color } = energyBand(energy);
        paint(context, '#222620', x - 11, y + 5, 22, 3);
        paint(context, color + '45', x - 11, y + 5, 22, 3);
        if (energy > 0) paint(context, color, x - 11, y + 5, 22 * energy / BattleRules.ENERGY_CAP, 3);
      }
    }
    if (!enemy && !actor.queued && !this.hasScreenName(figure)) {
      if (figure.captionName !== actor.name) {
        figure.captionName = actor.name;
        context.font = 'bold 8px "Malgun Gothic", sans-serif';
        figure.shortName = this.fitName(dummy ? '허수아비' : actor.name, context, TILE - 5);
      }
      this.caption(figure.shortName, x, Math.max(9, y - 32), own ? '#ffe1a3' : '#d8e4cf');
    }
    if (figure.hurtUntil > now) {
      const change = figure.hpChange;
      this.caption(change > 0 ? `+${change}` : String(change), x,
        y - (this.hasScreenName(figure) ? 47 : 37) - (1 - (figure.hurtUntil - now) / 590) * 14, change > 0 ? '#a4e4b4' : '#ffa68b');
    }
  }

  drawSweat(figure, now) {
    const headX = (figure.x + .5) * TILE;
    const headY = (figure.y + .87) * TILE - 30;
    for (const [index, offset] of [-15, 9, 17].entries()) {
      const phase = (now / 680 + index / 3) % 1;
      const x = Math.round(headX + offset + Math.sign(offset) * 6 * phase);
      const y = Math.round(headY + 10 * phase - index * 2);
      paint(this.context, '#204c64', x + 2, y, 2, 10);
      paint(this.context, '#204c64', x, y + 4, 6, 4);
      paint(this.context, '#69c6e3', x + 2, y + 2, 2, 6);
      paint(this.context, '#c5f5fa', x + 2, y + 2, 2, 2);
    }
  }

  caption(text, x, y, color) {
    const context = this.context;
    context.textAlign = 'center';
    context.font = 'bold 8px "Malgun Gothic", sans-serif';
    context.strokeStyle = '#171612';
    context.lineWidth = 3;
    context.strokeText(text, x, y);
    context.fillStyle = color;
    context.fillText(text, x, y);
  }

  fitName(name, context, width) {
    if (context.measureText(name).width <= width) return name;
    const letters = SPEECH_SEGMENTER ? [...SPEECH_SEGMENTER.segment(name)].map(({ segment }) => segment) : [...name];
    let shorter;
    do {
      letters.pop();
      shorter = letters.join('') + '…';
    } while (letters.length && context.measureText(shorter).width > width);
    return shorter;
  }

  point(event) {
    const rect = this.canvas.getBoundingClientRect();
    const x = (event.clientX - rect.left) * this.canvas.width / rect.width + this.viewX;
    const y = (event.clientY - rect.top) * this.canvas.height / rect.height + this.viewY;
    return [x, y];
  }

  npcAt(px, py) {
    return (this.snapshot?.npcs ?? []).find(({ x, y }) =>
      px >= x * TILE && px < (x + 1) * TILE && py >= (y - .13) * TILE && py < (y + 1) * TILE);
  }

  signAt(x, y) {
    for (const { actor, sign } of this.figures.values()) {
      if (actor.id !== this.snapshot?.selfId && actor.queued && sign &&
          x >= sign.x && x < sign.x + 48 && y >= sign.y && y < sign.y + 23) return actor.id;
    }
    return null;
  }

  cellAt(x, y) { return [Math.floor(x / TILE), Math.floor(y / TILE)]; }
}

function cameraOffset(focus, world, view) {
  return view >= world ? (world - view) / 2 : Math.min(world - view, Math.max(0, focus - view / 2));
}
