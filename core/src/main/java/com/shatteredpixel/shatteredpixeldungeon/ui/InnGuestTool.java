// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;

/** A toolbar button drawn from the original toolbar sheet, for the party guest's screen. */
public class InnGuestTool extends Button {
	private static final int BGCOLOR = 0x7B8073;

	private Image base;
	private Image icon;

	/** Frame of the button background and its icon in {@link Assets.Interfaces#TOOLBAR}. */
	public InnGuestTool(int x, int y, int width, int height, int iconX, int iconY) {
		super();
		hotArea.blockLevel = PointerArea.ALWAYS_BLOCK;
		base.frame(x, y, width, height);
		this.width = width;
		this.height = height;
		icon = new Image(Assets.Interfaces.TOOLBAR);
		icon.frame(iconX, iconY, 16, 16);
		add(icon);
	}

	@Override
	protected void createChildren() {
		super.createChildren();
		base = new Image(Assets.Interfaces.TOOLBAR);
		add(base);
	}

	@Override
	protected void layout() {
		super.layout();
		base.x = x;
		base.y = y;
		if (icon != null) {
			icon.x = x + (width - icon.width()) / 2f;
			icon.y = y + (height - icon.height()) / 2f;
		}
	}

	@Override
	protected void onPointerDown() {
		base.brightness(1.4f);
	}

	@Override
	protected void onPointerUp() {
		if (active) base.resetColor();
		else base.tint(BGCOLOR, 0.7f);
	}

	public void enable(boolean value) {
		if (value != active) {
			icon.alpha(value ? 1f : 0.4f);
			active = value;
			onPointerUp();
		}
	}
}
