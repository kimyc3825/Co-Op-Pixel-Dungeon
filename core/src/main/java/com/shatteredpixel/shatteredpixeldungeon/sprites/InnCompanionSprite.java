// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.PointF;

public class InnCompanionSprite extends MobSprite {

	public InnCompanionSprite() {
		texture(Assets.Sprites.ROGUE);
		HeroSprite.setupAnimations(this, new TextureFilm(texture, 12, 15));
		play(idle);
	}

	@Override
	public void bloodBurstA(PointF from, int damage) {
		// Human characters use the same bloodless hit effects as the player.
	}
}
