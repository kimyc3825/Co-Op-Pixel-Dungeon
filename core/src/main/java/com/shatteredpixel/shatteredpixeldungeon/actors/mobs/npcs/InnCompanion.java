// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Stasis;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.InnCompanionSprite;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

public class InnCompanion extends DirectableAlly {

	{
		spriteClass = InnCompanionSprite.class;
		HP = HT = 28;
	}

	// The existing ally transfer and level save handle a companion already in the run.
	public static void ensurePresent(Level level, int heroPos) {
		if (Stasis.getStasisAlly() instanceof InnCompanion) return;
		for (Mob mob : level.mobs) {
			if (mob instanceof InnCompanion) return;
		}

		int cell = spawnCell(level, heroPos);
		if (cell == -1) return;
		InnCompanion companion = new InnCompanion();
		companion.pos = cell;
		companion.HP = companion.HT = 25 + 3 * Dungeon.hero.lvl;
		level.mobs.add(companion);
	}

	private static int spawnCell(Level level, int heroPos) {
		for (int offset : PathFinder.NEIGHBOURS8) {
			int cell = heroPos + offset;
			if (canSpawn(level, cell)) return cell;
		}

		PathFinder.buildDistanceMap(heroPos, level.passable);
		int nearest = -1;
		int nearestDistance = Integer.MAX_VALUE;
		for (int cell = 0; cell < level.length(); cell++) {
			int distance = PathFinder.distance[cell];
			if (distance > 0 && distance < nearestDistance && canSpawn(level, cell)) {
				nearest = cell;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private static boolean canSpawn(Level level, int cell) {
		return level.insideMap(cell) && level.passable[cell] && !level.avoid[cell]
				&& level.findMob(cell) == null && level.traps.get(cell) == null;
	}

	@Override
	protected boolean act() {
		defenseSkill = 4 + Dungeon.hero.lvl;
		attacksAutomatically = Dungeon.level.distance(pos, Dungeon.hero.pos) <= 5;
		if (!attacksAutomatically && state == HUNTING) followHero();
		return super.act();
	}

	@Override
	public float speed() {
		float speed = super.speed();
		if (state == WANDERING && Dungeon.level.distance(pos, Dungeon.hero.pos) > 2) {
			speed *= 1.5f;
		}
		return speed;
	}

	@Override
	public int attackSkill(Char target) {
		return 9 + Dungeon.hero.lvl;
	}

	@Override
	public int damageRoll() {
		int level = Dungeon.hero.lvl;
		return Random.NormalIntRange(2 + level / 2, 5 + level);
	}
}
