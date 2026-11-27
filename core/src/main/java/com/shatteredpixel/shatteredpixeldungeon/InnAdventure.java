// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

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
    private static volatile boolean expedition;
    private static volatile boolean practice;
    private static volatile ExpeditionRun activeRun;
    private static Preparation nextPreparation;

    private static final class Preparation {
        private final int hp;
        private final int food;

        private Preparation(int hp, int food) {
            this.hp = Math.abs(hp) == 2 ? hp : 0;
            this.food = Math.abs(food) == 1 ? food : 0;
        }
    }

    static final class ExpeditionRun {
        private final int slot;
        private Preparation preparation;

        private ExpeditionRun(int slot, Preparation preparation) {
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
                                 String hairStyle, String accessory, boolean expedition) {
        configure(name, job, hairColor, outfitColor, hairStyle, accessory, expedition, 0, 0);
    }

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory, boolean expedition, int hp, int food) {
        InnOriginalParty.clear();
        profile = makeProfile(name, job, hairColor, outfitColor, hairStyle, accessory);
        activeRun = null;
        practice = false;
        nextPreparation = expedition ? new Preparation(hp, food) : null;
        InnAdventure.expedition = expedition;
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

    /** True for an inn expedition: temporary save slot, retreat ends the run. */
    public static boolean expedition() {
        return expedition;
    }

    public static void enablePractice() {
        if (!expedition) throw new IllegalStateException("Practice requires an inn expedition");
        practice = true;
        nextPreparation = null;
    }

    public static boolean practice() {
        return practice;
    }

    /** Allocate the temporary checkpoint slot before generating the new expedition. */
    public static int startExpedition() {
        int slot = GamesInProgress.firstSlot();
        if (!GamesInProgress.isExpeditionSlot(slot)) throw new IllegalStateException("Inn expedition is not active");
        if (!practice) {
            Dungeon.deleteGame(slot, true);
            activeRun = new ExpeditionRun(slot, nextPreparation);
        }
        nextPreparation = null;
        return slot;
    }

    static void applyPreparation(Hero hero) {
        ExpeditionRun run = expeditionRunForSlot(GamesInProgress.curSlot);
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

    static ExpeditionRun expeditionRunForSlot(int slot) {
        ExpeditionRun run = activeRun;
        return run != null && run.slot == slot ? run : null;
    }

    static boolean ownsExpeditionRun(ExpeditionRun run) {
        return run != null && activeRun == run;
    }

    /** Retire this expedition before returning to the inn. */
    public static void retreat() {
        InnOriginalParty.finish();
        ExpeditionRun run = activeRun;
        if (run == null) return;
        activeRun = null;
        Dungeon.deleteGame(run.slot, true);
    }

    public static void clear() {
        InnOriginalParty.clear();
        activeRun = null;
        nextPreparation = null;
        expedition = false;
        practice = false;
        profile = null;
    }
}
