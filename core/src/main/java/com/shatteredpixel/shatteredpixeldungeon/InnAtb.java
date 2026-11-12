// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.watabou.utils.Bundle;

/** The player budget on the original dungeon's real-time clock. */
public final class InnAtb {
    public static final float CAPACITY = 10;
    private static final float REGEN_DELAY = 0.5f;
    private static final float SECONDS_PER_POINT = 0.8f;
    private static final String ENERGY = "inn_atb_energy";
    private static final String DELAY = "inn_atb_delay";

    private static float energy = CAPACITY;
    private static float delay;

    private InnAtb() {}

    public static synchronized void reset() {
        energy = CAPACITY;
        delay = 0;
    }

    public static synchronized void advance(float elapsedSeconds) {
        if (elapsedSeconds <= 0) return;
        float regenTime = Math.max(0, elapsedSeconds - delay);
        delay = Math.max(0, delay - elapsedSeconds);
        energy = Math.min(CAPACITY, energy + regenTime / SECONDS_PER_POINT);
    }

    public static synchronized float energy() {
        return Math.max(0, energy);
    }

    public static synchronized boolean canSpend(float cost) {
        return energy + 0.0001f >= cost;
    }

    public static synchronized void spend(float cost) {
        if (!Float.isFinite(cost) || cost == 0) return;
        // Some original abilities commit atomically before their final cost is known.
        energy = Math.max(-CAPACITY, Math.min(CAPACITY, energy - cost));
        if (cost > 0) delay = REGEN_DELAY;
    }

    static synchronized void store(Bundle bundle) {
        bundle.put(ENERGY, energy);
        bundle.put(DELAY, delay);
    }

    static synchronized void restore(Bundle bundle) {
        reset();
        if (bundle.contains(ENERGY)) {
            float stored = bundle.getFloat(ENERGY);
            if (Float.isFinite(stored)) energy = Math.max(-CAPACITY, Math.min(CAPACITY, stored));
        }
        if (bundle.contains(DELAY)) {
            float stored = bundle.getFloat(DELAY);
            if (Float.isFinite(stored)) delay = Math.max(0, Math.min(REGEN_DELAY, stored));
        }
    }
}
