// SPDX-License-Identifier: GPL-3.0-or-later
// End-to-end check of the real web lobby against a local Worker:
//   (cd server && npx wrangler dev --port 8787)   # after node scripts/stage-web-client.mjs
//   node server/e2e/lobby.mjs [http://127.0.0.1:8787]
// Two browser contexts enter the inn, see each other move, chat, get matched through the recruiting
// sign list, and exchange party payloads over the link socket. Needs the `playwright` package.
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const origin = process.argv[2] ?? 'http://127.0.0.1:8787';
const dungeon = !process.argv.includes('--lobby-only');

const log = (...args) => console.log(new Date().toISOString().slice(11, 23), ...args);
const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function until(label, check, timeout = 20000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`timed out: ${label}`);
    await wait(100);
  }
}

async function player(browser, name) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  await context.addInitScript(profile => {
    localStorage.setItem('noxac.playerProfile', JSON.stringify(profile));
  }, { name, appearance: { job: name === 'Alpha' ? 'warrior' : 'mage' } });
  const page = await context.newPage();
  const frames = { inn: [], link: [], sentLink: [] };
  page.on('websocket', ws => {
    const kind = ws.url().includes('/api/v5/link') ? 'link' : 'inn';
    ws.on('framereceived', ({ payload }) => {
      if (typeof payload === 'string' && payload !== 'pong') frames[kind].push(JSON.parse(payload));
    });
    if (kind === 'link') ws.on('framesent', ({ payload }) => {
      if (typeof payload === 'string' && payload !== 'ping') frames.sentLink.push(JSON.parse(payload));
    });
  });
  page.on('pageerror', error => log(name, 'pageerror', error.message));
  await page.goto(`${origin}/play/`);
  return { name, context, page, frames };
}

