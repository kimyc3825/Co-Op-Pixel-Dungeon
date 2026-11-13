// SPDX-License-Identifier: GPL-3.0-or-later
const ID = /^[a-z0-9][a-z0-9_-]{0,47}$/;
const MAX_SIZE = 128;
let pending;

export function loadInnLayout() {
  pending ??= fetch(new URL('./assets/inn-layout.json', import.meta.url)).then(async response => {
    if (!response.ok) throw new Error('Could not load inn layout');
    const layout = await response.json();
    const { map, npcs, spawns, expeditionGate } = layout;
    if (!Array.isArray(map) || map.length === 0 || map.length > MAX_SIZE ||
        typeof map[0] !== 'string' || map[0].length === 0 || map[0].length > MAX_SIZE ||
        !map.every(row => typeof row === 'string' && row.length === map[0].length && /^[#.BTND]+$/.test(row)) ||
        !Array.isArray(npcs) || !npcs.every(npc => typeof npc?.id === 'string' && ID.test(npc.id) &&
          typeof npc.name === 'string' && typeof npc.role === 'string' && typeof npc.dialogue === 'string' &&
          Number.isInteger(npc.x) && Number.isInteger(npc.y) && map[npc.y]?.[npc.x] === 'N') ||
        new Set(npcs.map(({ id }) => id)).size !== npcs.length ||
        !Array.isArray(spawns) || !spawns.length || !spawns.every(point => walkable(map, point?.x, point?.y)) ||
        expeditionGate && (!inMap(map, expeditionGate.left, expeditionGate.top) ||
          !inMap(map, expeditionGate.right, expeditionGate.bottom) ||
          expeditionGate.right < expeditionGate.left || expeditionGate.bottom < expeditionGate.top)) {
      throw new Error('Invalid inn layout');
    }
    // The room depends on the collision grid and NPC identities, independent of their art and text.
    const points = npcs.map(({ id, x, y }) => `${id} ${x} ${y}`).sort();
    const geometry = `inn-layout-v1\n${map.join('\n')}\n\n${points.join('\n')}\n`;
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(geometry));
    const id = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
    return { ...layout, id };
  }).catch(error => {
    pending = undefined;
    throw error;
  });
  return pending;
}

function inMap(map, x, y) {
  return Number.isInteger(x) && Number.isInteger(y) && typeof map?.[y]?.[x] === 'string';
}

export function walkable(map, x, y) {
  return inMap(map, x, y) && (map[y][x] === '.' || map[y][x] === 'D');
}

export function localInnState(state, layout) {
  if (state.layoutId === undefined) return state;
  if (state.layoutId !== layout.id) throw new Error('Inn layout does not match the session');
  const self = state.people.find(({ id }) => id === state.selfId);
  if (!self || !walkable(layout.map, self.x, self.y)) throw new Error('Invalid inn position');
  return {
    ...state,
    map: layout.map,
    npcs: layout.npcs,
    expeditionGate: layout.expeditionGate,
    people: state.people.filter(({ x, y }) => walkable(layout.map, x, y)),
  };
}
