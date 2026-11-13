// SPDX-License-Identifier: GPL-3.0-or-later

const TILE = 32;
const HEARTH = [7.5 * TILE, 31];
const GATE = [23.5 * TILE, 11 * TILE];
const RUGS = [[5, 2, 5, 4]];
const TABLE_CANDLES = [[3, 7], [3, 11], [10, 8], [13, 10], [17, 9]];
const SCONCES = [[3, 0, 14], [12, 0, 13], [24, 5, 15], [20, 10, 11], [1, 13, 12]];
const PLANKS = ['#33251e', '#402b20', '#493023', '#38271f', '#4a3326', '#37261c'];
const STONES = ['#222228', '#29282b', '#33302f', '#393435', '#29282c'];
const BOTTLES = ['#5c6b43', '#744f3a', '#576665', '#744449', '#82714f'];

function rect(context, color, x, y, width, height) {
  context.fillStyle = color;
  context.fillRect(x, y, width, height);
}

function stamp(context, x, y) {
  return (color, dx, dy, width, height) => rect(context, color, x + dx, y + dy, width, height);
}

function variant(x, y, n) {
  return ((x * 37 + y * 13 + x * y * 7) >>> 0) % n;
}

function wood(context, x, y) {
  const draw = stamp(context, x * TILE, y * TILE);
  for (let row = 0; row < 4; row++) {
    const py = row * 8;
    const joint = variant(x, y * 4 + row, 15) * 2;
    draw(PLANKS[variant(x, y * 4 + row, PLANKS.length)], 0, py, TILE, 8);
    draw('#523726', 0, py, TILE, 1);
    draw('#221a18', 0, py + 7, TILE, 1);
    draw('#2b1e19', joint, py + 1, 1, 6);
    draw('#523525', (joint + 1) % TILE, py + 1, 1, 6);
    if (variant(x, y * 4 + row, 7) < 3) draw('#5c3e2e', (joint + 9) % 23, py + 3, 8, 1);
    if ((x + y + row) % 5 === 0) draw('#211915', (joint + 16) % 24, py + 5, 5, 1);
  }
}

function masonry(context, x, y, cool = false) {
  const draw = stamp(context, x * TILE, y * TILE);
  draw(cool ? '#131d20' : '#19191e', 0, 0, TILE, TILE);
  for (let row = 0; row < 4; row++) {
    const py = row * 8;
    const offset = (row + y) % 2 ? 8 : 0;
    for (let col = -1; col <= 1; col++) {
      const left = Math.max(0, col * 16 + offset);
      const right = Math.min(TILE, col * 16 + offset + 15);
      if (right <= left) continue;
      const tone = variant(x * 2 + col + 3, y * 4 + row, STONES.length);
      const color = cool ? ['#263538', '#28383c', '#314244', '#273336', '#1e2d32'][tone] : STONES[tone];
      draw(color, left, py, right - left, 7);
      draw(cool ? '#39474a' : '#454047', left, py, right - left, 1);
      if (right - left > 5) draw(cool ? '#1c2b2f' : '#211e24', left + 2, py + 5, 3, 1);
    }
  }
}

function rug(context, x, y) {
  const rug = RUGS.find(([left, top, width, height]) => x >= left && x < left + width && y >= top && y < top + height);
  if (!rug) return;
  const [left, top, width, height] = rug;
  const draw = stamp(context, x * TILE, y * TILE);
  const edges = [x === left, y === top, x === left + width - 1, y === top + height - 1];
  draw('#411e24', 0, 0, TILE, TILE);
  for (let py = 2; py < TILE; py += 4) {
    draw((x + y + py) % 3 ? '#56262b' : '#612c2c', 0, py, TILE, 1);
  }
  if (edges[0]) { draw('#8a5540', 1, 0, 3, TILE); draw('#b17a4a', 5, 0, 1, TILE); }
  if (edges[2]) { draw('#8a5540', 28, 0, 3, TILE); draw('#b17a4a', 26, 0, 1, TILE); }
  if (edges[1]) { draw('#8a5540', 0, 1, TILE, 3); draw('#b17a4a', 0, 5, TILE, 1); }
  if (edges[3]) { draw('#8a5540', 0, 28, TILE, 3); draw('#b17a4a', 0, 26, TILE, 1); }
  if (!edges.some(Boolean)) {
    for (let i = -6; i <= 6; i++) draw('#985237', 10 + Math.abs(i), 15 + i, 12 - 2 * Math.abs(i), 1);
    draw('#bf7d4b', 15, 13, 2, 5);
  }
}