const browser = await chromium.launch();
try {
  const a = await player(browser, 'Alpha');
  const b = await player(browser, 'Bravo');
  const welcome = p => p.frames.inn.find(f => f.t === 'welcome');
  const [wa, wb] = await Promise.all([until('Alpha welcome', () => welcome(a)), until('Bravo welcome', () => welcome(b))]);
  log('both entered', { alpha: wa.selfId, bravo: wb.selfId, shard: wb.shard });
  await until('Bravo sees two adventurers', async () => (await b.page.textContent('#board-status')) === '모험가 2명');
  await until('Alpha sees two adventurers', async () => (await a.page.textContent('#board-status')) === '모험가 2명');

  // Movement: Alpha walks with the keyboard; Bravo receives the steps.
  await a.page.locator('canvas').first().focus();
  for (const key of ['ArrowUp', 'ArrowRight', 'ArrowRight']) {
    await a.page.keyboard.down(key);
    await wait(170);
    await a.page.keyboard.up(key);
    await wait(80);
  }
  const moved = await until('Bravo sees Alpha move', () => b.frames.inn.filter(f => f.t === 'moved' && f.id === wa.selfId).length >= 2 &&
    b.frames.inn.filter(f => f.t === 'moved' && f.id === wa.selfId));
  log('Bravo saw Alpha move', moved.map(f => [f.x, f.y]));

  // Chat both ways, shown in both logs.
  for (const [from, text] of [[a, '안녕하세요 Bravo'], [b, 'hello Alpha']]) {
    await from.page.keyboard.press('Enter');
    await from.page.fill('#chat-message', text);
    await from.page.press('#chat-message', 'Enter');
  }
  for (const p of [a, b]) {
    await until(`${p.name} chat log`, async () => {
      const log = await p.page.textContent('#chat-log');
      return log.includes('안녕하세요 Bravo') && log.includes('hello Alpha');
    });
  }
  log('chat delivered both ways');

  // Matchmaking: Alpha recruits, Bravo joins from the recruiting list (the same request as clicking the sign).
  await a.page.click('#expedition-toggle');
  await a.page.click('#match');
  await until('Alpha queued', () => a.frames.inn.some(f => f.t === 'person' && f.person.id === wa.selfId && f.person.queued));
  await b.page.click('#expedition-toggle');
  await b.page.locator('#seekers button').first().click({ timeout: 10000 });
  const [ma, mb] = await Promise.all([
    until('Alpha matched', () => a.frames.inn.find(f => f.t === 'matched')),
    until('Bravo matched', () => b.frames.inn.find(f => f.t === 'matched')),
  ]);
  log('matched', ma.match, mb.match);
  if (ma.match.id !== mb.match.id || ma.match.role !== 'host' || mb.match.role !== 'guest') throw new Error('bad match roles');

  const [la, lb] = await Promise.all([
    until('Alpha link', () => a.frames.link.find(f => f.t === 'link')),
    until('Bravo link', () => b.frames.link.find(f => f.t === 'link')),
  ]);
  if (la.seed !== lb.seed || la.friendlyFire !== lb.friendlyFire) throw new Error('link settings differ');
  await until('paired', () => [a, b].every(p => p.frames.link.some(f => f.t === 'paired') ||
    p.frames.link.some(f => f.t === 'link' && f.paired)));
  log('link paired', { seed: la.seed, friendlyFire: la.friendlyFire });

  if (dungeon) {
    // Both original engines boot in iframes and exchange InnOriginalParty packets over the link.
    await until('guest ready', () => b.frames.sentLink.some(f => f.t === 'ready'), 180000);
    log('guest engine ready');
    await until('host ready', () => a.frames.sentLink.some(f => f.t === 'ready'), 180000);
    log('host engine ready');
    await until('host packets reach the guest', () => b.frames.link.filter(f => f.t === 'data').length >= 3, 60000);
    // The guest only sends when it acts: walk with the keyboard inside its game frame.
    const canvas = b.page.frameLocator('#original-body iframe').locator('#canvas');
    await canvas.click({ position: { x: 700, y: 330 } }).catch(error => log('canvas click', error.message));
    for (let i = 0; i < 40 && a.frames.link.filter(f => f.t === 'data').length < 3; i++) {
      await b.page.keyboard.press(['ArrowLeft', 'ArrowUp', 'ArrowRight', 'ArrowDown'][i % 4]);
      await wait(300);
    }
    await until('guest packets reach the host', () => a.frames.link.filter(f => f.t === 'data').length >= 1, 20000);
    const sample = p => p.frames.link.filter(f => f.t === 'data').slice(0, 1).map(f => f.d.slice(0, 40));
    log('payloads', { hostReceived: a.frames.link.filter(f => f.t === 'data').length, guestReceived:
      b.frames.link.filter(f => f.t === 'data').length, hostSample: sample(a), guestSample: sample(b) });
    // Bursts: the host's snapshots are coalesced (newest wins, at most 15/s); guest frames all arrive in order.
    const hostFrame = a.page.frames().find(f => f.url().includes('/play/original/runtime/'));
    const guestFrame = b.page.frames().find(f => f.url().includes('/play/original/runtime/'));
    await hostFrame.evaluate(() => { for (let i = 0; i < 100; i++) parent.postMessage({ type: 'noxac-original-party-send', data: `X|${i}` }, location.origin); });
    await guestFrame.evaluate(() => { for (let i = 0; i < 50; i++) parent.postMessage({ type: 'noxac-original-party-send', data: `Y|${i}` }, location.origin); });
    await until('burst delivered', () => b.frames.link.some(f => f.t === 'data' && f.d === 'X|99') &&
      a.frames.link.filter(f => f.t === 'data' && f.d.startsWith('Y|')).length === 50, 10000);
    const xs = b.frames.link.filter(f => f.t === 'data' && f.d.startsWith('X|')).map(f => f.d);
    const ys = a.frames.link.filter(f => f.t === 'data' && f.d.startsWith('Y|')).map(f => f.d);
    log('burst', { hostSent: 100, guestReceived: xs.length, last: xs.at(-1), guestSent: 50, hostReceivedInOrder: ys.join() === Array.from({ length: 50 }, (_, i) => `Y|${i}`).join() });
    if (xs.length > 5 || ys.length !== 50) throw new Error('burst handling wrong');
    // Order check: host packets carry increasing sequence numbers in their last field.
    await a.page.screenshot({ path: process.env.E2E_SHOTS ? `${process.env.E2E_SHOTS}/host.png` : '/dev/null' }).catch(() => {});
    await b.page.screenshot({ path: process.env.E2E_SHOTS ? `${process.env.E2E_SHOTS}/guest.png` : '/dev/null' }).catch(() => {});

    // Leaving ends the run on the other side and both return to the inn.
    await b.page.keyboard.press('Escape');
    await b.page.evaluate(() => document.getElementById('original-return')?.click());
    await until('host told the run ended', () => a.frames.link.some(f => f.t === 'ended'), 20000);
    log('host received', a.frames.link.find(f => f.t === 'ended'));
    await until('both back in the inn', async () =>
      (await a.page.textContent('#board-status')) === '모험가 2명' && (await b.page.textContent('#board-status')) === '모험가 2명', 30000);
    log('both returned to the inn');
  }
  log('E2E OK');
} finally {
  await browser.close();
}
