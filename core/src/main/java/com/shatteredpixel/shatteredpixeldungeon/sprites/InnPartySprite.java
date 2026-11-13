// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.PointF;

public final class InnPartySprite extends MobSprite {
    public InnPartySprite(InnAdventure.Profile profile) {
        HeroClass job = profile == null ? HeroClass.WARRIOR : profile.heroClass();
        texture(InnHeroAppearance.texture(job, profile));
        HeroSprite.setupAnimations(this, new TextureFilm(texture, 12, 15));
        visibleOutOfFFOV = true;
        idle();
    }

    @Override
    public void bloodBurstA(PointF from, int damage) {
        // Use the original hero's bloodless hit effects.
    }
}
