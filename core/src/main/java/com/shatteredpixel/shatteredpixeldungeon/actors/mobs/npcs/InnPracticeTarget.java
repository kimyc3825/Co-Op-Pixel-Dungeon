// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs;

import com.shatteredpixel.shatteredpixeldungeon.InnAtb;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.PointF;

/** A stationary target which uses the original hit resolution and resets when its health reaches zero. */
public class InnPracticeTarget extends NPC {
    {
        alignment = Alignment.ENEMY;
        HP = HT = 30;
        properties.add(Property.IMMOVABLE);
        spriteClass = DummySprite.class;
    }

    @Override
    protected boolean act() {
        spend(TICK);
        return true;
    }

    @Override
    public void aggro(Char target) {
        // It is a training target, so it never attacks or pursues the hero.
    }

    @Override
    public String description() {
        return Messages.get(this, "desc");
    }

    @Override
    public void die(Object cause) {
        HP = HT;
        if (sprite != null) sprite.showStatus(CharSprite.POSITIVE, Messages.get(InnPracticeTarget.class, "reset"));
    }

    public abstract static class Station extends InnPracticeTarget {
        {
            alignment = Alignment.NEUTRAL;
            HP = HT = 1;
        }

        @Override public void damage(int damage, Object source) {}
    }

    public static final class Rations extends Station {
        { spriteClass = RationsSprite.class; }

        @Override
        public boolean interact(Char character) {
            if (!(character instanceof Hero)) return false;
            Hero hero = (Hero) character;
            Food supply = new Food();
            supply.quantity(3);
            if (supply.collect(hero.belongings.backpack)) {
                GLog.p(Messages.get(InnPracticeTarget.class, "supplied"));
            } else {
                GLog.w(Messages.get(InnPracticeTarget.class, "bag_full"));
            }
            return true;
        }
    }

    public static final class Recovery extends Station {
        { spriteClass = RecoverySprite.class; }

        @Override
        public boolean interact(Char character) {
            if (!(character instanceof Hero)) return false;
            Hero hero = (Hero) character;
            hero.HP = hero.HT;
            InnAtb.reset();
            Hunger hunger = hero.buff(Hunger.class);
            if (hunger != null) hunger.satisfy(Hunger.STARVING);
            GLog.p(Messages.get(InnPracticeTarget.class, "recovered"));
            return true;
        }
    }

    private abstract static class PracticeSprite extends CharSprite {
        PracticeSprite(int index) {
            texture("sprites/inn-training.png");
            TextureFilm frames = new TextureFilm(texture, 16, 16);
            idle = new Animation(1, true);
            idle.frames(frames, index);
            run = attack = zap = operate = die = idle;
            play(idle);
        }

        @Override public void bloodBurstA(PointF from, int damage) {}
    }

    public static final class DummySprite extends PracticeSprite { public DummySprite() { super(0); } }
    public static final class RationsSprite extends PracticeSprite { public RationsSprite() { super(1); } }
    public static final class RecoverySprite extends PracticeSprite { public RecoverySprite() { super(2); } }
}
