// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

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
    private static volatile AtbRun activeAtbRun;

    static final class AtbRun {
        private final int slot;

        private AtbRun(int slot) {
            this.slot = slot;
        }
    }

    private InnAdventure() {}

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory) {
        configure(name, job, hairColor, outfitColor, hairStyle, accessory, false);
    }

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory, boolean realTime) {
        HeroClass selected = HeroClass.WARRIOR;
        for (HeroClass heroClass : HeroClass.values()) {
            if (heroClass.name().equalsIgnoreCase(job)) {
                selected = heroClass;
                break;
            }
        }
        profile = new Profile(name, selected, hairColor, outfitColor, hairStyle, accessory);
        InnAtb.reset();
        activeAtbRun = null;
        atb = realTime;
    }

    public static Profile profile() {
        return profile;
    }

    public static boolean atb() {
        return atb;
    }

    /** Allocate the temporary checkpoint slot before generating the new expedition. */
    public static int startAtbRun() {
        int slot = GamesInProgress.firstSlot();
        if (!GamesInProgress.isAtbSlot(slot)) throw new IllegalStateException("ATB adventure is not active");
        Dungeon.deleteGame(slot, true);
        activeAtbRun = new AtbRun(slot);
        return slot;
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
        AtbRun run = activeAtbRun;
        if (run == null) return;
        activeAtbRun = null;
        Actor.updateAtb(0, true);
        Dungeon.deleteGame(run.slot, true);
    }

    public static void clear() {
        activeAtbRun = null;
        atb = false;
        profile = null;
    }
}
