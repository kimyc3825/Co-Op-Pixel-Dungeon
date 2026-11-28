// SPDX-License-Identifier: GPL-3.0-or-later
// Where the inn rooms and party links live. A Durable Object is placed near the request that first
// creates it and never moves, so without a hint an inn room first opened from another continent
// would carry every later chat line and party packet there too. Players are mostly in Korea.
import type { Env } from './env';

/** Cloudflare offers regions only (no single country); `apac` keeps objects in Asia-Pacific. */
export const LOCATION_HINT: DurableObjectLocationHint = 'apac';

/**
 * The name of one inn shard. The `apac:` prefix gave the rooms new identities when the hint was
 * added, because existing objects ignore hints; inn rooms keep nothing once empty, so no state is lost.
 */
export const innName = (layout: string, shard: number) => `apac:${layout}:${shard}`;

export const innStub = (env: Env, layout: string, shard: number) =>
  env.INN.get(env.INN.idFromName(innName(layout, shard)), { locationHint: LOCATION_HINT });

/** Links are created fresh for every match, so the hint applies to each new party. */
export const linkStub = (env: Env, id: string) =>
  env.LINK.get(env.LINK.idFromName(id), { locationHint: LOCATION_HINT });
