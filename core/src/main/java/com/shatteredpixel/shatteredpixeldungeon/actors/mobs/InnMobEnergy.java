// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs;

import com.watabou.utils.Bundle;

/** A normal monster's action budget on the original actor scheduler's clock. */
final class InnMobEnergy {
    static final float CAPACITY = 6f;
    static final float ATTACK_COST = 1f;
    private static final float EXHAUSTED_RECOVERY = 3f;
    private static final float REGEN_DELAY = 0.5f;
    private static final float SECONDS_PER_POINT = 0.8f;
    private static final float EPSILON = 0.0001f;
    private static final String ENERGY = "inn_mob_energy";
    private static final String REGEN = "inn_mob_regen";
    private static final String REST = "inn_mob_rest";
    private static final String DEPLETED = "inn_mob_depleted";

    final float moveCost;
    private float balance = CAPACITY;
    private float regenAt;
    private float restUntil;
    private boolean depleted;

    InnMobEnergy(float moveCost) {
        this.moveCost = moveCost;
    }

    synchronized float energy(float now) {
        return Math.min(CAPACITY, balance + Math.max(0f, now - regenAt) / SECONDS_PER_POINT);
    }

    synchronized boolean exhausted(float now) {
        return now < restUntil || depleted && energy(now) + EPSILON < EXHAUSTED_RECOVERY;
    }

    synchronized float waitFor(float cost, float now) {
        float required = depleted ? Math.max(cost, EXHAUSTED_RECOVERY) : cost;
        float missing = required - energy(now);
        if (missing <= EPSILON) return 0f;
        float wait = Math.max(0f, regenAt - now) + missing * SECONDS_PER_POINT;
        restUntil = now + wait;
        return wait;
    }

    synchronized void spend(float cost, float now) {
        float remaining = energy(now) - cost;
        depleted = remaining <= EPSILON;
        balance = depleted ? 0f : remaining;
        regenAt = now + REGEN_DELAY;
        restUntil = now;
    }

    synchronized void fixTime(float decrement) {
        regenAt -= decrement;
        restUntil -= decrement;
    }

    synchronized void store(Bundle bundle, float now) {
        bundle.put(ENERGY, energy(now));
        bundle.put(REGEN, Math.max(0f, regenAt - now));
        bundle.put(REST, Math.max(0f, restUntil - now));
        bundle.put(DEPLETED, depleted);
    }

    synchronized void restore(Bundle bundle, float now) {
        if (!bundle.contains(ENERGY)) return;
        float stored = bundle.getFloat(ENERGY);
        float regen = bundle.getFloat(REGEN);
        float rest = bundle.getFloat(REST);
        balance = Float.isFinite(stored) ? Math.min(CAPACITY, Math.max(0f, stored)) : CAPACITY;
        regenAt = now + (Float.isFinite(regen) ? Math.min(REGEN_DELAY, Math.max(0f, regen)) : 0f);
        restUntil = now + (Float.isFinite(rest) ? Math.max(0f, rest) : 0f);
        depleted = bundle.getBoolean(DEPLETED);
    }
}
