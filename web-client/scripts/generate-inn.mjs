// SPDX-License-Identifier: GPL-3.0-or-later
// Paints the hearth hall for both clients: the lit background, its animated regions and the NPC sheet.
// Run from the repository root with `node web-client/scripts/generate-inn.mjs`, then commit the outputs.
import { crc32, deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

const OUT = new URL('../play/', import.meta.url);
const TILE = 16;

// The Worker serves this map and validates movement against it. Keep the two in sync.
const MAP = [
  '##########################',
  '#.....BBB......BBBBBBBBB.#',
  '#........................#',
  '#......................N.#',
  '#................N.......#',
  '#..............BBBBBBBBB.#',
  '#........................#',
  '#..TT....................#',
  '#.........TT.............#',
  '#.......N.......TT.......#',
  '#...........TT......######',
  '#..TT...............#....#',
  '#....N..............#....#',
  '#....................N...#',
  '#.....................DDD#',
  '#..........DDDD.......DDD#',
  '##########################',
];
const W = MAP[0].length * TILE, H = MAP.length * TILE;
const FRAMES = 4, FRAME_SECONDS = 0.34;

const cache = {};
function C(h) {
  return cache[h] ??= [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
}
function hash(x, y) {
  let n = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
}
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

function canvas(w, h) {
  const buf = new Uint8ClampedArray(w * h * 4), glow = new Uint8Array(w * h), stack = [];
  const g = { w, h, buf, glow, tx: 0, ty: 0, emissive: false };
  g.at = (x, y, paint) => { stack.push(g.tx, g.ty); g.tx += x; g.ty += y; paint(); g.ty = stack.pop(); g.tx = stack.pop(); };
  g.px = (x, y, c) => {
    x = (x | 0) + g.tx; y = (y | 0) + g.ty;
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    c = C(c);
    const i = (y * w + x) * 4;
    buf[i] = c[0]; buf[i + 1] = c[1]; buf[i + 2] = c[2]; buf[i + 3] = 255;
    glow[y * w + x] = g.emissive ? 1 : 0;
  };
  g.rect = (x, y, rw, rh, c) => { for (let j = 0; j < rh; j++) for (let i = 0; i < rw; i++) g.px(x + i, y + j, c); };
  g.mul = (x, y, fr, fg, fb) => {
    x = (x | 0) + g.tx; y = (y | 0) + g.ty;
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    const i = (y * w + x) * 4;
    if (!buf[i + 3]) return;
    buf[i] *= fr; buf[i + 1] *= fg; buf[i + 2] *= fb;
  };
  g.shade = (x, y, rw, rh, f) => { for (let j = 0; j < rh; j++) for (let i = 0; i < rw; i++) g.mul(x + i, y + j, f, f, f); };
  const inside = (dx, dy, rx, ry) => (dx * dx) / (rx * rx + 0.5) + (dy * dy) / (ry * ry + 0.5) <= 1;
  g.ell = (cx, cy, rx, ry, c) => { for (let dy = -ry; dy <= ry; dy++) for (let dx = -rx; dx <= rx; dx++) if (inside(dx, dy, rx, ry)) g.px(cx + dx, cy + dy, c); };
  g.ellShade = (cx, cy, rx, ry, f) => { for (let dy = -ry; dy <= ry; dy++) for (let dx = -rx; dx <= rx; dx++) if (inside(dx, dy, rx, ry)) g.mul(cx + dx, cy + dy, f, f, f); };
  g.lit = (paint) => { g.emissive = true; paint(); g.emissive = false; };
  return g;
}

// ---------- Terrain patterns (absolute pixel coordinates, so neighbouring tiles join) ----------
const PLANKS = ['#4a3526', '#443022', '#3d2b1f', '#4e3929'];
function woodAt(x, y) {
  x &= 63; y &= 63;
  const row = y >> 3, off = (hash(row, 7) * 32) | 0, plank = (((x + off) / 32) | 0) & 1;
  if ((y & 7) === 7 || (x + off) % 32 === 0) return '#20150e';
  if (hash(x >> 2, y) < 0.10) return '#382619';
  if (hash(x, y) < 0.03) return '#2a1c12';
  return PLANKS[(hash(plank, row) * 4) | 0];
}
const SLABS = ['#45444a', '#3e3d43', '#4a484f', '#3a3940'];
function stoneAt(x, y) {
  x &= 63; y &= 63;
  const row = y >> 4, off = (row & 1) * 8, lx = (x + off) & 15, ly = y & 15, slab = ((x + off) >> 4) & 3;
  if (ly === 15 || lx === 15) return '#1b1a1f';
  if (ly === 0 || lx === 0) return '#504e56';
  if (hash(slab, row + 31) < 0.35 && lx === ((ly * 0.6 + 3 + hash(ly, slab) * 1.5) | 0)) return '#222127';
  if (hash(x, y + 7) < 0.06) return '#333238';
  return SLABS[(hash(slab, row + 11) * 4) | 0];
}
function wallTopAt(x, y) {
  x &= 15; y &= 15;
  const off = ((y >> 3) & 1) * 8;
  if ((y & 7) === 0 || (x + off) % 16 === 0) return '#0f0d13';
  return hash(x, y) < 0.05 ? '#1e1b25' : '#17151c';
}
const BRICKS = ['#4b4852', '#44414b', '#3c3a44', '#504d56'];
function wallFaceAt(x, y) { // y is 0..15 within the tile
  if (y < 2) return y ? '#1c120a' : '#2e1e12';
  x %= 48;
  const row = ((y - 2) / 5) | 0, off = (row & 1) * 6, brick = (((x + off) / 12) | 0) % 4;
  if ((y - 2) % 5 === 4 || (x + off) % 12 === 11) return '#24222a';
  if (hash(x, y + 99) < 0.05) return '#35333c';
  if (hash(brick, row + 77) < 0.14 && hash(x, y) < 0.6) return '#35442f';
  return BRICKS[(hash(brick, row + 50) * 4) | 0];
}

// ---------- Props (local coordinates) ----------
function rug(g, w, h) {
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
    if ((i < 2 || i >= w - 2) && hash(i, j + 40) < 0.5) continue;
    let c = '#5a2022';
    if (i < 4 || i >= w - 4 || j < 2 || j >= h - 2) c = '#34141a';
    else if (i === 5 || i === w - 6 || j === 3 || j === h - 4) c = '#8a5a3a';
    else if ((Math.abs(i - w / 2) / 2 + Math.abs(j - h / 2)) % 8 < 1.5) c = '#7a3626';
    if (hash(i + 300, j) < 0.08) c = '#2c1014';
    g.px(i, j, c);
  }
}
function fireplace(g, t) { // 48x29
  g.rect(2, 0, 44, 29, '#625f69');
  for (let j = 0; j < 29; j += 6) g.rect(2, j, 44, 1, '#3a3842');
  for (let j = 0; j < 5; j++) for (let i = 0; i < 4; i++) g.rect(2 + i * 12 + (j & 1) * 6, 2 + j * 6, 1, 2, '#3a3842');
  g.rect(0, 0, 48, 3, '#75727c'); g.rect(0, 3, 48, 1, '#2c2a33');
  g.rect(10, 8, 28, 21, '#0b0708'); g.rect(11, 7, 26, 1, '#0b0708');
  g.rect(13, 26, 22, 2, '#33200f'); g.rect(16, 25, 8, 1, '#4a2e16'); g.rect(25, 25, 7, 1, '#3d2612');
  g.lit(() => {
    for (let fx = 0; fx < 22; fx++) {
      const bell = 1 - ((fx - 10.5) / 11) ** 2;
      const height = (3 + 11 * bell * (0.72 + 0.28 * Math.sin(t * 9 + fx * 1.3)) + hash(fx, (t * 10) | 0) * 3) | 0;
      for (let k = 0; k < height; k++) {
        const r = k / height + (bell < 0.45 ? 0.3 : 0);
        g.px(13 + fx, 25 - k, r < 0.3 ? '#fbe07a' : r < 0.62 ? '#e8872a' : '#a83a16');
      }
    }
    for (let i = 0; i < 4; i++) g.px(16 + hash(i, (t * 6) | 0) * 16, 11 - hash(i, 4 + ((t * 6) | 0)) * 3, '#e8872a');
  });
}
function torch(g, t) { // 5x11
  g.rect(1, 4, 3, 2, '#5c5c64'); g.rect(2, 6, 1, 5, '#4a3020');
  g.lit(() => {
    g.px(2, 3, '#e0802a'); g.px(2, 2, '#f7d060'); g.px(2 + (Math.sin(t * 9) > 0 ? 1 : 0), 1, '#e0802a');
    g.px(1, 3, '#a83a16'); g.px(3, 3, '#a83a16');
  });
}
function candle(g, t, seed) { // 3x6
  g.rect(0, 2, 2, 3, '#cfc8ae'); g.rect(0, 5, 2, 1, '#6a6048');
  g.lit(() => { g.px(Math.sin(t * 11 + seed) > 0.6 ? 1 : 0, 0, '#e8872a'); g.px(0, 1, '#fbe07a'); });
}
const BOTTLES = ['#35543f', '#5a3030', '#3c3c5c', '#75632c', '#4a4038'];
function shelf(g, w) { // two tiers, 27 tall
  g.rect(0, 0, w, 27, '#1e130c'); g.rect(0, 0, w, 1, '#4a301a');
  g.rect(0, 12, w, 2, '#4a301a'); g.rect(0, 25, w, 2, '#4a301a');
  for (let x = 12; x < w; x += 24) g.rect(x - 1, 1, 1, 24, '#33200f');
  for (let tier = 0; tier < 2; tier++) for (let i = 0; 3 + i * 5 + 3 < w; i++) {
    const pick = hash(i, tier + 20);
    if (pick < 0.15) continue;
    const bx = 3 + i * 5, height = 4 + ((hash(i, tier + 30) * 4) | 0), by = (tier ? 25 : 12) - height, c = BOTTLES[(pick * 5) | 0];
    g.rect(bx, by, 3, height, c); g.px(bx + 1, by - 1, c); g.px(bx + 1, by - 2, '#8a7a5a'); g.px(bx, by + 1, '#9aa39a');
  }
}
function counter(g, w) { // one tile tall
  g.rect(0, 0, w, 7, '#614226'); g.rect(0, 0, w, 1, '#7a5832');
  for (let x = 24; x < w; x += 24) g.rect(x, 1, 1, 6, '#4a301a');
  g.rect(0, 7, w, 9, '#3d2716'); g.rect(0, 7, w, 1, '#22140a');
  for (let x = 5; x < w; x += 6) g.rect(x, 8, 1, 8, '#28180c');
  g.rect(0, 12, w, 1, '#4a301a'); g.rect(0, 0, 2, 16, '#2a1a0e'); g.rect(w - 2, 0, 2, 16, '#2a1a0e');
}
function table(g, w) { // one tile tall
  g.ellShade(w / 2, 15, w / 2, 2, 0.6);
  g.rect(1, 2, w - 2, 9, '#5e3f24'); g.rect(1, 2, w - 2, 1, '#78552f'); g.rect(1, 6, w - 2, 1, '#4c321c');
  g.rect(1, 11, w - 2, 3, '#352210'); g.rect(1, 11, w - 2, 1, '#22140a');
  g.rect(2, 14, 2, 2, '#2a1a0e'); g.rect(w - 4, 14, 2, 2, '#2a1a0e');
}
function stairs(g, w, t) { // 32 tall, leads down to the expedition
  g.rect(0, 0, w, 32, '#54525c');
  for (let x = 0; x < w; x += 8) g.rect(x, 0, 1, 32, '#2c2a33');
  g.rect(0, 0, w, 1, '#6a6872');
  g.rect(4, 4, w - 8, 26, '#0a090c');
  ['#4a4852', '#3d3b45', '#302e38', '#24222b', '#19171f', '#100e14'].forEach((c, i) => {
    g.rect(4, 4 + i * 4, w - 8, 3, c); g.rect(4, 7 + i * 4, w - 8, 1, '#08070a');
  });
  g.lit(() => {
    for (let i = 0; i < w - 8; i++) {
      if (hash(i, 3 + ((t * 3) | 0)) < 0.45) g.px(4 + i, 28 + ((hash(i, 9) * 2) | 0), hash(i, 5) < 0.5 ? '#1f5a50' : '#143a36');
    }
  });
}
function window(g) { // 14x13
  g.rect(0, 0, 14, 13, '#2a1c12'); g.rect(2, 2, 10, 9, '#131c30');
  g.ell(9, 5, 2, 2, '#9db0c4'); g.px(8, 4, '#c8d4de');
  for (const [x, y] of [[3, 3], [5, 7], [10, 8], [4, 9]]) g.rect(x, y, 1, 2, '#3a4a66');
  g.rect(6, 2, 1, 9, '#2a1c12'); g.rect(2, 6, 10, 1, '#2a1c12');
}
function noticeBoard(g) { // 34x14
  g.rect(0, 0, 34, 14, '#2e1e12'); g.rect(2, 2, 30, 10, '#55391f');
  for (let x = 12; x < 32; x += 10) g.rect(x, 2, 1, 10, '#3f2a16');
  for (const [x, y, w, h, c] of [[4, 3, 6, 8, '#c2b28c'], [13, 3, 7, 5, '#a89a78'], [23, 3, 7, 8, '#b0a07c'], [13, 9, 6, 3, '#b9a984']]) {
    g.rect(x, y, w, h, c);
    for (let j = 2; j < h - 1; j += 2) g.rect(x + 1, y + j, w - 2 - ((hash(x, j) * 2) | 0), 1, '#6b5d46');
    g.px(x + (w >> 1), y, '#3a3a40');
  }
  g.rect(25, 8, 2, 2, '#9a2222');
}
function cobweb(g, flip) { // 11x11
  const m = (x) => flip ? 10 - x : x;
  for (const k of [4, 7, 10]) for (let i = 0; i <= k; i++) if ((i + k) % 2 === 0) g.px(m(i), k - i, '#77747f');
  for (let i = 0; i < 9; i++) g.px(m(i), i, '#63606b');
}
const small = {
  mug: (g) => { g.rect(0, 0, 3, 4, '#74644a'); g.rect(0, 0, 3, 1, '#cfc8ae'); g.rect(3, 1, 1, 2, '#74644a'); },
  bottle: (g) => { g.rect(0, 3, 3, 7, '#35543f'); g.rect(1, 0, 1, 3, '#35543f'); g.px(0, 4, '#9aa39a'); },
  coins: (g) => { g.px(0, 1, '#c9a83e'); g.px(2, 2, '#c9a83e'); g.px(3, 0, '#c9a83e'); g.px(1, 0, '#8a7228'); },
  ledger: (g) => { g.rect(0, 0, 9, 5, '#9a8a66'); g.rect(4, 0, 1, 5, '#5a4c34'); g.rect(1, 2, 2, 1, '#5a4c34'); g.rect(6, 2, 2, 1, '#5a4c34'); },
  letter: (g) => { g.rect(0, 0, 5, 3, '#9a8a66'); g.px(2, 1, '#9a2222'); },
  plate: (g) => { g.ell(4, 2, 4, 2, '#8a8068'); g.rect(2, 1, 4, 1, '#c9c2a8'); g.px(6, 1, '#c9c2a8'); g.px(1, 1, '#c9c2a8'); },
  bread: (g) => { g.rect(0, 0, 6, 3, '#75522f'); g.px(1, 1, '#4c321c'); },
  keg: (g) => {
    g.rect(0, 2, 10, 9, '#553620'); g.rect(0, 4, 10, 1, '#5c5c64'); g.rect(0, 8, 10, 1, '#5c5c64');
    g.ell(5, 2, 5, 2, '#6a4628'); g.rect(0, 3, 1, 8, '#2e1c10'); g.rect(9, 3, 1, 8, '#2e1c10'); g.px(5, 6, '#b08a4a');
  },
};

// ---------- Scene ----------
const tileAt = (x, y) => MAP[y]?.[x] ?? '#';
const isFace = (x, y) => tileAt(x, y) === '#' && y + 1 < MAP.length && tileAt(x, y + 1) !== '#';
const inAlcove = (x, y) => x >= 21 && x <= 24 && y >= 11 && y <= 15;

function paintHall(t, animated) {
  const g = canvas(W, H), lights = [];
  const light = (x, y, r, i, c) => lights.push({ x, y, r, i, c });
  const WARM = [1, 0.6, 0.28], CANDLE = [1, 0.66, 0.34];
  const fx = (x, y, w, h) => animated.push([x, y, w, h]);

  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const cx = x >> 4, cy = y >> 4;
    if (tileAt(cx, cy) === '#') g.px(x, y, isFace(cx, cy) ? wallFaceAt(x, y & 15) : wallTopAt(x, y));
    else g.px(x, y, inAlcove(cx, cy) ? stoneAt(x, y) : woodAt(x, y));
  }
  for (let cy = 0; cy < MAP.length; cy++) for (let cx = 0; cx < MAP[0].length; cx++) {
    if (!isFace(cx, cy)) continue;
    g.shade(cx * 16, cy * 16 + 13, 16, 3, 0.75);
    g.shade(cx * 16, cy * 16 + 16, 16, 3, 0.55); g.shade(cx * 16, cy * 16 + 19, 16, 2, 0.8);
  }
  g.shade(16, 16, 2, 240, 0.7); g.shade(398, 16, 2, 144, 0.7);

  // Stains and wear
  const stain = (cx, cy, rx, ry, fr, fg, fb) => {
    for (let dy = -ry; dy <= ry; dy++) for (let dx = -rx; dx <= rx; dx++) {
      const d = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry);
      if (d <= 1 && hash(cx + dx, cy + dy) > d * 0.8) g.mul(cx + dx, cy + dy, fr, fg, fb);
    }
  };
  stain(150, 214, 11, 6, 0.75, 0.47, 0.44); stain(166, 224, 4, 2, 0.75, 0.47, 0.44);
  stain(262, 124, 9, 5, 0.72, 0.72, 0.68); stain(96, 168, 7, 4, 0.74, 0.74, 0.7); stain(40, 30, 7, 4, 0.78, 0.82, 0.95);
  stain(300, 200, 8, 4, 0.74, 0.74, 0.7);

  // Hearth side
  g.at(84, 38, () => rug(g, 72, 50));
  g.at(96, 3, () => fireplace(g, t)); fx(106, 10, 28, 20); light(120, 34, 132, 1.05, WARM);
  g.at(19, 2, () => cobweb(g, false)); g.at(386, 2, () => cobweb(g, true));
  g.at(33, 2, () => window(g)); light(40, 24, 24, 0.3, [0.4, 0.5, 0.85]);
  for (const x of [69, 165, 227]) { g.at(x, 3, () => torch(g, t)); fx(x, 3, 5, 5); light(x + 2, 9, 46, 0.6, WARM); }
  g.at(178, 1, () => noticeBoard(g));

  // Bar
  g.at(240, 4, () => shelf(g, 144));
  g.at(240, 80, () => counter(g, 144)); g.shade(240, 96, 144, 2, 0.6);
  g.at(246, 76, () => small.mug(g)); g.at(258, 72, () => small.bottle(g));
  g.at(300, 75, () => candle(g, t, 3)); fx(300, 75, 3, 2); light(301, 78, 58, 0.75, CANDLE);
  g.at(318, 82, () => small.coins(g)); g.at(332, 81, () => small.ledger(g));
  g.at(350, 71, () => small.keg(g)); g.at(362, 71, () => small.keg(g)); g.at(376, 77, () => small.mug(g));

  // Tables
  const dressings = [
    (x, y) => { g.at(x + 5, y, () => small.mug(g)); g.at(x + 20, y + 5, () => small.coins(g)); },
    (x, y) => { g.at(x + 4, y + 3, () => small.plate(g)); g.at(x + 24, y + 1, () => small.mug(g)); },
    (x, y) => { g.at(x + 6, y + 5, () => small.bread(g)); g.at(x + 23, y, () => small.mug(g)); },
    (x, y) => { g.at(x + 5, y + 1, () => small.mug(g)); g.at(x + 21, y + 4, () => small.letter(g)); },
    (x, y) => { g.at(x + 4, y + 4, () => small.letter(g)); g.at(x + 24, y, () => small.mug(g)); },
  ];
  let tables = 0;
  MAP.forEach((row, cy) => {
    for (let cx = 0; cx < row.length; cx++) {
      if (row[cx] !== 'T') continue;
      let width = 1;
      while (row[cx + width] === 'T') width++;
      const x = cx * 16, y = cy * 16;
      g.at(x, y, () => table(g, width * 16));
      dressings[tables % dressings.length](x, y);
      g.at(x + 14, y - 1, () => candle(g, t, 5 + tables * 2)); fx(x + 14, y - 1, 3, 2);
      light(x + 15, y + 3, 44, 0.6, CANDLE);
      tables++;
      cx += width - 1;
    }
  });

  // Entrance and the way down
  g.rect(178, 242, 60, 11, '#3a2a22'); g.rect(180, 244, 56, 7, '#4c382c');
  for (let x = 182; x < 236; x += 4) g.rect(x, 246, 2, 3, '#3a2a22');
  g.rect(176, 256, 64, 16, '#0b090c'); g.rect(176, 256, 3, 16, '#5a5862'); g.rect(237, 256, 3, 16, '#5a5862');
  g.rect(179, 256, 58, 2, '#3a3840'); light(208, 252, 36, 0.3, [0.4, 0.5, 0.85]);
  g.at(352, 224, () => stairs(g, 48, t)); fx(356, 252, 40, 2); light(376, 240, 54, 0.6, [0.2, 0.85, 0.7]);
  g.at(357, 162, () => torch(g, t)); fx(357, 162, 5, 5); light(359, 168, 42, 0.5, WARM);
  g.shade(336, 176, 2, 80, 0.7);

  // Baked lighting. Flames and glints stay at full brightness.
  const ambient = [0.64, 0.64, 0.74];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const p = y * W + x, i = p * 4;
    if (g.glow[p]) continue;
    let r = ambient[0], gr = ambient[1], b = ambient[2];
    const threshold = BAYER[(y & 3) * 4 + (x & 3)] / 16;
    for (const lamp of lights) {
      const dx = x - lamp.x, dy = (y - lamp.y) * 1.2, d = Math.sqrt(dx * dx + dy * dy);
      if (d >= lamp.r) continue;
      let f = (1 - d / lamp.r) ** 2 * lamp.i;
      f = Math.floor(f * 8 + threshold) / 8;
      r += f * lamp.c[0]; gr += f * lamp.c[1]; b += f * lamp.c[2];
    }
    const vx = (x - W / 2) / (W / 2), vy = (y - H / 2) / (H / 2), v = 1 - 0.24 * (vx * vx + vy * vy);
    g.buf[i] *= r * v; g.buf[i + 1] *= gr * v; g.buf[i + 2] *= b * v;
  }
  return g;
}