function furniture(context, map, x, y, tile) {
  const draw = stamp(context, x * TILE, y * TILE);
  const joinLeft = map[y][x - 1] === tile;
  const joinRight = map[y][x + 1] === tile;
  const left = joinLeft ? 0 : 3;
  const width = (joinRight ? 32 : 29) - left;
  const top = tile === 'B' ? 3 : 6;
  const face = tile === 'B' ? 10 : 6;
  draw('#221917', left, top + 7, width, 22);
  draw('#62412d', left, top, width, 18);
  draw('#956342', left, top, width, 2);
  draw('#4a3127', left, top + 7, width, 1);
  draw('#503225', left, top + 18, width, face);
  draw('#744b32', left, top + 18, width, 2);
  if (!joinLeft) draw('#8c5f3d', left + 1, top + 2, 1, 15);
  if (!joinRight) draw('#2d211c', left + width - 2, top + 2, 2, 18);
  if (tile === 'T') {
    draw('#2c201c', left + 1, top + 24, 3, 5);
    draw('#2c201c', left + width - 4, top + 24, 3, 5);
    if (x % 2 === 0) {
      draw('#372d26', 12, top + 6, 8, 5);
      draw('#8c7658', 13, top + 6, 6, 3);
    }
  }
}

function thresholdStone(context, x, y) {
  // The timber gives way to fitted stones irregularly along the passage.
  const draw = stamp(context, x * TILE, y * TILE);
  for (let row = 0; row < 4; row++) {
    const left = 9 + variant(x, y * 4 + row, 15);
    const py = row * 8;
    draw('#181e23', left - 2, py, TILE - left + 2, 8);
    draw('#283437', left, py, TILE - left, 7);
    draw('#3c4648', left, py, TILE - left, 1);
  }
}

export function innTile(context, map, x, y) {
  const tile = map[y][x];
  const gateFloor = x >= 21 && x <= 24 && y >= 10 && y <= 15;
  if (tile === '#' || gateFloor) {
    masonry(context, x, y, gateFloor);
    if (tile === '#' || gateFloor && tile === 'D') return;
  } else {
    wood(context, x, y);
    rug(context, x, y);
    if (x === 20 && y >= 10 && y <= 15) thresholdStone(context, x, y);
  }
  if (tile === 'D') {
    const draw = stamp(context, x * TILE, y * TILE);
    draw('#2b2524', 0, 13, 32, 19);
    draw('#635343', 0, 16, 32, 4);
    draw('#887460', 0, 18, 32, 1);
    draw('#3e3632', 0, 21, 32, 2);
  } else if (tile === 'B' || tile === 'T') {
    if (!(tile === 'B' && y === 1 && (x >= 15 || x >= 6 && x <= 8))) furniture(context, map, x, y, tile);
  }
}

function glow(context, x, y, radius, color, alpha) {
  const gradient = context.createRadialGradient(x, y, 0, x, y, radius);
  gradient.addColorStop(0, `rgba(${color},${alpha})`);
  gradient.addColorStop(.36, `rgba(${color},${alpha * .47})`);
  gradient.addColorStop(1, `rgba(${color},0)`);
  rect(context, gradient, x - radius, y - radius, radius * 2, radius * 2);
}

function hearth(context) {
  const [x, y] = HEARTH;
  const draw = stamp(context, x - 45, y - 25);
  draw('#201919', 0, 8, 90, 52);
  draw('#4b3a34', 4, 7, 82, 50);
  draw('#685044', 0, 9, 90, 5);
  draw('#876048', 4, 8, 82, 2);
  draw('#8a5d43', 8, 19, 74, 2);
  draw('#3c2b27', 9, 21, 72, 29);
  draw('#a06f4c', 24, 23, 42, 26);
  draw('#25161a', 28, 27, 34, 22);
  draw('#5e3427', 28, 44, 34, 6);
  draw('#b76839', 30, 46, 30, 2);
  for (let dy = 19; dy < 52; dy += 8) {
    for (const dx of [8, 67]) {
      draw('#74503d', dx, dy, 14, 6);
      draw('#3d2a29', dx + (dy % 3) * 4, dy, 1, 6);
    }
  }
  draw('#85654f', 2, 52, 86, 4);
  draw('#3c2a27', 0, 57, 90, 4);
}

