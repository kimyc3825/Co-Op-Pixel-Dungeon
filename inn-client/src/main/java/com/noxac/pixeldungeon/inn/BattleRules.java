// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

final class BattleRules {
    static final int ENERGY_CAP = 10;
    static final int MOVE_COST = 1;
    static final int ATTACK_COST = 1;
    static final int SHOOT_COST = 2;
    static final int SHOOT_RANGE = 5;
    static final int SHOOT_DAMAGE = 3;
    static final int PROJECTILE_STEP_MS = 100;
    static final int EAT_COST = 3;
    static final int MOVE_COOLDOWN_MS = 180;
    static final int ATTACK_COOLDOWN_MS = 800;
    static final int REGEN_DELAY_MS = 500;
    static final int REGEN_POINT_MS = 800;
    static final int RATION_HEAL = 6;

    private BattleRules() {}

    static int cost(String type, boolean inCombat) {
        return switch (type) {
            case "move" -> inCombat ? MOVE_COST : 0;
            case "attack" -> ATTACK_COST;
            case "shoot" -> SHOOT_COST;
            case "eat" -> EAT_COST;
            default -> throw new IllegalArgumentException("Unknown battle action");
        };
    }

    static double energyAt(InnApi.Combatant actor, long snapshotNow, long displayNow) {
        long elapsed = Math.max(0, displayNow - Math.max(snapshotNow, actor.energyAt()));
        return Math.min(ENERGY_CAP, actor.energy() + elapsed / (double) REGEN_POINT_MS);
    }

    static boolean lacksEnergy(InnApi.Combatant actor, String type, long snapshotNow, long displayNow, boolean inCombat) {
        return energyAt(actor, snapshotNow, displayNow) + 1e-8 < cost(type, inCombat);
    }

    static long readyAt(InnApi.Combatant actor, String type, long snapshotNow, boolean inCombat) {
        long gate = switch (type) {
            case "move" -> actor.moveReadyAt();
            case "attack", "shoot" -> actor.attackReadyAt();
            default -> 0;
        };
        double missing = cost(type, inCombat) - actor.energy();
        if (missing <= 0) return gate;
        // The host stores integer units. Ignore floating-point roundoff when converting back to ms.
        long refill = Math.max(snapshotNow, actor.energyAt()) + (long) Math.ceil(missing * REGEN_POINT_MS - 0.000001);
        return Math.max(gate, refill);
    }
}
