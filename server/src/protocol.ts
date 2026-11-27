// SPDX-License-Identifier: GPL-3.0-or-later
// Wire protocol constants and validators shared by the Worker and both Durable Objects.
// server/PROTOCOL.md is the normative description; keep the two in step.

export const WIRE_VERSION = 1;

export const PATHS = {
  health: '/api/v5/health',
  inn: '/api/v5/inn',
  link: '/api/v5/link',
} as const;

export const CLOSE = {
  normal: 1000,
  goingAway: 1001,
  unsupportedData: 1003,
  tooBig: 1009,
  internal: 1011,
  badMessage: 4000,
  unsupportedVersion: 4001,
  helloTimeout: 4002,
  forbidden: 4003,
  notFound: 4004,
  rateLimited: 4008,
  replaced: 4009,
  ended: 4010,
  full: 4013,
  tooManyConnections: 4029,
} as const;

export const LIMITS = {
  /** Largest inn frame (bytes of UTF-8 text). */
  innFrameBytes: 4096,
  /**
   * Largest link frame. `data.d` is capped by `linkDataBytes`; JSON escaping can grow a payload up
   * to six times (`\u00XX`), so the frame limit leaves room for any valid payload.
   */
  linkFrameBytes: 6 * 24 * 1024 + 1024,
  /** Matches the web client's MAX_MESSAGE_BYTES and the TeaVM bridge's 24576-character filter. */
  linkDataBytes: 24 * 1024,
  nameChars: 20,
  chatChars: 160,
  coordMax: 127,
  socketsPerShard: 32,
  /** All sockets an object keeps, counting closed ones whose close was never acknowledged. */
  socketsPerShardTotal: 64,
  /** Sockets one client address may hold in one room shard (households and carrier NAT share addresses). */
  socketsPerAddress: 8,
  shardsPerLayout: 8,
  /** Seconds a client should wait after `inn_full` before probing the shards again. */
  fullRetryAfterS: 30,
  linkSockets: 6,
  linkSocketsTotal: 12,
  /** While paired, the link checks for a silent or vanished side at least this often. */
  linkCheckMs: 120_000,
  eventsKept: 30,
  eventMaxAgeMs: 15 * 60_000,
  helloTimeoutMs: 10_000,
  /** A socket is dropped when neither a message nor a `ping` arrived for this long. */
  staleMs: 100_000,
  pairTimeoutMs: 45_000,
  reconnectGraceMs: 20_000,
  linkLifetimeMs: 12 * 3600_000,
  linkBufferMessages: 512,
  linkBufferBytes: 2 * 1024 * 1024,
} as const;

export const KEY = /^[A-Za-z0-9_-]{32,128}$/;
export const LAYOUT_ID = /^[0-9a-f]{64}$/;
export const MATCH_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const NPC_ID = /^[a-z0-9][a-z0-9_-]{0,47}$/;
export const PREPARATION_ID = MATCH_ID;
const OPTION_ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const FORBIDDEN_CHARS = /[\p{C}\p{Zl}\p{Zp}]/u;
export const THEMES = ['sewers', 'prison'] as const;
export type Theme = typeof THEMES[number];

export const APPEARANCE_FIELDS = ['job', 'hairColor', 'outfitColor', 'hairStyle', 'accessory'] as const;
export type Appearance = Record<typeof APPEARANCE_FIELDS[number], string>;

export interface Preparation { id: string; hp: number; food: number }

export type Json = Record<string, unknown>;

export function parseFrame(message: string | ArrayBuffer, maxBytes: number):
    { frame: Json } | { close: number; reason: string } {
  if (typeof message !== 'string') return { close: CLOSE.unsupportedData, reason: 'text_only' };
  // A UTF-16 length check first avoids encoding a huge string; then the exact byte count.
  if (message.length > maxBytes || utf8Bytes(message) > maxBytes) return { close: CLOSE.tooBig, reason: 'too_big' };
  let frame: unknown;
  try { frame = JSON.parse(message); } catch { return { close: CLOSE.badMessage, reason: 'invalid_json' }; }
  if (!frame || typeof frame !== 'object' || Array.isArray(frame) || typeof (frame as Json).t !== 'string') {
    return { close: CLOSE.badMessage, reason: 'invalid_frame' };
  }
  return { frame: frame as Json };
}

const encoder = new TextEncoder();
export function utf8Bytes(text: string): number { return encoder.encode(text).byteLength; }

/** Trimmed single-line text of 1..max code points without control or separator characters. */
export function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== 'string' || value.length > max * 2 + 64) return null;
  const text = value.trim();
  if (!text || [...text].length > max || FORBIDDEN_CHARS.test(text)) return null;
  return text;
}

/** Appearance option ids; the clients normalise unknown ids to their catalogue defaults. */
export function cleanAppearance(value: unknown): Appearance | null {
  if (value == null) return null;
  if (typeof value !== 'object' || Array.isArray(value)) return null;
  const result = {} as Appearance;
  for (const field of APPEARANCE_FIELDS) {
    const option = (value as Json)[field];
    if (option == null) continue;
    if (typeof option !== 'string' || !OPTION_ID.test(option)) return null;
    result[field] = option;
  }
  return result;
}

export function isCoord(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0 && (value as number) <= LIMITS.coordMax;
}

export function isRef(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

export function isDungeonProtocol(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 && (value as number) <= 1_000_000;
}

export function cleanTheme(value: unknown): Theme | null {
  if (value == null) return 'sewers';
  return THEMES.includes(value as Theme) ? value as Theme : null;
}

export function cleanBool(value: unknown, fallback = false): boolean | null {
  if (value == null) return fallback;
  return typeof value === 'boolean' ? value : null;
}