function windowAndTapestry(context) {
  const window = stamp(context, 2 * TILE - 5, 7);
  window('#433942', 5, 0, 23, 25);
  window('#51464d', 2, 5, 29, 19);
  window('#15222d', 6, 6, 21, 16);
  window('#284453', 9, 3, 15, 9);
  window('#5b7782', 17, 4, 2, 4);
  window('#394d56', 15, 5, 2, 17);
  window('#29232a', 1, 23, 31, 4);
  const tapestry = stamp(context, 11 * TILE + 7, 5);
  tapestry('#604333', 0, 0, 18, 2);
  tapestry('#6a292c', 2, 2, 14, 23);
  tapestry('#af7147', 8, 6, 2, 12);
  tapestry('#af7147', 5, 10, 8, 2);
  tapestry('#90583f', 2, 22, 14, 2);
}

function barBack(context, map) {
  const start = 15 * TILE;
  const y = 17;
  const draw = stamp(context, start, y);
  draw('#201a1c', 0, 0, 9 * TILE, 46);
  draw('#543627', 0, 0, 9 * TILE, 3);
  draw('#624430', 0, 20, 9 * TILE, 4);
  draw('#6e4a34', 0, 38, 9 * TILE, 4);
  for (let x = 15; x <= 23; x++) {
    if (map[1]?.[x] !== 'B') continue;
    const px = (x - 15) * TILE;
    draw('#6b4d37', px, 0, 3, 42);
    for (const [row, shelf] of [20, 38].entries()) {
      for (let b = 0; b < 3; b++) {
        const bx = px + 6 + b * 7;
        const h = 5 + variant(x, b + row, 5);
        const tone = BOTTLES[variant(x + b, row, BOTTLES.length)];
        draw(tone, bx, shelf - h, 5, h);
        draw(tone, bx + 1, shelf - h - 3, 2, 3);
        draw('#867a54', bx + 1, shelf - 4, 2, 2);
      }
    }
  }
}

function kegsAndStools(context, map) {
  for (const x of [19, 20, 22, 23]) {
    if (map[5]?.[x] !== 'B') continue;
    const draw = stamp(context, x * TILE + 4, 5 * TILE + 8);
    draw('#2e211d', 0, 4, 14, 13);
    draw('#795039', 2, 2, 10, 14);
    draw('#aa6e48', 3, 2, 8, 2);
    draw('#4b3d35', 1, 5, 12, 2);
    draw('#4b3d35', 1, 12, 12, 2);
    draw('#b17a49', 5, 10, 4, 1);
  }
  const tap = stamp(context, 17 * TILE + 10, 5 * TILE + 6);
  tap('#2d221c', 0, 6, 12, 6);
  tap('#b58a55', 3, 0, 5, 9);
  tap('#d9a86e', 4, 0, 2, 7);
  tap('#a7794c', 5, 5, 7, 3);
  for (const x of [16, 18, 21, 23]) {
    if (map[6]?.[x] !== '.') continue;
    const draw = stamp(context, x * TILE + 9, 6 * TILE + 1);
    draw('#211b1b', 0, 0, 14, 9);
    draw('#5c4232', 1, 0, 12, 5);
    draw('#785540', 2, 0, 10, 2);
    draw('#2f2420', 3, 5, 2, 5);
    draw('#2f2420', 9, 5, 2, 5);
  }
}

function gate(context) {
  const [x, y] = GATE;
  const draw = stamp(context, x - 52, y - 22);
  draw('#172225', 0, 9, 104, 56);
  draw('#39484a', 2, 5, 100, 10);
  draw('#5b5e54', 8, 2, 88, 3);
  draw('#263837', 9, 15, 86, 47);
  draw('#102621', 19, 19, 66, 59);
  draw('#162a27', 22, 40, 60, 111);
  draw('#344341', 8, 11, 10, 68);
  draw('#46524a', 9, 13, 2, 61);
  draw('#344341', 86, 11, 10, 68);
  draw('#46524a', 87, 13, 2, 61);
  for (let dx = 21; dx <= 82; dx += 8) {
    draw('#415747', dx, 18, 2, 21);
    draw('#233d35', dx + 2, 18, 2, 18);
  }
  for (let row = 0; row < 8; row++) {
    const py = 55 + row * 12;
    draw(row < 3 ? '#253b36' : '#1e3332', 23, py, 58, 9);
    draw('#506451', 23, py, 58, 2);
    draw('#0e2424', 23, py + 9, 58, 3);
  }
  draw('#62725b', 44, 8, 16, 2);
  draw('#536e5b', 51, 5, 2, 7);
}