// ---------- NPC sheet: one 16px column per role, idle and breathing rows ----------
const SKIN = { s: '#b89070', S: '#7d5d48', e: '#140c10', f: '#1c1612' };
const NPC_SPRITES = [ // guard, innkeeper, vendor, bard, adventurer
  [{ m: '#6c7078', M: '#474a52', k: '#0c0a0c', c: '#6a1e1e', R: '#7a5236' }, [
    '...mmmmmm...', '..mmmmmmmm..', '..mMMMMMMm..', '..mkkkkkkm..', '..mmmMmmmm..', '...mmMmmm...',
    '..MmmmmmmM..', '.MMmccccmMM.', '.mmmccccmmm.', '.mRmccccmmm.', '.MM.cccc.MM.', '...mccccm...',
    '...mm..mm...', '...mR..mm...', '...MM..MM...', '..MMM..MMM..']],
  [{ d: '#4a3020', w: '#857a62', a: '#5e5646', p: '#2e2a26' }, [
    '....ssss....', '...ssssss...', '...ssssss...', '...sesses...', '...SddddS...', '....dddd....',
    '..wwaddaww..', '.wwwaaaawww.', '.wwwaaaawww.', '.sswaaaawss.', '...waaaaw...', '...aaaaaa...',
    '...ppaapp...', '...pp..pp...', '...pp..pp...', '...ff..ff...']],
  [{ b: '#3d2e1c', o: '#63492a', O: '#44321c', q: '#75643f', y: '#c9a83e', p: '#2e2a26' }, [
    '...bbbbbb...', '.bbbbbbbbbb.', '...ssssss...', '...sesses...', '...ssssss...', 'qq..ssss....',
    'qqooooooo...', 'qqoooyoooo..', 'qqoOooooOo..', 'qqsOooooOs..', '.q.oooooo...', '...oooooo...',
    '...oOooOo...', '...pp..pp...', '...pp..pp...', '...ff..ff...']],
  [{ t: '#3f5238', F: '#a02c2c', h: '#5a3a1e', g: '#51633f', G: '#394a2e', l: '#9a6630', L: '#5e3c18', p: '#3a2a22' }, [
    '.....tttF...', '...ttttttF..', '..tttttttt..', '...hsssss...', '...hsesse...', '....sss.....',
    '..gggggggg..', '.gggGggGggg.', '.gslllLggsg.', '.gglllllLLL.', '..gllllgg...', '..gggggg....',
    '..pppppp....', '...pp..pp...', '...pp..pp...', '...ff..ff...']],
  [{ h: '#70401c', a: '#75522f', A: '#4f3722', B: '#2a1c12', p: '#3c3c44', x: '#9a9aa2' }, [
    '....hhhh....', '...hhhhhh...', '...hsssss...', '...hsesse...', '...ssssss...', '....ssss..x.',
    '..aaaaaaaax.', '.aaaAaaAaax.', '.aaaAaaAaax.', '.saaaaaaasx.', '..BBBBBBBB..', '...aaaaaa...',
    '...pp..pp...', '...pp..pp...', '...pp..pp...', '...ff..ff...']],
];
function paintNpcs() {
  const g = canvas(NPC_SPRITES.length * 16, 32);
  NPC_SPRITES.forEach(([palette, rows], column) => {
    const colors = { ...SKIN, ...palette };
    const breathing = ['............', ...rows.slice(0, 11), ...rows.slice(12)];
    [rows, breathing].forEach((frame, row) => frame.forEach((line, y) => {
      if (line.length !== 12) throw new Error(`NPC ${column} row ${y} must be 12 pixels wide.`);
      [...line].forEach((ch, x) => { if (ch !== '.') g.px(column * 16 + 2 + x, row * 16 + y, colors[ch]); });
    }));
  });
  return g;
}

