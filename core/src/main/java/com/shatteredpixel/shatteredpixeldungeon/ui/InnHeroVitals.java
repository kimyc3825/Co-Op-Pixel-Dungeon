// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.InnAtb;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Group;

/** Compact world-space meters attached to the original ATB player. */
public final class InnHeroVitals extends Group {
    private static final float BAR_HEIGHT = 1f;
    private static final float TRACK_BRIGHTNESS = 0.35f;

    private final Hero hero;
    private final HealthBar health = new HealthBar();
    private final ColorBlock energyTrack = new ColorBlock(1, 1, 0xFFFFFFFF);
    private final ColorBlock energyFill = new ColorBlock(1, 1, 0xFFFFFFFF);
    private int lastEnergyColor = -1;

    public InnHeroVitals(Hero hero) {
        this.hero = hero;
        visible = false;
        add(health);
        add(energyTrack);
        add(energyFill);
    }

    @Override
    public void update() {
        CharSprite sprite = hero.sprite;
        visible = hero.isAlive() && hero.isActive() && sprite != null && sprite.isVisible();
        if (!visible) return;

        Camera camera = sprite.camera();
        float width = sprite.width();
        float left = PixelScene.align(camera, sprite.x);
        float top = PixelScene.align(camera, sprite.y + sprite.height() + 1);
        health.setRect(left, top, width, BAR_HEIGHT);
        health.level(hero);

        float fraction = InnAtb.energy() / InnAtb.CAPACITY;
        int color = InnAtbMeter.colorForFraction(fraction);
        if (lastEnergyColor != color) {
            lastEnergyColor = color;
            energyFill.hardlight(color);
            energyTrack.hardlight(((color >> 16) & 255) / 255f * TRACK_BRIGHTNESS,
                    ((color >> 8) & 255) / 255f * TRACK_BRIGHTNESS,
                    (color & 255) / 255f * TRACK_BRIGHTNESS);
        }
        energyTrack.x = energyFill.x = left;
        energyTrack.y = energyFill.y = top + BAR_HEIGHT + 1;
        energyTrack.size(width, BAR_HEIGHT);
        energyFill.size(width * fraction, BAR_HEIGHT);
        super.update();
    }
}
