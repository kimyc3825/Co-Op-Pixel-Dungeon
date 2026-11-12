// SPDX-License-Identifier: GPL-3.0-or-later

const TILE = 32;
const CANDLES = [[3, 3], [7, 5], [11, 9], [15, 11], [19, 9]];
const PATRONS = [[4, 3, '#794737'], [8, 5, '#55704c'], [20, 9, '#5a557c'], [14, 11, '#6b6345']];
const RUGS = [[3, 3, 6, 3], [10, 9, 7, 3], [10, 13, 6, 2]];

function rect(context, color, x, y, width, height) {
  context.fillStyle = color;
  context.fillRect(x, y, width, height);
}

export function innRug(context, x, y) {
  const rug = RUGS.find(([left, top, width, height]) => x >= left && x < left + width && y >= top && y < top + height);
  if (!rug) return;
  const [left, top, width, height] = rug;
  const px = x * TILE;
  const py = y * TILE;
  rect(context, (x + y) % 2 ? '#5c3130' : '#643535', px, py, TILE, TILE);
  if (y === top || y === top + height - 1) {
    const edge = py + (y === top ? 2 : TILE - 4);
    rect(context, '#8a583f', px, edge, TILE, 2);
    for (let stitch = 3; stitch < TILE; stitch += 8) rect(context, '#ae7450', px + stitch, edge, 3, 2);
  }
  if (x === left || x === left + width - 1) {
    rect(context, '#8a583f', px + (x === left ? 2 : TILE - 4), py, 2, TILE);
  }
  rect(context, '#784242', px + 14, py + 12, 4, 8);
  rect(context, '#784242', px + 12, py + 14, 8, 4);
}

function glow(context, x, y, radius, color) {
  const gradient = context.createRadialGradient(x, y, 0, x, y, radius);
  gradient.addColorStop(0, color);
  gradient.addColorStop(1, 'rgba(248,134,54,0)');
  rect(context, gradient, x - radius, y - radius, radius * 2, radius * 2);
}

function hasHearth(map) {
  return map[0]?.slice(18, 21) === '###' && map[1]?.slice(18, 21) === '...';
}

export function decorateInn(context, map) {
  for (const [x, y] of CANDLES) {
    if (map[y]?.[x] !== 'T') continue;
    glow(context, x * TILE + 9, y * TILE + 9, 31, 'rgba(255,177,75,.30)');
    rect(context, '#492f22', x * TILE + 5, y * TILE + 13, 8, 3);
    rect(context, '#edcf91', x * TILE + 8, y * TILE + 8, 3, 5);
  }
  if (map[16]?.[16] === '#') {
    const x = 16.5 * TILE;
    const y = 16 * TILE;
    glow(context, x, y + 6, 43, 'rgba(255,157,61,.39)');
    rect(context, '#302120', x - 5, y + 7, 10, 5);
    rect(context, '#ad7549', x - 3, y + 6, 6, 3);
  }
  if (!hasHearth(map)) return;
  const x = 18 * TILE + 8;
  const block = (color, left, y, w, h) => rect(context, color, x + left, y, w, h);
  glow(context, x + 39, 25, 114, 'rgba(255,146,57,.48)');
  block('#291d1a', 0, 0, 80, 33);
  block('#5f4d44', 4, 0, 72, 31);
  block('#8d6752', 0, 2, 80, 4);
  block('#745a4e', 6, 8, 68, 21);
  block('#3d2923', 14, 10, 52, 20);
  block('#251916', 20, 14, 40, 16);
  for (const left of [8, 18, 52, 64]) block('#a17b61', left, 7, 7, 2);
  for (const left of [7, 62]) block('#594339', left, 17, 11, 3);
  block('#806352', 0, 29, 80, 3);
  block('#574134', 4, 32, 72, 3);
  block('#63351e', 24, 26, 31, 3);
  block('#af6031', 26, 25, 20, 2);
}

function flame(context, x, bottom, time, scale = 1) {
  const flicker = Math.floor(time / 110) % 5;
  const tilt = [0, 1, -1, 1, 0][flicker];
  rect(context, '#b54826', x - 3 * scale, bottom - 5 * scale, 7 * scale, 5 * scale);
  rect(context, '#ff9f36', x - 2 * scale, bottom - 6 * scale, 5 * scale, 6 * scale);
  rect(context, '#ffb94a', x + tilt * scale, bottom - (7 + (flicker & 1)) * scale, 2 * scale, 4 * scale);
  rect(context, '#ffe49c', x - scale, bottom - 4 * scale, 3 * scale, 4 * scale);
}

export function animateInn(context, map, now) {
  if (map[16]?.[16] === '#') flame(context, 16.5 * TILE, 16 * TILE + 6, now + 310);
  if (hasHearth(map)) {
    const center = 19.5 * TILE;
    for (let i = -1; i <= 1; i++) flame(context, center + i * 12, 27, now + 180 * i, i === 0 ? 2 : 1.5);
    for (let i = 0; i < 5; i++) {
      const age = (now + i * 323) % 1400 / 1400;
      const x = center + (i - 2) * 7 + Math.round(Math.sin(age * 6 + i) * 4);
      rect(context, `rgba(255,163,71,${1 - age})`, x, 22 - Math.round(age * 20), i % 2 + 1, 2);
    }
  }
  for (const [x, y] of CANDLES) {
    if (map[y]?.[x] === 'T') flame(context, x * TILE + 9, y * TILE + 8, now + x * 91, .5);
  }
  for (const [x, y, coat] of PATRONS) {
    if (map[y]?.[x] !== 'T') continue;
    const px = x * TILE;
    const py = y * TILE;
    const toast = Math.floor((now + x * 197) / 300) % 13 === 7;
    rect(context, '#382624', px + 10, py + 5, 13, 10);
    rect(context, coat, px + 11, py + 8, 11, 7);
    rect(context, '#c98b5e', px + 13, py + 2, 7, 8);
    rect(context, '#3b2a28', px + 12, py + 1, 9, 3);
    rect(context, '#deae77', px + 19, py + 7, 2, 2);
    rect(context, coat, px + 21, py + (toast ? 7 : 11), 4, 3);
    rect(context, '#a36d40', px + 24, py + (toast ? 3 : 10), 4, 5);
    rect(context, '#edcf92', px + 24, py + (toast ? 2 : 9), 4, 1);
  }
}

export function bardNotes(context, x, y, now) {
  for (let i = 0; i < 2; i++) {
    const age = (now + i * 800) % 1600 / 1600;
    const px = x + 24 + Math.round(age * 13) + i * 3;
    const py = y - 20 - Math.round(age * 12);
    const color = `rgba(241,187,110,${Math.sin(age * Math.PI) * .85})`;
    rect(context, color, px, py, 4, 3);
    rect(context, color, px + 3, py - 6, 1, 6);
    rect(context, color, px + 4, py - 6, 3, 2);
  }
}
