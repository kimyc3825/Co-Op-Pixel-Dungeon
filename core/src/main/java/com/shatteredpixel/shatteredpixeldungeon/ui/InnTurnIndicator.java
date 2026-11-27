// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.ui.Component;

/** Small party HUD line shown while the world waits for one player's input with a grace countdown. */
public final class InnTurnIndicator extends Component {
	public static final int HEIGHT = 11;
	private static final int MINE = 0xFFCE78;
	private static final int PARTNER = 0xC8D7CF;

	private ColorBlock back;
	private RenderedTextBlock text;
	private String shown = "";

	@Override
	protected void createChildren() {
		add(back = new ColorBlock(1, 1, 0xC01A211D));
		add(text = PixelScene.renderTextBlock(6));
		visible = false;
	}

	/** Shows the countdown: {@code mine} when the world waits for this screen's player, otherwise for the partner. */
	public void show(boolean mine, float seconds) {
		String label = (mine ? "내 차례 · " : "동료를 기다리는 중 · ") + seconds(seconds);
		if (!label.equals(shown)) {
			shown = label;
			text.text(label);
			text.hardlight(mine ? MINE : PARTNER);
			layout();
		}
		visible = true;
	}

	public void hide() {
		visible = false;
	}

	private static String seconds(float seconds) {
		int tenths = Math.max(0, (int) Math.ceil(seconds * 10 - 0.001f));
		return (tenths / 10) + "." + (tenths % 10) + "초";
	}

	@Override
	protected void layout() {
		float w = text.width() + 6;
		back.size(w, HEIGHT);
		back.x = x + (width - w) / 2f;
		back.y = y;
		text.setPos(back.x + 3, y + (HEIGHT - text.height()) / 2f);
		PixelScene.align(text);
	}
}
