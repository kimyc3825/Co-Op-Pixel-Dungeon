// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.ui.Component;

/**
 * The party guest's status pane, drawn like the original small status pane: portrait, health bar, level, and
 * below the bar the floor and the party's gold. The guest has no experience of its own, so there is no
 * experience bar.
 */
public final class InnGuestStatus extends Component {
	public static final int WIDTH = 82;
	public static final int HEIGHT = 38;

	private final HeroClass heroClass;
	private NinePatch bg;
	private Image avatar;
	private Image hp;
	private BitmapText hpText;
	private BitmapText level;
	private RenderedTextBlock info;
	private String shownInfo = "";
	private int shownHp = -1, shownMax = -1, shownLevel = -1;

	public InnGuestStatus(HeroClass heroClass) {
		this.heroClass = heroClass;
		avatar = HeroSprite.innAvatar(heroClass, 1);
		add(avatar);
		hp = new Image(Assets.Interfaces.STATUS, 0, 40, 50, 4);
		add(hp);
		hpText = new BitmapText(PixelScene.pixelFont);
		hpText.alpha(0.6f);
		add(hpText);
		level = new BitmapText(PixelScene.pixelFont);
		level.hardlight(0xFFFFAA);
		add(level);
		info = PixelScene.renderTextBlock(5);
		add(info);
	}

	@Override
	protected void createChildren() {
		bg = new NinePatch(Assets.Interfaces.STATUS, 0, 0, 82, 38, 32, 0, 5, 0);
		add(bg);
	}

	/** Shows the guest's health, the party level and a short line such as the floor and gold. */
	public void show(int health, int max, int partyLevel, String line) {
		if (health != shownHp || max != shownMax) {
			shownHp = health;
			shownMax = max;
			hp.scale.x = max <= 0 ? 0 : Math.max(0f, Math.min(1f, health / (float) max));
			hpText.text(health + "/" + max);
			hpText.measure();
			avatar.tint(0x000000, health <= 0 ? 0.5f : 0f);
		}
		if (partyLevel != shownLevel) {
			shownLevel = partyLevel;
			level.text(Integer.toString(partyLevel));
			level.measure();
		}
		if (!line.equals(shownInfo)) {
			shownInfo = line;
			info.text(line);
		}
		layout();
	}

	@Override
	protected void layout() {
		width = WIDTH;
		height = HEIGHT;
		bg.x = x;
		bg.y = y;
		bg.size(WIDTH, bg.height);
		avatar.x = x - avatar.width / 2f + 15;
		avatar.y = y - avatar.height / 2f + 16;
		PixelScene.align(avatar);
		hp.x = x + 30;
		hp.y = y + 2;
		hpText.scale.set(PixelScene.align(0.5f));
		hpText.x = hp.x + 1;
		hpText.y = hp.y + (hp.height - (hpText.baseLine() + hpText.scale.y)) / 2f - 0.001f;
		PixelScene.align(hpText);
		level.x = x + 27.5f - level.width() / 2f;
		level.y = y + 28.0f - level.baseLine() / 2f;
		PixelScene.align(level);
		info.setPos(x + 31, y + 9);
		PixelScene.align(info);
	}
}
