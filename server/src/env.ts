// SPDX-License-Identifier: GPL-3.0-or-later
import type { InnRoom } from './inn';
import type { PartyLink } from './link';
import type { Appearance, Preparation, Theme } from './protocol';

export interface Env {
  INN: DurableObjectNamespace<InnRoom>;
  LINK: DurableObjectNamespace<PartyLink>;
  /** Comma-separated extra browser origins allowed to open sockets (for example a local preview). */
  ALLOWED_ORIGINS?: string;
}

export interface LinkPlayer {
  role: 'host' | 'guest';
  id: string;
  name: string;
  appearance: Appearance;
  preparation: Preparation | null;
}

export interface LinkConfig {
  id: string;
  seed: number;
  friendlyFire: boolean;
  theme: Theme;
  dungeonProtocol: number;
  createdAt: number;
  players: LinkPlayer[];
}
