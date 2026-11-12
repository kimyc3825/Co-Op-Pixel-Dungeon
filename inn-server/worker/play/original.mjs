// SPDX-License-Identifier: GPL-3.0-or-later
const MANIFEST = '/play/original/manifest.json';
const BOOT_TIMEOUT = 45_000;
const SAVE_TIMEOUT = 8_000;

export class OriginalGame {
  constructor({ suspend, resume, canLaunch, onChange, tips }) {
    this.suspend = suspend;
    this.resume = resume;
    this.canLaunch = canLaunch;
    this.onChange = onChange;
    this.tips = tips;
    this.shell = document.getElementById('play-shell');
    this.screen = document.getElementById('original-screen');
    this.body = document.getElementById('original-body');
    this.loading = document.getElementById('original-loading');
    this.ember = document.getElementById('original-ember');
    this.label = document.getElementById('original-phase');
    this.tip = document.getElementById('original-tip');
    this.retry = document.getElementById('original-retry');
    this.back = document.getElementById('original-return');
    this.tipIndex = 0;
    this.tipTimer = null;
    this.frame = this.attempt = this.suspending = this.phase = null;
    this.deadline = null;
    document.getElementById('original-launch').addEventListener('click', () => this.start());
    this.back.addEventListener('click', () => {
      if (this.phase === 'ready') this.requestSave();
      else void this.returnToInn();
    });
    this.retry.addEventListener('click', () => {
      if (this.phase === 'save-failed') this.requestSave();
      else if (this.phase === 'failed') void this.load();
    });
    window.addEventListener('message', (event) => this.onMessage(event));
  }

  get active() { return !this.screen.hidden; }

  start() {
    if (this.active || !this.canLaunch()) return;
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
      const response = await fetch(MANIFEST, {
        cache: 'no-store',
        signal: AbortSignal.any([attempt.signal, AbortSignal.timeout(6000)]),
      });
      if (!response.ok) throw new Error('Original game manifest unavailable');
      const { version, entry } = await response.json();
      if (!/^[0-9a-f]{64}$/.test(version) || entry !== `/play/original/runtime/${version}/index.html`) {
        throw new Error('Invalid original game manifest');
      }
      if (attempt.signal.aborted) return;
      this.setPhase('loading', '원본 게임 불러오는 중');
      const frame = document.createElement('iframe');
      frame.title = '원본 녹픽던 게임';
      frame.allow = 'fullscreen; autoplay';
      frame.referrerPolicy = 'no-referrer';
      frame.tabIndex = -1;
      frame.src = entry;
      this.frame = frame;
      this.body.prepend(frame);
      this.deadline = setTimeout(() => this.fail('원본 게임을 시작하지 못했습니다'), BOOT_TIMEOUT);
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
    this.back.textContent = phase === 'save-failed' ? '저장 확인 없이 돌아가기' : '여관으로';
    this.back.disabled = phase === 'saving' || phase === 'returning';
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
    if (event.data?.type === 'noxac-original-ready' && this.phase === 'loading') {
      clearTimeout(this.deadline);
      this.setPhase('ready');
      this.frame.focus();
    } else if (event.data?.type === 'noxac-original-error') {
      this.fail('원본 게임이 중단되었습니다');
    } else if (event.data?.type === 'noxac-original-exit') {
      void this.returnToInn();
    }
  }

  requestSave() {
    this.setPhase('saving', '게임 저장 중');
    this.frame.contentWindow.postMessage({ type: 'noxac-original-return-request' }, window.location.origin);
    this.deadline = setTimeout(() => this.setPhase('save-failed', '저장 확인을 받지 못했습니다'), SAVE_TIMEOUT);
  }

  clearFrame() {
    clearTimeout(this.deadline);
    this.deadline = null;
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
    if (await this.suspending) await this.resume();
    this.suspending = this.attempt = this.phase = null;
    this.screen.hidden = true;
    this.shell.hidden = false;
    this.onChange(false);
  }
}
