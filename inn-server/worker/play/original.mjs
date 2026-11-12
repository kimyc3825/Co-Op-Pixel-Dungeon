// SPDX-License-Identifier: GPL-3.0-or-later
import { OriginalDownload } from './original-download.mjs';

const BOOT_TIMEOUT = 120_000;
const SAVE_TIMEOUT = 8_000;
const RETREAT_TIMEOUT = 3_000;

export class OriginalGame {
  constructor({ suspend, resume, canLaunch, getProfile, getPreparation, usePreparation, onChange, tips }) {
    this.suspend = suspend;
    this.resume = resume;
    this.canLaunch = canLaunch;
    this.getProfile = getProfile;
    this.getPreparation = getPreparation;
    this.usePreparation = usePreparation;
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
    this.back.addEventListener('click', () => {
      if (this.phase === 'ready') this.requestReturn();
      else void this.returnToInn();
    });
    this.retry.addEventListener('click', () => {
      if (this.phase === 'save-failed') this.requestReturn();
      else if (this.phase === 'failed') void this.load();
    });
    window.addEventListener('message', (event) => this.onMessage(event));
  }

  get active() { return !this.screen.hidden; }
  get atb() { return this.launchProfile?.get('atb') === '1'; }

  warm() { this.download.warm(); }

  start(atb = false) {
    if (this.active || !this.canLaunch()) return;
    const { name, appearance: { job, hairColor, outfitColor, hairStyle, accessory } } = this.getProfile();
    this.launchProfile = new URLSearchParams({ name, job, hairColor, outfitColor, hairStyle, accessory });
    this.preparation = atb ? this.getPreparation?.() : null;
    this.consumingPreparation = null;
    if (atb) this.launchProfile.set('atb', '1');
    if (this.preparation) {
      this.launchProfile.set('prepHp', this.preparation.hp);
      this.launchProfile.set('prepFood', this.preparation.food);
    }
    this.title.textContent = atb ? '원본 던전 · ATB' : '원본 1인 저장';
    this.shell.hidden = true;
    this.screen.hidden = false;
    this.onChange(true);
    this.back.focus();
    void this.load();
  }

  async load() {
    const attempt = new AbortController();
    this.attempt = attempt;
    this.setPhase('checking', '원본 게임 버전 확인 중');
    try {
      this.suspending ??= this.suspend().catch(() => false);
      const suspended = await this.suspending;
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
      // Keep the profile out of asset requests and their cache keys.
      frame.src = `${entry}#${this.launchProfile}`;
      this.frame = frame;
      this.progress = -1;
      this.body.prepend(frame);
      this.resetBootDeadline();
    } catch {
      if (!attempt.signal.aborted) this.fail('원본 게임을 불러오지 못했습니다');
    }
  }

  setPhase(phase, label) {
    this.phase = phase;
    const loading = phase === 'checking' || phase === 'loading';
    const failed = phase === 'failed' || phase === 'save-failed';
    this.loading.hidden = phase === 'ready';
    this.ember.hidden = !loading;
    this.label.textContent = label ?? '';
    this.retry.hidden = !failed;
    this.retry.textContent = phase === 'save-failed' ? '저장 다시 시도' : '다시 시도';
    this.back.textContent = phase === 'save-failed' ? '저장 확인 없이 돌아가기' :
      this.atb && phase === 'ready' ? '여관으로 철수' : '여관으로';
    this.back.disabled = phase === 'saving' || phase === 'retreating' || phase === 'returning';
    if (this.frame) this.frame.tabIndex = phase === 'ready' ? 0 : -1;
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
    const { type, percent } = event.data ?? {};
    if (type === 'noxac-original-progress' && this.phase === 'loading' &&
        Number.isInteger(percent) && percent >= 0 && percent <= 100 && percent > this.progress) {
      this.progress = percent;
      this.label.textContent = `원본 게임 불러오는 중 · ${percent}%`;
      this.resetBootDeadline();
    } else if (type === 'noxac-original-ready' && this.phase === 'loading') {
      clearTimeout(this.deadline);
      if (this.preparation && !this.consumingPreparation) {
        this.consumingPreparation = this.usePreparation(this.preparation.id);
      }
      this.setPhase('ready');
      this.frame.focus();
    } else if (type === 'noxac-original-error') {
      if (this.phase === 'retreating') void this.returnToInn();
      else this.fail('원본 게임이 중단되었습니다');
    } else if (type === 'noxac-original-exit') {
      void this.returnToInn();
    }
  }

  resetBootDeadline() {
    clearTimeout(this.deadline);
    this.deadline = setTimeout(() => this.fail('원본 게임을 시작하지 못했습니다'), BOOT_TIMEOUT);
  }

  requestReturn() {
    const retreating = this.atb;
    this.setPhase(retreating ? 'retreating' : 'saving', retreating ? '원정 철수 중' : '게임 저장 중');
    this.frame.contentWindow.postMessage({ type: 'noxac-original-return-request' }, window.location.origin);
    this.deadline = setTimeout(() => {
      if (retreating) void this.returnToInn();
      else this.setPhase('save-failed', '저장 확인을 받지 못했습니다');
    }, retreating ? RETREAT_TIMEOUT : SAVE_TIMEOUT);
  }

  clearFrame() {
    clearTimeout(this.deadline);
    this.deadline = null;
    if (this.phase === 'retreating') this.frame?.contentDocument?.defaultView?.spd?.prepareForDetach?.();
    this.frame?.remove();
    this.frame = null;
  }

  fail(label) {
    this.clearFrame();
    this.setPhase('failed', label);
  }

  async returnToInn() {
    if (this.phase === 'returning') return;
    this.attempt?.abort();
    this.clearFrame();
    this.setPhase('returning', '여관으로 돌아가는 중');
    await this.consumingPreparation;
    if (await this.suspending) await this.resume();
    this.suspending = this.attempt = this.phase = this.launchProfile = null;
    this.screen.hidden = true;
    this.shell.hidden = false;
    this.onChange(false);
  }
}
