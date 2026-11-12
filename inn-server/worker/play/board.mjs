// SPDX-License-Identifier: GPL-3.0-or-later
import { PRISON } from './layout.mjs';

const TILE = 32;
const ASSETS = ['warrior', 'rogue', 'rat', 'looking-sign', 'items', 'tiles_sewers'];
const INN = ['############', '#BBBBBBBB..#', '#..........#', '#..TT..TT..#', '#..........#',
  '#..TT..TT..#', '#..........#', '#....DD....#', '############'];
export const DIRECTIONS = [[1, 0], [-1, 0], [0, -1], [0, 1], [1, -1], [-1, -1], [1, 1], [-1, 1]];

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

function paint(context, color, x, y, width, height) {
  context.fillStyle = color;
  context.fillRect(x, y, width, height);
}

function innTile(context, map, x, y) {
  const px = x * TILE;
  const py = y * TILE;
  const box = (color, left, top, width, height) => paint(context, color, px + left, py + top, width, height);
  const shade = (x * 17 + y * 13) % 7;
  const tile = map[y][x];
  if (tile === '#') {
    box('#352e26', 0, 0, 32, 32);
    box('#4e4639', 0, 0, 32, 8);
    box('#50473a', 0, 10, 32, 8);
    box('#453e32', 0, 20, 32, 8);
    for (let row = 0; row < 3; row++) {
      const seam = (row % 2) * 8;
      box('#352d25', seam, row * 10, 2, 8);
      box('#352d25', 16 + seam, row * 10, 2, 8);
    }
    box('#8c6541', 0, 28, 32, 2);
    box('#2c2019', 0, 30, 32, 2);
    if (y === 0 && (x === 2 || x === 9)) {
      box('#241b16', 10, 5, 12, 19);
      box('#a2764c', 11, 8, 10, 12);
      box('#d69c55', 13, 8, 6, 12);
      box('#ffe79d', 15, 9, 3, 8);
      box('#513824', 9, 20, 14, 3);
    }
    return;
  }
  box(['#705039', '#76523c', '#6a4a35'][shade % 3], 0, 0, 32, 32);
  for (let row = 0; row < 4; row++) {
    box('#493426', 0, row * 8 + 7, 32, 1);
    box('#8a6245', 0, row * 8, 32, 1);
    box('#493426', (shade * 3 + row * 13) % 31, row * 8 + 1, 1, 6);
  }
  if (tile === 'D') {
    box('#392c22', 0, 17, 32, 15);
    box('#87644c', 0, 19, 32, 4);
    box('#99825b', 0, 22, 32, 2);
    return;
  }
  if (tile !== 'B' && tile !== 'T') return;
  const left = map[y][x - 1] === tile ? 0 : 2;
  const right = map[y][x + 1] === tile ? 32 : 30;
  const top = tile === 'B' ? 2 : 5;
  box('#35241e', left, top + 4, right - left, 25);
  box('#94643f', left, top, right - left, 20);
  box('#b28153', left, top, right - left, 3);
  box('#70452e', left, top + 16, right - left, 3);
  box('#583626', left, top + 21, right - left, 4);
  if (shade % 2 === 0) {
    box('#dfc89a', 12, top + 6, 8, 6);
    box('#a97742', 14, top + 7, 4, 3);
  } else {
    box('#472f22', 20, top + 6, 7, 7);
    box('#dca46b', 21, top + 6, 4, 5);
  }
}

