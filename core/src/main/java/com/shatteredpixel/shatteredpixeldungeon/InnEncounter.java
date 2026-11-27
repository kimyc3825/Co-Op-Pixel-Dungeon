// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.function.Supplier;

/** Per-player engagement used by the party turn clock to decide whether an idle player gets a grace countdown. */
public final class InnEncounter {

	private InnEncounter() {}

	/**
	 * A player is engaged while alive and a living hostile mob either hunts them, or is awake (not sleeping
	 * and not passive) and visible inside their field of view. The field of view is only requested when an
	 * awake hostile mob could be in view, so callers can compute it fresh.
	 */
	public static boolean engaged(Char player, Supplier<boolean[]> fieldOfView, Level level) {
		if (player == null || !player.isAlive() || level == null) return false;
		boolean[] fov = null;
		boolean fovKnown = false;
		for (Mob mob : level.mobs.toArray(new Mob[0])) {
			if (mob == player || !mob.isAlive() || mob.alignment != Char.Alignment.ENEMY) continue;
			if (mob.state == mob.HUNTING && mob.isTargeting(player)) return true;
			if (mob.state == mob.SLEEPING || mob.state == mob.PASSIVE || mob.invisible > 0) continue;
			if (!fovKnown) {
				fov = fieldOfView.get();
				fovKnown = true;
			}
			if (fov != null && mob.pos >= 0 && mob.pos < fov.length && fov[mob.pos]) return true;
		}
		return false;
	}
}
