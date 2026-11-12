// SPDX-License-Identifier: GPL-3.0-or-later
const loadedEngines = new Map();
const decodedArt = new Map();
const ART = ['rat', 'items', 'tiles_sewers', 'tiles_prison'];

export function runtimeImage(version, name) {
  return decodedArt.get(version)?.get(name) ?? null;
}

async function importEngine(entry, version) {
  const module = await import(entry);
  if (typeof module.HostBattle !== 'function') throw new Error('Missing dungeon runtime entry');
  loadedEngines.set(version, module.HostBattle);
}

async function decodeArt(base, version) {
  const images = await Promise.all(ART.map(async (name) => {
    const image = new Image();
    image.src = `${base}assets/${name}.png`;
    await image.decode();
    return [name, image];
  }));
  decodedArt.set(version, new Map(images));
}

async function checkManifest(signal) {
  const timeout = AbortSignal.timeout(5000);
  const response = await fetch('/play/runtime-manifest.json', {
    cache: 'no-store', signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  });
  if (!response.ok) throw new Error('Dungeon runtime manifest unavailable');
  const manifest = await response.json();
  if (!/^[0-9a-f]{64}$/.test(manifest?.version ?? '') ||
      manifest.entry !== `/play/runtime/${manifest.version}/battle.mjs`) {
    throw new Error('Invalid dungeon runtime manifest');
  }
  return manifest;
}

export async function loadBattleRuntime({ simulate, signal, onLoading }) {
  onLoading({ phase: 'checking', version: null, cached: false });
  let failedVersion;
  for (let attempt = 0; ; attempt++) {
    let manifest;
    try {
      signal?.throwIfAborted();
      manifest = await checkManifest(signal);
      const { version } = manifest;
      const needEngine = simulate && !loadedEngines.has(version);
      const needArt = !decodedArt.has(version);
      const cached = !needEngine && !needArt;
      if (!cached) {
        onLoading({ phase: 'loading', version, cached: false });
        // A failed dependency import can stay cached for its URL. Retry the whole module graph under a fresh path.
        const base = version === failedVersion
          ? `/play/runtime/${version}/retry/${crypto.randomUUID().replaceAll('-', '')}/`
          : `/play/runtime/${version}/`;
        await Promise.all([
          needEngine && importEngine(base + 'battle.mjs', version),
          needArt && decodeArt(base, version),
        ]);
      }
      signal?.throwIfAborted();
      return { version, HostBattle: simulate ? loadedEngines.get(version) : null, cached };
    } catch (error) {
      if (signal?.aborted || attempt > 0) throw error;
      failedVersion = manifest?.version;
    }
  }
}
