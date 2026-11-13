// SPDX-License-Identifier: GPL-3.0-or-later
import { OriginalDownload } from './original-download.mjs';
import { normalizeAppearance } from './avatar-catalog.mjs';

const BOOT_TIMEOUT = 120_000;
const RETREAT_TIMEOUT = 3_000;

export class OriginalGame {
  constructor({ suspend, resume, canLaunch, getProfile, getPreparation, usePreparation, onPartyReady, onPartySend,
    onPartyLeave, onChange, tips }) {
    this.suspend = suspend;
    this.resume = resume;
    this.canLaunch = canLaunch;
    this.getProfile = getProfile;
    this.getPreparation = getPreparation;
    this.usePreparation = usePreparation;
    this.onPartyReady = onPartyReady;
    this.onPartySend = onPartySend;
    this.onPartyLeave = onPartyLeave;
    this.onChange = onChange;
    this.tips = tips;
    this.shell = document.getElementById('play-shell');
    this.screen = document.getElementById('original-screen');
    this.title = document.getElementById('original-title');
    this.body = document.getElementById('original-body');
    this.loading = document.getElementById('original-loading');
    this.ember = document.getElementById('original-ember');
    this.label = document.getElementById('original-phase');
    this.download = new OriginalDownload(() => {
      if (this.phase === 'loading' && this.progress < 0) this.label.textContent = this.download.label;
    });
    this.tip = document.getElementById('original-tip');
    this.retry = document.getElementById('original-retry');
    this.back = document.getElementById('original-return');
    this.tipIndex = Math.floor(Math.random() * tips.length);
    this.tipTimer = null;
    this.frame = this.attempt = this.suspending = this.phase = this.launchProfile = null;
    this.deadline = null;
    this.progress = -1;
    document.getElementById('original-launch').addEventListener('click', () => this.start());
    this.back.addEventListener('click', () => void this.returnToInn());
    this.retry.addEventListener('click', () => {
      if (this.phase === 'failed') void this.load();
    });
    window.addEventListener('message', (event) => this.onMessage(event));
  }

  get active() { return !this.screen.hidden; }

  warm() { this.download.warm(); }

  start(atb = false, practice = false) {
    if (this.active || !this.canLaunch()) return false;
    this.preparation = atb && !practice ? this.getPreparation?.() : null;
    this.consumingPreparation = null;
    const { name, appearance } = this.getProfile();
    this.launchProfile = new URLSearchParams({ name, ...normalizeAppearance(appearance) });
    if (atb || practice) this.launchProfile.set('atb', '1');
    if (practice) this.launchProfile.set('practice', '1');
    if (this.preparation) {
      this.launchProfile.set('prepHp', this.preparation.hp);
      this.launchProfile.set('prepFood', this.preparation.food);
    }
    this.show(practice ? '길잡이 연습 · 원본 ATB' : atb ? '원본 던전 · ATB' : '원본 1인 저장');
    return true;
  }

  startParty({ link, seed, friendlyFire }) {
    if (this.active) return false;
    this.party = true;
    this.preparation = this.consumingPreparation = null;
    this.launchProfile = new URLSearchParams({ name: link.selfName, ...normalizeAppearance(link.selfAppearance),
      atb: '1', partyRole: link.role, partySeed: seed, partyName: link.partnerName,
      friendlyFire: friendlyFire ? '1' : '0' });
    for (const [key, value] of Object.entries(normalizeAppearance(link.partnerAppearance))) {
      this.launchProfile.set(`party${key[0].toUpperCase()}${key.slice(1)}`, value);
    }
    if (link.role === 'host' && link.selfPreparation) {
      this.launchProfile.set('prepHp', link.selfPreparation.hp);
      this.launchProfile.set('prepFood', link.selfPreparation.food);
    }
    this.show('2인 원정 · 원본 ATB');
    return true;
  }

  show(title) {
    this.title.textContent = title;
    this.shell.hidden = true;
    this.screen.hidden = false;
    this.onChange(true);
    void this.load();
  }

  async load() {
    const attempt = new AbortController();
    this.attempt = attempt;
    this.setPhase('checking', '던전 버전 확인 중');
    this.back.focus();
    try {
      if (!this.party) this.suspending ??= this.suspend().catch(() => false);
      const suspended = this.party || await this.suspending;
      if (attempt.signal.aborted) return;
      if (!suspended) {
        this.suspending = null;
        this.fail('여관을 나가지 못했습니다');
        return;
      }
      const entry = await this.download.enter(attempt.signal);
      if (attempt.signal.aborted) return;
      this.setPhase('loading', this.download.label);
      const frame = document.createElement('iframe');
      frame.title = this.title.textContent;
      frame.allow = 'fullscreen; autoplay';
      frame.referrerPolicy = 'no-referrer';
      frame.tabIndex = -1;
      frame.inert = true;
      // Keep the profile out of asset requests and their cache keys.
      frame.src = `${entry}#${this.launchProfile}`;
      this.frame = frame;
      this.progress = -1;
      this.body.prepend(frame);
      this.resetBootDeadline();
    } catch {
      if (!attempt.signal.aborted) this.fail('던전을 불러오지 못했습니다');
    }
  }

