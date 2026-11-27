// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.html;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.TurnClock;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;

/**
 * Test tooling for INDEV builds only (registered next to {@link AutoTest}): sets up party turn clock situations
 * on the host deterministically. Commands run on the render thread while the actor thread is parked.
 *
 * <ul>
 *   <li>{@code rat host|guest}: an awake rat next to that player, hunting it.</li>
 *   <li>{@code hiddenrat host|guest}: the same, invisible, so only the hunted player is engaged.</li>
 *   <li>{@code apart}: moves the guest's member to a free cell out of the host's view.</li>
 *   <li>{@code walk}: starts the host walking to the farthest explored cell it can reach.</li>
 *   <li>{@code clear}: removes every hostile mob on the floor.</li>
 *   <li>{@code heal}: restores both players' health.</li>
 * </ul>
 */
final class DebugTools {

	private DebugTools() {}

	/** Queues a command from the browser console; returns at once. */
	static String command(GameThread thread, String command, String argument) {
		thread.post(new Runnable() {
			int attempts;

			@Override
			public void run() {
				if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null) return;
				if (!Actor.parked()) {
					// The actor thread is mid-pass: try again before the next frame.
					if (++attempts < 600) thread.post(this);
					return;
				}
				WebJS.debugResult(execute(command, argument));
			}
		});
		return "queued";
	}

	private static String execute(String command, String argument) {
		switch (command) {
			case "rat":
				return spawnRat(target(argument), false);
			case "hiddenrat":
				return spawnRat(target(argument), true);
			case "clear":
				int removed = 0;
				for (Mob mob : Dungeon.level.mobs.toArray(new Mob[0])) {
					if (mob.alignment == Char.Alignment.ENEMY) {
						// Removed without dying, so no experience, kills or badges are counted.
						Dungeon.level.mobs.remove(mob);
						Actor.remove(mob);
						if (mob.sprite != null) mob.sprite.killAndErase();
						removed++;
					}
				}
				return "cleared " + removed;
			case "apart":
				return moveApart();
			case "walk":
				return walkFar();
			case "heal":
				Dungeon.hero.HP = Dungeon.hero.HT;
				Char member = TurnClock.activeMember();
				if (member != null) member.HP = member.HT;
				return "healed";
			default:
				return "unknown command " + command;
		}
	}

	private static String walkFar() {
		Level level = Dungeon.level;
		boolean[] known = new boolean[level.length()];
		for (int i = 0; i < known.length; i++) known[i] = level.passable[i] && (level.visited[i] || level.mapped[i]);
		PathFinder.buildDistanceMap(Dungeon.hero.pos, known);
		int best = -1;
		for (int cell = 0; cell < level.length(); cell++) {
			int d = PathFinder.distance[cell];
			if (d < Integer.MAX_VALUE && Actor.findChar(cell) == null && (best < 0 || d > PathFinder.distance[best])) best = cell;
		}
		if (best < 0 || PathFinder.distance[best] < 4) return "nowhere to walk";
		int steps = PathFinder.distance[best];
		if (!Dungeon.hero.handle(best)) return "walk refused";
		Dungeon.hero.next();
		return "walking to " + best + " (" + steps + " steps)";
	}

	private static String moveApart() {
		Char member = TurnClock.activeMember();
		if (member == null) return "no guest";
		Level level = Dungeon.level;
		for (int cell = 0; cell < level.length(); cell++) {
			if (level.passable[cell] && !level.pit[cell] && !level.heroFOV[cell] && Actor.findChar(cell) == null
					&& level.distance(Dungeon.hero.pos, cell) >= 8) {
				int free = 0;
				for (int offset : PathFinder.NEIGHBOURS8) {
					int next = cell + offset;
					if (level.insideMap(next) && level.passable[next] && !level.heroFOV[next]) free++;
				}
				if (free < 3) continue;
				member.pos = cell;
				if (member.sprite != null) member.sprite.place(cell);
				return "guest moved to " + cell;
			}
		}
		return "no cell out of view";
	}

	private static Char target(String who) {
		return "guest".equals(who) ? TurnClock.activeMember() : Dungeon.hero;
	}

	private static String spawnRat(Char target, boolean hidden) {
		if (target == null || !target.isAlive()) return "no such player";
		Level level = Dungeon.level;
		int cell = -1;
		for (int offset : PathFinder.NEIGHBOURS8) {
			int candidate = target.pos + offset;
			if (level.insideMap(candidate) && level.passable[candidate] && !level.pit[candidate]
					&& Actor.findChar(candidate) == null) {
				cell = candidate;
				break;
			}
		}
		if (cell < 0) return "no free cell next to the player";
		Rat rat = new Rat();
		rat.pos = cell;
		rat.aggro(target);
		GameScene.add(rat);
		if (hidden) Buff.affect(rat, Invisibility.class, 1000f);
		return "rat " + rat.id() + " at " + cell + " hunting " + (target == Dungeon.hero ? "host" : "guest");
	}
}
