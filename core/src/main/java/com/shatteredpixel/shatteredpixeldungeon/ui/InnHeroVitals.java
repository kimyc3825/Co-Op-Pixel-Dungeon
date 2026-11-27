// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Group;

/** Compact world-space health bar attached to the expedition hero. */
public final class InnHeroVitals extends Group {
    private static final float BAR_HEIGHT = 1f;

    private final Hero hero;
    private final HealthBar health = new HealthBar();

    public InnHeroVitals(Hero hero) {
        this.hero = hero;
        visible = false;
        add(health);
    }

    @Override
    public void update() {
        CharSprite sprite = hero.sprite;
        visible = hero.isAlive() && hero.isActive() && sprite != null && sprite.isVisible();
        if (!visible) return;

        Camera camera = sprite.camera();
        float left = PixelScene.align(camera, sprite.x);
        float top = PixelScene.align(camera, sprite.y + sprite.height() + 1);
        health.setRect(left, top, sprite.width(), BAR_HEIGHT);
        health.level(hero);
        super.update();
    }
}