// ---------- Output ----------
function png(g) {
  const raw = Buffer.alloc((g.w * 4 + 1) * g.h);
  for (let y = 0; y < g.h; y++) Buffer.from(g.buf.buffer, y * g.w * 4, g.w * 4).copy(raw, y * (g.w * 4 + 1) + 1);
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type), data]), frame = Buffer.alloc(body.length + 8);
    frame.writeUInt32BE(data.length, 0); body.copy(frame, 4); frame.writeUInt32BE(crc32(body) >>> 0, body.length + 4);
    return frame;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(g.w, 0); header.writeUInt32BE(g.h, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

const regions = [];
const frames = Array.from({ length: FRAMES }, (_, frame) => paintHall(frame * FRAME_SECONDS, frame ? [] : regions));
const sheetWidth = Math.max(...regions.map(([, , w]) => w)) * FRAMES;
const sheet = canvas(sheetWidth, regions.reduce((sum, [, , , h]) => sum + h, 0));
let sheetY = 0;
const effects = regions.map(([x, y, w, h]) => {
  frames.forEach((frame, index) => {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      const from = ((y + j) * W + x + i) * 4, to = ((sheetY + j) * sheetWidth + index * w + i) * 4;
      sheet.buf.set(frame.buf.subarray(from, from + 4), to);
    }
  });
  const effect = [x, y, w, h, sheetY];
  sheetY += h;
  return effect;
});

writeFileSync(new URL('assets/inn-hall.png', OUT), png(frames[0]));
writeFileSync(new URL('assets/inn-hall-fx.png', OUT), png(sheet));
writeFileSync(new URL('assets/inn-npcs.png', OUT), png(paintNpcs()));
writeFileSync(new URL('inn-hall.mjs', OUT), `// SPDX-License-Identifier: GPL-3.0-or-later
// Generated by web-client/scripts/generate-inn.mjs. Edit the script, not this file.

// The hall painting fits exactly this map.
export const HALL_MAP = Object.freeze(${JSON.stringify(MAP)});
// Animated regions in 16px-tile pixels: [x, y, width, height, sheetY]; the sheet holds ${FRAMES} frames side by side.
export const HALL_FX = Object.freeze(${JSON.stringify(effects)});
export const HALL_FX_FRAMES = ${FRAMES};
`);
console.log(`hall ${W}x${H}, fx sheet ${sheet.w}x${sheet.h}`);
console.log('Java HALL_FX:', effects.map((e) => `{${e.join(', ')}}`).join(', '));
