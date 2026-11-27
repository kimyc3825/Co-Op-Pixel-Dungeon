// SPDX-License-Identifier: GPL-3.0-or-later
// Types for `cloudflare:workers` `env` and `exports` inside the test runtime.
import type { Env as AppEnv } from '../src/env';

declare global {
  namespace Cloudflare {
    interface Env extends AppEnv {}
    interface GlobalProps {
      mainModule: typeof import('../src/index');
      durableNamespaces: 'InnRoom' | 'PartyLink';
    }
  }
}
