// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.watabou.utils.Bundle;

/** The player budget on the original dungeon's real-time clock. */
public final class InnAtb {
    public static final float CAPACITY = 10;
    public static final float EXHAUSTED_RECOVERY = 3f;
    private static final float REGEN_DELAY = 0.5f;
    private static final float SECONDS_PER_POINT = 0.8f;
    private static final float EPSILON = 0.0001f;
    private static final String ENERGY = "inn_atb_energy";
    private static final String DELAY = "inn_atb_delay";
    private static final String EXHAUSTED = "inn_atb_exhausted";

    private static final Budget player = new Budget();
    private static volatile boolean slowTime;

    private InnAtb() {}

    public static boolean slowTime() {
        return slowTime;
    }

    public static void setSlowTime(boolean enabled) {
        slowTime = enabled;
    }

    public static void toggleSlowTime() {
        slowTime = !slowTime;
    }

    public static float gameElapsed(float elapsed) {
        return InnAdventure.atb() && slowTime ? elapsed * 0.5f : elapsed;
    }

    public static void reset() {
        player.reset();
    }

    public static void advance(float elapsedSeconds) {
        player.advance(elapsedSeconds);
    }

    public static float energy() {
        return player.energy();
    }

    public static boolean exhausted() {
        return player.exhausted();
    }

    public static boolean canSpend(float cost) {
        return player.canSpend(cost);
    }

    public static void spend(float cost) {
        player.spend(cost);
    }

    static void store(Bundle bundle) {
        player.store(bundle);
    }

    static void restore(Bundle bundle) {
        player.restore(bundle);
    }

    /** One independent budget for each player in the original dungeon. */
    public static final class Budget {
        private float energy = CAPACITY;
        private float delay;
        private boolean exhausted;

        private synchronized void reset() {
            energy = CAPACITY;
            delay = 0f;
            exhausted = false;
        }

        public synchronized void advance(float elapsedSeconds) {
            if (elapsedSeconds <= 0) return;
            float regenTime = Math.max(0, elapsedSeconds - delay);
            delay = Math.max(0, delay - elapsedSeconds);
            energy = Math.min(CAPACITY, energy + regenTime / SECONDS_PER_POINT);
            unlockIfRecovered();
        }

        public synchronized float energy() {
            return Math.max(0, energy);
        }

        public synchronized boolean exhausted() {
            return exhausted;
        }

        public synchronized boolean canSpend(float cost) {
            return !exhausted && energy + EPSILON >= cost;
        }

        public synchronized void spend(float cost) {
            if (!Float.isFinite(cost) || cost == 0) return;
            // Some original abilities commit atomically before their final cost is known.
            energy = Math.max(-CAPACITY, Math.min(CAPACITY, energy - cost));
            if (cost > 0) {
                delay = REGEN_DELAY;
                if (energy <= EPSILON) {
                    energy = Math.min(0f, energy);
                    exhausted = true;
                }
            } else {
                unlockIfRecovered();
            }
        }

        private void unlockIfRecovered() {
            if (exhausted && energy + EPSILON >= EXHAUSTED_RECOVERY) exhausted = false;
        }

        private synchronized void store(Bundle bundle) {
            bundle.put(ENERGY, energy);
            bundle.put(DELAY, delay);
            bundle.put(EXHAUSTED, exhausted);
        }

        private synchronized void restore(Bundle bundle) {
            reset();
            if (bundle.contains(ENERGY)) {
                float stored = bundle.getFloat(ENERGY);
                if (Float.isFinite(stored)) energy = Math.max(-CAPACITY, Math.min(CAPACITY, stored));
                exhausted = energy <= EPSILON || bundle.getBoolean(EXHAUSTED);
                unlockIfRecovered();
            }
            if (bundle.contains(DELAY)) {
                float stored = bundle.getFloat(DELAY);
                if (Float.isFinite(stored)) delay = Math.max(0, Math.min(REGEN_DELAY, stored));
            }
        }
    }
}
