// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

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

    private InnAdventure() {}

    public static void configure(String name, String job, String hairColor, String outfitColor,
                                 String hairStyle, String accessory) {
        HeroClass selected = HeroClass.WARRIOR;
        for (HeroClass heroClass : HeroClass.values()) {
            if (heroClass.name().equalsIgnoreCase(job)) {
                selected = heroClass;
                break;
            }
        }
        profile = new Profile(name, selected, hairColor, outfitColor, hairStyle, accessory);
    }

    public static Profile profile() {
        return profile;
    }

    public static void clear() {
        profile = null;
    }
}
