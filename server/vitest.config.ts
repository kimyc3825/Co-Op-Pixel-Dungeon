import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: './wrangler.jsonc' },
      miniflare: { bindings: { ALLOWED_ORIGINS: 'https://preview.example' } },
    }),
  ],
  test: { include: ['test/**/*.test.ts'] },
});
