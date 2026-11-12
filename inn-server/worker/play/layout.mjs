// SPDX-License-Identifier: GPL-3.0-or-later

export const SEWERS = 'sewers';
export const PRISON = 'prison';
const COLUMNS = 3;
const ROWS = 2;
const BAY_SIZE = 6;

function layout(theme, map, host, partner, enemy1, enemy2) {
  return Object.freeze({
    theme, map: Object.freeze(map),
    host: Object.freeze(host), partner: Object.freeze(partner),
    enemy1: Object.freeze(enemy1), enemy2: Object.freeze(enemy2),
  });
}

const SEWER_LAYOUT = layout(SEWERS, [
  '#############',
  '#...........#',
  '#.....#.....#',
  '#.....#.....#',
  '#...........#',
  '#..##.......#',
  '#...........#',
  '#.....#.....#',
  '#############',
], { x: 2, y: 2 }, { x: 3, y: 2 }, { x: 9, y: 2 }, { x: 10, y: 6 });

export function themeOrDefault(theme) {
  return theme === PRISON ? PRISON : SEWERS;
}

export function dungeonLayout(theme, seed) {
  return themeOrDefault(theme) === PRISON ? prison(seed) : SEWER_LAYOUT;
}

function prison(seed) {
  const next = layoutRandom(seed);
  const grid = Array.from({ length: ROWS * BAY_SIZE + 1 }, () => Array(COLUMNS * BAY_SIZE + 1).fill('#'));

  // As in RegularLevel, place the rooms before carving the LoopBuilder-style route and its branches.
  const centers = [];
  for (let index = 0; index < COLUMNS * ROWS; index++) {
    const width = 3 + next(3);
    const height = 3 + next(3);
    const x = 1 + index % COLUMNS * BAY_SIZE + next(BAY_SIZE - width);
    const y = 1 + Math.floor(index / COLUMNS) * BAY_SIZE + next(BAY_SIZE - height);
    for (let row = y; row < y + height; row++) horizontal(grid, row, x, x + width - 1);
    centers.push({ x: x + Math.floor(width / 2), y: y + Math.floor(height / 2) });
  }

  const loopColumn = next(COLUMNS - 1);
  const loop = [loopColumn, loopColumn + 1, COLUMNS + loopColumn + 1, COLUMNS + loopColumn];
  for (let i = 0; i < loop.length; i++) connect(grid, centers, loop[i], loop[(i + 1) % loop.length]);

  const branchColumn = loopColumn === 0 ? COLUMNS - 1 : 0;
  const branch = next(3);
  if (branch !== 2) connect(grid, centers, 1, branchColumn);
  if (branch !== 1) connect(grid, centers, COLUMNS + 1, COLUMNS + branchColumn);
  if (branch !== 0) connect(grid, centers, branchColumn, COLUMNS + branchColumn);

  const entrance = centers[next(ROWS) * COLUMNS];
  return layout(PRISON, grid.map((row) => row.join('')), { x: entrance.x - 1, y: entrance.y }, entrance,
    centers[COLUMNS - 1], centers.at(-1));
}

function connect(grid, centers, from, to) {
  const a = centers[from];
  const b = centers[to];
  if (Math.floor(from / COLUMNS) === Math.floor(to / COLUMNS)) {
    const divider = Math.max(from % COLUMNS, to % COLUMNS) * BAY_SIZE;
    horizontal(grid, a.y, a.x, divider);
    vertical(grid, divider, a.y, b.y);
    horizontal(grid, b.y, divider, b.x);
  } else {
    const divider = Math.max(Math.floor(from / COLUMNS), Math.floor(to / COLUMNS)) * BAY_SIZE;
    vertical(grid, a.x, a.y, divider);
    horizontal(grid, divider, a.x, b.x);
    vertical(grid, b.x, divider, b.y);
  }
}

function horizontal(grid, y, from, to) {
  grid[y].fill('.', Math.min(from, to), Math.max(from, to) + 1);
}

function vertical(grid, x, from, to) {
  for (let y = Math.min(from, to); y <= Math.max(from, to); y++) grid[y][x] = '.';
}

// Keep the UTF-16 seed hash and xorshift operations in sync with the Windows DungeonLayout.
function layoutRandom(seed) {
  const text = String(seed);
  let state = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) state = Math.imul(state ^ text.charCodeAt(i), 0x01000193);
  if (state === 0) state = 0x6d2b79f5;
  return (bound) => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 1) % bound;
  };
}