  setPhase(phase, label) {
    this.phase = phase;
    const loading = phase === 'checking' || phase === 'loading';
    const failed = phase === 'failed';
    this.loading.hidden = phase === 'ready';
    this.ember.hidden = !loading;
    this.label.textContent = label ?? '';
    this.retry.hidden = !failed;
    this.back.disabled = phase === 'retreating' || phase === 'returning';
    if (this.frame) {
      this.frame.tabIndex = phase === 'ready' ? 0 : -1;
      this.frame.inert = phase !== 'ready';
    }
    if (loading) {
      if (this.tipTimer === null) {
        this.tip.textContent = this.tips[this.tipIndex];
        this.tipTimer = setInterval(() => {
          this.tipIndex = (this.tipIndex + 1) % this.tips.length;
          this.tip.textContent = this.tips[this.tipIndex];
        }, 1800);
      }
    } else {
      clearInterval(this.tipTimer);
      this.tipTimer = null;
      this.tip.textContent = '';
    }
    if (failed) this.retry.focus();
  }

  onMessage(event) {
    if (!this.frame || event.source !== this.frame.contentWindow || event.origin !== window.location.origin ||
        this.phase === 'returning') return;
    const { type, percent, reason, data } = event.data ?? {};
    if (type === 'noxac-original-progress' && this.phase === 'loading' &&
        Number.isInteger(percent) && percent >= 0 && percent <= 100 && percent > this.progress) {
      this.progress = percent;
      this.label.textContent = `던전 불러오는 중 · ${percent}%`;
      this.resetBootDeadline();
    } else if (type === 'noxac-original-ready' && this.phase === 'loading') {
      clearTimeout(this.deadline);
      if (this.preparation && !this.consumingPreparation) {
        this.consumingPreparation = this.usePreparation(this.preparation.id);
      }
      this.setPhase('ready');
      this.frame.focus();
      this.frame.contentDocument?.getElementById('canvas')?.focus();
      if (this.party) this.onPartyReady();
    } else if (this.party && type === 'noxac-original-party-send' && this.phase === 'ready' && typeof data === 'string') {
      this.onPartySend(data);
    } else if (type === 'noxac-original-error') {
      if (this.phase === 'retreating') void this.returnToInn();
      else this.fail('던전 실행이 중단되었습니다');
    } else if (type === 'noxac-original-exit') {
      void this.returnToInn(reason === 'death' ? reason : undefined);
    }
  }

  resetBootDeadline() {
    clearTimeout(this.deadline);
    this.deadline = setTimeout(() => this.fail('던전을 시작하지 못했습니다'), BOOT_TIMEOUT);
  }

  receiveParty(data) {
    if (this.party && this.phase === 'ready') this.frame.contentWindow.postMessage(
      { type: 'noxac-original-party-payload', data }, window.location.origin);
  }

  endParty(notice) {
    if (!this.party || this.phase === 'returning') return false;
    this.partyNotice = notice;
    if (this.phase === 'ready') {
      this.setPhase('retreating', '원정 철수 중');
      this.frame.contentWindow.postMessage({ type: 'noxac-original-return-request' }, window.location.origin);
      this.deadline = setTimeout(() => void this.returnToInn(), RETREAT_TIMEOUT);
    } else if (this.phase !== 'retreating') void this.returnToInn();
    return true;
  }

  clearFrame() {
    clearTimeout(this.deadline);
    this.deadline = null;
    if (this.phase === 'retreating') this.frame?.contentDocument?.defaultView?.spd?.prepareForDetach?.();
    this.frame?.remove();
    this.frame = null;
  }

  fail(label) {
    if (this.party) {
      this.partyNotice = label;
      void this.returnToInn();
      return;
    }
    this.clearFrame();
    this.setPhase('failed', label);
  }

  async returnToInn(reason) {
    if (this.phase === 'returning') return;
    this.attempt?.abort();
    this.clearFrame();
    this.setPhase('returning', '여관으로 돌아가는 중');
    await this.consumingPreparation;
    if (this.party) await this.onPartyLeave(this.partyNotice);
    else if (await this.suspending) await this.resume();
    this.party = false;
    this.partyNotice = null;
    this.suspending = this.attempt = this.phase = this.launchProfile = null;
    this.screen.hidden = true;
    this.shell.hidden = false;
    this.onChange(false, reason);
  }
}
