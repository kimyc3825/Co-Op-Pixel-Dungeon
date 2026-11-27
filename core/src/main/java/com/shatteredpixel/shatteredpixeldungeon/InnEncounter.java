// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

/** Per-player engagement used by the party turn clock to decide whether an idle player gets a grace countdown. */
public final class InnEncounter {

	private InnEncounter() {}

	/**
	 * A player is engaged while alive and a living hostile mob either hunts them, or is awake (not sleeping
	 * and not passive) and visible inside their field of view.
	 */
	public static boolean engaged(Char player, boolean[] fieldOfView, Level level) {
		if (player == null || !player.isAlive() || level == null) return false;
		for (Mob mob : level.mobs.toArray(new Mob[0])) {
			if (mob == player || !mob.isAlive() || mob.alignment != Char.Alignment.ENEMY) continue;
			if (mob.state == mob.HUNTING && mob.isTargeting(player)) return true;
			if (mob.state != mob.SLEEPING && mob.state != mob.PASSIVE && mob.invisible <= 0
					&& fieldOfView != null && mob.pos >= 0 && mob.pos < fieldOfView.length && fieldOfView[mob.pos]) {
				return true;
			}
		}
		return false;
	}
}
