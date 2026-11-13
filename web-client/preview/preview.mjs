// SPDX-License-Identifier: GPL-3.0-or-later
import { Board } from '../play/board.mjs';
import { loadInnLayout } from '../play/inn-layout.mjs';

const canvas = document.getElementById('inn');
try {
  const layout = await loadInnLayout();
  const board = new Board(canvas, { fitMap: true });
  board.set(layout, false);
  canvas.hidden = false;
  function render(now) {
    board.draw(now);
    requestAnimationFrame(render);
  }
  requestAnimationFrame(render);
} catch (error) {
  const status = document.getElementById('status');
  status.textContent = '여관 지도를 불러오지 못했습니다.';
  status.hidden = false;
  console.error(error);
}
