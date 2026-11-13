// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPracticeTarget;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.HashSet;
import java.util.Set;

/** The original ATB run's encounter status, derived from the existing enemy AI. */
public final class InnEncounter {
    private static final float SIGHT_GRACE = 0.45f;
    private static final float PURSUIT_GRACE = 2.4f;

    public enum Phase {
        EXPLORE, ALERT, ENGAGED, PURSUIT;

        public boolean costsMovement() {
            return this == ENGAGED || this == PURSUIT;
        }
    }

    private final Set<Mob> knownEnemies = new HashSet<>();
    private final Set<Mob> visibleEnemies = new HashSet<>();
    private volatile Phase phase = Phase.EXPLORE;
    private Level level;
    private boolean contact;
    private boolean pursuer;
    private float sightRemaining;
    private float pursuitRemaining;

    public Phase phase() {
        return phase;
    }

    public synchronized void advance(Level currentLevel, float seconds) {
        if (changeLevel(currentLevel)) return;
        // An attack can finish on the render thread after the last enemy's scheduled turn.
        knownEnemies.removeIf(InnEncounter::noLongerHostile);
        visibleEnemies.removeIf(InnEncounter::noLongerHostile);
        if (knownEnemies.isEmpty()) {
            contact = pursuer = false;
            sightRemaining = pursuitRemaining = 0;
        }
        if (!contact) sightRemaining = Math.max(0, sightRemaining - seconds);
        if (!contact && !pursuer) pursuitRemaining = Math.max(0, pursuitRemaining - seconds);
        updatePhase();
    }

    public synchronized void observe(Hero hero, Level currentLevel, Mob[] mobs) {
        changeLevel(currentLevel);
        if (currentLevel == null || currentLevel.heroFOV == null) return;
        boolean[] fov = currentLevel.heroFOV;
        InnOriginalParty party = InnOriginalParty.current();
        InnPartyMember partner = party == null ? null : party.activeMember(currentLevel);
        contact = pursuer = false;
        visibleEnemies.clear();
        knownEnemies.removeIf(mob -> noLongerHostile(mob) || !currentLevel.mobs.contains(mob));

        for (Mob mob : mobs) {
            if (noLongerHostile(mob)) continue;
            boolean visible = mob.pos >= 0 && mob.pos < fov.length && fov[mob.pos] && mob.invisible <= 0;
            if (visible) visibleEnemies.add(mob);
            boolean tracking = (mob.state == mob.HUNTING || mob.state == mob.INVESTIGATING)
                    && (mob.isTargeting(hero) || partner != null && mob.isTargeting(partner))
                    || mob instanceof InnPracticeTarget && mob.HP < mob.HT && visible;
            if (!tracking) continue;
            boolean seesPartner = partner != null && mob.seesTarget(partner);
            if (visible || mob.seesTarget(hero) || seesPartner) knownEnemies.add(mob);
            if (knownEnemies.contains(mob)) {
                pursuer = true;
                contact |= visible || seesPartner;
            }
        }

        if (knownEnemies.isEmpty()) {
            sightRemaining = pursuitRemaining = 0;
        } else {
            if (contact) sightRemaining = SIGHT_GRACE;
            if (pursuer) pursuitRemaining = PURSUIT_GRACE;
        }
        updatePhase();
    }

    private static boolean noLongerHostile(Mob mob) {
        return !mob.isAlive() || mob.alignment != Char.Alignment.ENEMY;
    }

    private boolean changeLevel(Level currentLevel) {
        if (level == currentLevel) return false;
        level = currentLevel;
        knownEnemies.clear();
        visibleEnemies.clear();
        contact = pursuer = false;
        sightRemaining = pursuitRemaining = 0;
        phase = Phase.EXPLORE;
        return true;
    }

    private void updatePhase() {
        if (contact || sightRemaining > 0) phase = Phase.ENGAGED;
        else if (pursuer || pursuitRemaining > 0) phase = Phase.PURSUIT;
        else {
            knownEnemies.clear();
            phase = visibleEnemies.isEmpty() ? Phase.EXPLORE : Phase.ALERT;
        }
    }
}