function sconce(context, x, y) {
  const draw = stamp(context, x, y);
  draw('#2e2220', -5, 1, 10, 10);
  draw('#765341', -4, 0, 8, 4);
  draw('#ad7848', -3, -1, 6, 2);
}

function tableLight(context, x, y) {
  const draw = stamp(context, x, y);
  draw('#463026', -5, 2, 10, 3);
  draw('#d4b177', -1, -3, 3, 5);
}

export function decorateInn(context, map) {
  if (map[1]?.[7] !== 'B' || map[5]?.[19] !== 'B') return;
  hearth(context);
  windowAndTapestry(context);
  barBack(context, map);
  kegsAndStools(context, map);
  gate(context);
  for (const [x, y, localY] of SCONCES) sconce(context, (x + .5) * TILE, y * TILE + localY + 3);
  for (const [x, y] of TABLE_CANDLES) {
    if (map[y]?.[x] === 'T') tableLight(context, x * TILE + 9, y * TILE + 12);
  }

  const width = map[0].length * TILE;
  const height = map.length * TILE;
  const vignette = context.createRadialGradient(width * .5, height * .46, width * .15,
    width * .5, height * .46, width * .62);
  vignette.addColorStop(0, 'rgba(12,9,15,0)');
  vignette.addColorStop(.62, 'rgba(12,9,15,.22)');
  vignette.addColorStop(1, 'rgba(8,8,13,.61)');
  rect(context, vignette, 0, 0, width, height);

  context.save();
  context.globalCompositeOperation = 'screen';
  glow(context, HEARTH[0], HEARTH[1] + 14, 165, '255,115,39', .48);
  glow(context, 20 * TILE, 4.2 * TILE, 146, '237,149,69', .23);
  glow(context, GATE[0], GATE[1] + 56, 133, '62,174,127', .26);
  for (const [x, y, localY] of SCONCES) glow(context, (x + .5) * TILE, y * TILE + localY, 58, '243,139,66', .38);
  for (const [x, y] of TABLE_CANDLES) {
    if (map[y]?.[x] === 'T') glow(context, x * TILE + 9, y * TILE + 11, 45, '238,143,66', .29);
  }
  context.restore();
}

function flame(context, x, bottom, now, scale = 1) {
  const flicker = Math.floor(now / 110) % 5;
  const tilt = [0, 1, -1, 1, 0][flicker];
  rect(context, '#a74527', x - 3 * scale, bottom - 5 * scale, 7 * scale, 5 * scale);
  rect(context, '#ef8234', x - 2 * scale, bottom - 6 * scale, 5 * scale, 6 * scale);
  rect(context, '#ffc156', x + tilt * scale, bottom - (7 + (flicker & 1)) * scale, 2 * scale, 4 * scale);
  rect(context, '#fff0a2', x - scale, bottom - 4 * scale, 3 * scale, 4 * scale);
}

export function animateInn(context, map, now) {
  if (map[1]?.[7] !== 'B' || map[5]?.[19] !== 'B') return;
  for (let i = -1; i <= 1; i++) {
    flame(context, HEARTH[0] + i * 8, HEARTH[1] + 20, now + 180 * i, i === 0 ? 2 : 1);
  }
  for (let i = 0; i < 6; i++) {
    const age = (now + i * 241) % 1440 / 1440;
    const x = HEARTH[0] + (i - 3) * 5 + Math.round(Math.sin(age * 6 + i) * 4);
    rect(context, `rgba(255,163,71,${1 - age})`, x, HEARTH[1] + 14 - Math.round(age * 21), 1 + i % 2, 2);
  }
  for (const [x, y, localY] of SCONCES) flame(context, (x + .5) * TILE, y * TILE + localY, now + 90 * x, .72);
  for (const [x, y] of TABLE_CANDLES) {
    if (map[y]?.[x] === 'T') flame(context, x * TILE + 9, y * TILE + 8, now + 91 * x, .55);
  }
  for (let i = 0; i < 3; i++) {
    const age = (now + i * 640) % 2000 / 2000;
    const x = GATE[0] - 19 + i * 16 + Math.round(Math.sin(age * 5 + i) * 4);
    rect(context, `rgba(136,215,167,${Math.sin(age * Math.PI) * .66})`, x, GATE[1] + 75 - Math.round(age * 32), 2, 2);
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