function tileArt(context, image, column, row, x, y, size) {
  context.drawImage(image, column * 16, row * 16, 16, 16, x, y, size, size);
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

  accept(actor, target, now, interval) {
    this.advance(now);
    if (actor.x !== this.actor.x || actor.y !== this.actor.y) {
      this.prediction = null;
      this.move(actor.x, actor.y, now, interval);
    }
    if (actor.actionSeq !== this.actor.actionSeq && actor.lastAction === 'attack') {
      this.attackUntil = now + 265;
      this.attackX = Math.sign((target?.x ?? actor.x) - actor.x);
      this.attackY = Math.sign((target?.y ?? actor.y) - actor.y);
      if (this.attackX) this.left = this.attackX < 0;
    }
    if (actor.hp !== this.actor.hp && Number.isFinite(actor.hp) && Number.isFinite(this.actor.hp)) {
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
  constructor(canvas) {
    this.canvas = canvas;
    this.context = canvas.getContext('2d');
    this.figures = new Map();
    this.images = Object.fromEntries(ASSETS.map((name) => [name, this.loadImage(name)]));
    this.set(null, false);
  }

  loadImage(name) {
    const image = new Image();
    if (name.startsWith('tiles_')) image.addEventListener('load', () => {
      if (this.textureName === name) this.terrain = null;
    });
    image.src = `/play/assets/${name}.png`;
    return image;
  }

  set(snapshot, dungeon, now = performance.now()) {
    const scene = dungeon ? snapshot?.id : snapshot?.selfId;
    const textureName = dungeon ? (snapshot?.theme === PRISON ? 'tiles_prison' : 'tiles_sewers') : null;
    if (scene !== this.scene || dungeon !== this.dungeon || textureName !== this.textureName) {
      this.figures.clear();
      this.lastEvent = 0;
      this.terrain = null;
      this.scene = scene;
    }
    this.textureName = textureName;
    if (textureName && !this.images[textureName]) this.images[textureName] = this.loadImage(textureName);
    this.dungeon = dungeon;
    this.snapshot = snapshot;
    const map = snapshot?.map ?? INN;
    const mapKey = map.join('|');
    if (mapKey !== this.mapKey) {
      this.map = map;
      this.mapKey = mapKey;
      this.terrain = null;
      this.canvas.width = map[0].length * TILE;
      this.canvas.height = map.length * TILE;
    }
    const actors = (dungeon ? snapshot?.actors : snapshot?.people) ?? [];
    const present = new Set(actors.map((actor) => actor.id));
    for (const id of this.figures.keys()) if (!present.has(id)) this.figures.delete(id);
    for (const actor of actors) {
      const figure = this.figures.get(actor.id);
      const interval = dungeon ? (actor.kind === 'enemy' ? 270 : 180) : 145;
      if (figure) figure.accept(actor, actors.find((other) => other.id === actor.targetId), now, interval);
      else this.figures.set(actor.id, new Figure(actor, now));
    }
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
  }

  predict(dx, dy, now) {
    this.figures.get(this.snapshot?.selfId)?.predict(dx, dy, now, this.dungeon ? 180 : 145, now + 550);
  }

  cancel(now) { this.figures.get(this.snapshot?.selfId)?.cancel(now); }

  getTerrain() {
    if (this.terrain) return this.terrain;
    const image = document.createElement('canvas');
    image.width = this.canvas.width;
    image.height = this.canvas.height;
    const context = image.getContext('2d');
    context.imageSmoothingEnabled = false;
    const tiles = this.images[this.textureName];
    const prison = this.textureName === 'tiles_prison';
    const palette = prison ? ['#5b4f55', '#40373d', '#362e33'] : ['#4e5144', '#333b31', '#2e352d'];
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
      });
    });
    this.terrain = image;
    return image;
  }

  draw(now, selected, destination) {
    const context = this.context;
    context.imageSmoothingEnabled = false;
    context.drawImage(this.getTerrain(), 0, 0);
    const snapshot = this.snapshot;
    const self = this.figures.get(snapshot?.selfId)?.actor;
    const target = this.figures.get(selected)?.actor;
    if (target?.hp > 0) this.marker(target.x, target.y, '#f4876b');
    const queued = snapshot?.queued;
    if (this.dungeon && self && queued?.type === 'move') this.marker(self.x + queued.dx, self.y + queued.dy, '#89d1b2');
    if (!this.dungeon && destination) this.marker(destination[0], destination[1], '#edb776');
    for (const drop of snapshot?.drops ?? []) {
      const image = this.images.items;
      if (drop.pickedBy != null) continue;
      if (image.naturalWidth) {
        const gold = drop.item === 'gold';
        tileArt(context, image, gold ? 2 : 5, gold ? 1 : 27, drop.x * TILE + 7, drop.y * TILE + 12, 19);
      } else {
        paint(context, '#ddad5c', drop.x * TILE + 12, drop.y * TILE + 18, 8, 6);
      }
    }
    const figures = [...this.figures.values()];
    for (const figure of figures) figure.advance(now);
    figures.sort((left, right) => left.y - right.y);
    for (const figure of figures) this.drawFigure(figure, now);
    for (const figure of figures) if (!this.dungeon && figure.actor.queued) this.drawSign(figure);
  }

  marker(x, y, color) {
    const context = this.context;
    context.save();
    context.globalAlpha = .72;
    context.strokeStyle = color;
    context.lineWidth = 2;
    context.strokeRect(x * TILE + 3, y * TILE + 3, TILE - 6, TILE - 6);
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
    const own = actor.id === this.snapshot?.selfId;
    const enemy = actor.kind === 'enemy';
    const first = actor.id === this.snapshot?.actors?.[0]?.id;
    const image = this.images[enemy ? 'rat' : own && !this.dungeon || first ? 'warrior' : 'rogue'];
    const context = this.context;
    const size = enemy ? 16 : 12;
    const x = Math.round((figure.x + .5) * TILE);
    const y = Math.round((figure.y + .87) * TILE);
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
    if (image.naturalWidth) {
      context.drawImage(image, size * frame, 0, size, 15, -size, -30, size * 2, 30);
    } else {
      paint(context, own ? '#d8ad65' : '#95bfb4', -6, -20, 12, 20);
    }
    context.restore();
    if (dead) return;
    if (this.dungeon) {
      paint(context, '#222620', x - 11, y + 1, 22, 3);
      paint(context, enemy ? '#dd957b' : own ? '#dfb96c' : '#9bd2c0', x - 11, y + 1, 22 * actor.hp / actor.maxHp, 3);
    }
    if (!enemy && !actor.queued) {
      if (figure.captionName !== actor.name) {
        figure.captionName = actor.name;
        figure.shortName = this.fitName(actor.name);
      }
      this.caption(figure.shortName, x, Math.max(9, y - 32), own ? '#ffe1a3' : '#d8e4cf');
    }
    if (figure.hurtUntil > now) {
      const change = figure.hpChange;
      this.caption(change > 0 ? `+${change}` : String(change), x,
        y - 37 - (1 - (figure.hurtUntil - now) / 590) * 14, change > 0 ? '#a4e4b4' : '#ffa68b');
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

  fitName(name) {
    const context = this.context;
    context.font = 'bold 8px "Malgun Gothic", sans-serif';
    const width = TILE - 5;
    if (context.measureText(name).width <= width) return name;
    const letters = [...name];
    let shorter;
    do {
      letters.pop();
      shorter = letters.join('') + '…';
    } while (letters.length && context.measureText(shorter).width > width);
    return shorter;
  }

  point(event) {
    const rect = this.canvas.getBoundingClientRect();
    const x = (event.clientX - rect.left) * this.canvas.width / rect.width;
    const y = (event.clientY - rect.top) * this.canvas.height / rect.height;
    return [x, y];
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
