// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;

/** The inn profile for the current original-engine adventure. */
public final class InnAdventure {
    public static final class Profile {
        private final String name;
        private final HeroClass heroClass;
        private final String hairColor;
        private final String outfitColor;
        private final String hairStyle;
        private final String accessory;

        private Profile(String name, HeroClass heroClass, String hairColor, String outfitColor,
                        String hairStyle, String accessory) {
            this.name = name;
            this.heroClass = heroClass;
            this.hairColor = hairColor;
            this.outfitColor = outfitColor;
            this.hairStyle = hairStyle;
            this.accessory = accessory;
        }

        public String name() { return name; }
        public HeroClass heroClass() { return heroClass; }
        public String hairColor() { return hairColor; }
        public String outfitColor() { return outfitColor; }
        public String hairStyle() { return hairStyle; }
        public String accessory() { return accessory; }
    }

    private static Profile profile;
    private static volatile boolean atb;
    private static volatile boolean practice;
    private static volatile AtbRun activeAtbRun;
    private static Preparation nextPreparation;

    private static final class Preparation {
        private final int hp;
        private final int food;

        private Preparation(int hp, int food) {
            this.hp = Math.abs(hp) == 2 ? hp : 0;
            this.food = Math.abs(food) == 1 ? food : 0;
        }
    }

    static final class AtbRun {
        private final int slot;
        private Preparation preparation;

        private AtbRun(int slot, Preparation preparation) {
            this.slot = slot;
            this.preparation = preparation;
        }

        private synchronized Preparation takePreparation() {
            Preparation result = preparation;
            preparation = null;
            return result;
        }
    }

    private InnAdventure() {}

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory) {
        configure(name, job, hairColor, outfitColor, hairStyle, accessory, false);
    }

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory, boolean realTime) {
        configure(name, job, hairColor, outfitColor, hairStyle, accessory, realTime, 0, 0);
    }

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory, boolean realTime, int hp, int food) {
        InnOriginalParty.clear();
        profile = makeProfile(name, job, hairColor, outfitColor, hairStyle, accessory);
        InnAtb.reset();
        activeAtbRun = null;
        practice = false;
        nextPreparation = realTime ? new Preparation(hp, food) : null;
        atb = realTime;
    }

    public static Profile makeProfile(String name, String job, String hairColor, String outfitColor,
                                      String hairStyle, String accessory) {
        HeroClass selected = HeroClass.WARRIOR;
        for (HeroClass heroClass : HeroClass.values()) {
            if (heroClass.name().equalsIgnoreCase(job)) {
                selected = heroClass;
                break;
            }
        }
        return new Profile(name, selected, hairColor, outfitColor, hairStyle, accessory);
    }

    public static Profile profile() {
        return profile;
    }

    public static boolean atb() {
        return atb;
    }

    public static void enablePractice() {
        if (!atb) throw new IllegalStateException("Practice requires an ATB adventure");
        practice = true;
        nextPreparation = null;
    }

    public static boolean practice() {
        return practice;
    }

    /** Allocate the temporary checkpoint slot before generating the new expedition. */
    public static int startAtbRun() {
        int slot = GamesInProgress.firstSlot();
        if (!GamesInProgress.isAtbSlot(slot)) throw new IllegalStateException("ATB adventure is not active");
        if (!practice) {
            Dungeon.deleteGame(slot, true);
            activeAtbRun = new AtbRun(slot, nextPreparation);
        }
        nextPreparation = null;
        return slot;
    }

    static void applyPreparation(Hero hero) {
        AtbRun run = atbRunForSlot(GamesInProgress.curSlot);
        if (run == null) return;
        Preparation preparation = run.takePreparation();
        if (preparation == null) return;

        if (preparation.hp != 0) {
            hero.HTBoost += preparation.hp;
            hero.updateHT(true);
        }
        if (preparation.food > 0) {
            Food ration = new Food();
            if (!Challenges.isItemBlocked(ration)) ration.collect(hero.belongings.backpack);
        } else if (preparation.food < 0) {
            Food ration = hero.belongings.getItem(Food.class);
            if (ration != null) ration.detach(hero.belongings.backpack);
        }
    }

    static AtbRun atbRunForSlot(int slot) {
        AtbRun run = activeAtbRun;
        return run != null && run.slot == slot ? run : null;
    }

    static boolean ownsAtbRun(AtbRun run) {
        return run != null && activeAtbRun == run;
    }

    /** Retire this expedition before returning to the inn. */
    public static void retreat() {
        InnOriginalParty.finish();
        AtbRun run = activeAtbRun;
        if (run == null) return;
        activeAtbRun = null;
        Actor.updateAtb(0, true);
        Dungeon.deleteGame(run.slot, true);
    }

    public static void clear() {
        InnOriginalParty.clear();
        activeAtbRun = null;
        nextPreparation = null;
        atb = false;
        practice = false;
        profile = null;
    }
}
