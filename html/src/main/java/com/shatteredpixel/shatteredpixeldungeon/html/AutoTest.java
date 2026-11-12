/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.html;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.WelcomeScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.watabou.input.PointerEvent;
import com.watabou.utils.Point;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.utils.PathFinder;

import java.util.ArrayDeque;

/**
 * Smoke-test driver, only active when switched on from the browser console with spd.autotest(true).
 * Not part of the game: it plays a simple run by giving the hero the same commands a player's taps
 * give (Hero.handle + next, like GameScene's cell listener, and Hero.rest like the wait button):
 * fight visible enemies, rest when hurt, otherwise walk to the stairs down and descend.
 * Used to check that a real run reaches the first boss without errors in the console.
 */
public class AutoTest {

	private static boolean enabled;
	private static int actions;
	private static String last = "";
	private static int stuck;

	public static void setEnabled( boolean on ){
		enabled = on;
	}

	public static String status(){
		Hero hero = Dungeon.hero;
		if (hero == null) return "no hero, scene=" + Game.scene().getClass().getSimpleName();
		return "runs=" + runs + " deepest=" + deepest + " enabled=" + enabled + " depth=" + Dungeon.depth + " hp=" + hero.HP + "/" + hero.HT + " lvl=" + hero.lvl
				+ " pos=" + hero.pos + " exit=" + (Dungeon.level == null ? -1 : Dungeon.level.exit())
				+ " ready=" + hero.ready + " alive=" + hero.isAlive() + " actions=" + actions + " last=" + last
				+ " scene=" + Game.scene().getClass().getSimpleName() + (stuck > 0 ? "\n" + around(hero.pos, 9) : "");
	}

	//terrain codes around a cell, '*' = hero, lowercase hex = impassable, '.' = passable; for debugging the driver
	private static String around( int center, int radius ){
		Level level = Dungeon.level;
		int w = level.width();
		StringBuilder sb = new StringBuilder();
		for (int y = center / w - radius; y <= center / w + radius; y++){
			for (int x = center % w - radius; x <= center % w + radius; x++){
				if (x < 0 || y < 0 || x >= w || y >= level.height()) { sb.append(' '); continue; }
				int c = x + y * w;
				if (c == center) sb.append('*');
				else if (c == level.exit()) sb.append('X');
				else sb.append(level.passable[c] ? '.' : Character.forDigit(level.map[c] % 36, 36));
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	public static int runs = 0;
	public static int deepest = 0;
	private static int deadFrames;
	private static int storyFrames;
	private static int lastPos = -1, lastHP = -1, noProgress, ignoredFor;
	private static Mob lastTarget, ignored;
	private static int lastHeap = -1, lastDepth = -1;
	private static final java.util.HashSet<Integer> ignoredHeaps = new java.util.HashSet<>();

	//called on the game thread after every frame
	public static void step(){
		if (!enabled) return;

		//region story on the loading screen: tap its Continue button
		if (Game.scene() instanceof InterlevelScene){
			StyledButton button = com.watabou.noosa.WebGroupAccess.findFirst(Game.scene(), StyledButton.class);
			if (button != null && ++storyFrames % 60 == 0){
				tap(button);
			}
			return;
		}

		//from the title screens: start a run
		if (Game.scene() instanceof TitleScene || Game.scene() instanceof WelcomeScene){
			if (++deadFrames > 120){
				deadFrames = 0;
				startRun();
			}
			return;
		}

		if (!(Game.scene() instanceof GameScene)) return;
		Hero hero = Dungeon.hero;
		if (hero != null && !hero.isAlive()){
			if (++deadFrames > 120){
				deadFrames = 0;
				runs++;
				startRun();
			}
			return;
		}
		if (hero == null || !hero.ready || Dungeon.level == null) return;
		deepest = Math.max(deepest, Dungeon.depth);
		if (Dungeon.depth != lastDepth){ lastDepth = Dungeon.depth; ignoredHeaps.clear(); }

		//dismiss story popups and other windows, like tapping outside of them
		Window window = com.watabou.noosa.WebGroupAccess.findFirst(Game.scene(), Window.class);
		if (window != null){
			window.hide();
			last = "close " + window.getClass().getSimpleName();
			return;
		}

		Level level = Dungeon.level;
		Mob target = null;
		int bestDist = Integer.MAX_VALUE;
		//a command that changes nothing (e.g. attacking an enemy that can't be reached) would repeat forever
		if (hero.pos == lastPos && hero.HP == lastHP && (last.startsWith("attack") || last.startsWith("hunt") || last.startsWith("loot"))){
			if (++noProgress > 20){
				if (last.startsWith("loot")) ignoredHeaps.add(lastHeap);
				ignored = lastTarget;
				ignoredFor = 200;
				noProgress = 0;
			}
		} else {
			noProgress = 0;
		}
		lastPos = hero.pos;
		lastHP = hero.HP;
		if (ignoredFor > 0 && --ignoredFor == 0) ignored = null;

		for (Mob m : level.mobs.toArray(new Mob[0])){
			if (m == ignored) continue;
			if (m.alignment == Char.Alignment.ENEMY && level.heroFOV[m.pos] && m.isAlive()){
				int d = level.distance(hero.pos, m.pos);
				if (d < bestDist){
					bestDist = d;
					target = m;
				}
			}
		}

		if (target != null && hero.HP <= hero.HT / 4){
			//desperate: drink an unknown potion, healing is the most common one
			Potion potion = hero.belongings.getItem(Potion.class);
			if (potion != null){
				last = "drink " + potion.getClass().getSimpleName();
				actions++;
				potion.execute(hero, Potion.AC_DRINK);
				return;
			}
		}

		if (target != null){
			if (bestDist <= 1 || hero.HP >= hero.HT * 3 / 5){
				lastTarget = target;
				command(target.pos, "attack " + target.getClass().getSimpleName());
			} else {
				//hurt: let it come to us rather than chasing it
				last = "wait for " + target.getClass().getSimpleName();
				actions++;
				hero.rest(false);
			}
			return;
		}

		Hunger hunger = hero.buff(Hunger.class);
		if (hunger != null && hunger.isStarving() && target == null){
			Food food = hero.belongings.getItem(Food.class);
			if (food != null){
				last = "eat";
				actions++;
				food.execute(hero, Food.AC_EAT);
				return;
			}
		}

		//no regeneration while starving, resting would never end
		if (hero.HP < hero.HT * 9 / 10 && target == null && (hunger == null || !hunger.isStarving())){
			last = "rest";
			actions++;
			hero.rest(false);
			return;
		}

		//wear better equipment, like a player opening the item and choosing "equip"
		for (Item item : hero.belongings.backpack.items.toArray(new Item[0])){
			if (item instanceof Armor && hero.belongings.armor() != null
					&& ((Armor) item).tier > hero.belongings.armor().tier && ((Armor) item).STRReq() <= hero.STR()){
				last = "equip " + item.getClass().getSimpleName();
				actions++;
				item.execute(hero, EquipableItem.AC_EQUIP);
				return;
			}
			if (item instanceof MeleeWeapon && hero.belongings.weapon() instanceof MeleeWeapon
					&& ((MeleeWeapon) item).tier > ((MeleeWeapon) hero.belongings.weapon()).tier
					&& ((MeleeWeapon) item).STRReq() <= hero.STR()){
				last = "equip " + item.getClass().getSimpleName();
				actions++;
				item.execute(hero, EquipableItem.AC_EQUIP);
				return;
			}
		}

		//pick up items lying nearby
		for (Heap heap : level.heaps.valueList()){
			if (heap.type == Heap.Type.HEAP && heap.pos != hero.pos && level.visited[heap.pos] && !ignoredHeaps.contains(heap.pos)
					&& level.distance(hero.pos, heap.pos) < 12 && heap.peek() != null){
				int step = nextStepTo(level, hero.pos, heap.pos, false, false);
				if (step != -1){
					lastHeap = heap.pos;
					command(step, "loot");
					return;
				}
			}
		}

		//gain some experience before going deeper: go after the nearest enemy on the floor
		if (hero.lvl < Dungeon.depth + 2 && Dungeon.depth < 5){
			Mob prey = null;
			int preyDist = Integer.MAX_VALUE;
			for (Mob m : level.mobs.toArray(new Mob[0])){
				if (m == ignored || m.alignment != Char.Alignment.ENEMY || !m.isAlive()) continue;
				int d = level.distance(hero.pos, m.pos);
				if (d < preyDist){
					preyDist = d;
					prey = m;
				}
			}
			if (prey != null){
				int step = nextStepTo(level, hero.pos, prey.pos, false, false);
				if (step == -1) step = nextStepTo(level, hero.pos, prey.pos, false, true);
				if (step != -1){
					lastTarget = prey;
					command(step, "hunt " + prey.getClass().getSimpleName());
					return;
				}
			}
		}

		int exit = level.exit();
		if (hero.pos == exit && hero.HP < hero.HT && (hunger == null || !hunger.isStarving())){
			last = "rest before stairs";
			actions++;
			hero.rest(false);
			return;
		}
		if (hero.pos == exit){
			command(exit, "descend");
			return;
		}

		//prefer dry routes, piranhas live in the water
		int next = nextStepTo(level, hero.pos, exit, false, false);
		if (next == -1) next = nextStepTo(level, hero.pos, exit, false, true);
		if (next == -1){
			//the way is blocked by a hidden door: walk up to it, then search next to it like a player would
			int viaSecret = nextStepTo(level, hero.pos, exit, true, true);
			if (viaSecret != -1 && !level.secret[viaSecret]){
				command(viaSecret, "move to secret");
				return;
			}
			//nothing reachable: search for hidden doors
			stuck++;
			last = "search";
			actions++;
			hero.search(true); //the search button (Toolbar, long press)
			return;
		}
		command(next, "move");
	}

	//start a new run, the way HeroSelectScene's start button does
	private static void startRun(){
		GamesInProgress.selectedClass = HeroClass.WARRIOR;
		if (GamesInProgress.firstEmpty() == -1){
			//all slots taken by abandoned test runs: erase one, like the Erase button of a slot
			Dungeon.deleteGame(1, true);
		}
		GamesInProgress.curSlot = GamesInProgress.firstEmpty();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.initSeed();
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		Game.switchScene(InterlevelScene.class);
	}

	//a tap on a UI button, through the same input queue the browser feeds
	private static void tap( StyledButton button ){
		Point p = button.camera().cameraToScreen(button.centerX(), button.centerY());
		PointerEvent.addPointerEvent(new PointerEvent(p.x, p.y, 0, PointerEvent.Type.DOWN));
		PointerEvent.addPointerEvent(new PointerEvent(p.x, p.y, 0, PointerEvent.Type.UP));
		last = "tap continue";
	}

	private static void command( int cell, String what ){
		Hero hero = Dungeon.hero;
		last = what + "@" + cell;
		actions++;
		if (hero.handle(cell)){
			hero.next();
		} else {
			hero.rest(false);
		}
	}

	//breadth first search on the real map, avoiding visible traps and hazards
	private static int nextStepTo( Level level, int from, int to, boolean throughSecrets, boolean throughWater ){
		int length = level.length();
		int[] prev = new int[length];
		java.util.Arrays.fill(prev, -2);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		prev[from] = -1;
		while (!queue.isEmpty()){
			int cell = queue.poll();
			if (cell == to) break;
			for (int offset : PathFinder.NEIGHBOURS8){
				int n = cell + offset;
				if (n < 0 || n >= length || prev[n] != -2) continue;
				boolean secret = throughSecrets && level.secret[n];
				if (!secret && (!level.passable[n] || level.avoid[n])) continue;
				if (!throughWater && level.water[n] && n != to) continue;
				Trap trap = level.traps.get(n);
				if (trap != null && trap.visible && trap.active) continue;
				if (n != to && Actor.findChar(n) != null) continue;
				prev[n] = cell;
				queue.add(n);
			}
		}
		if (prev[to] == -2) return -1;
		int cell = to;
		while (prev[cell] != from && prev[cell] != -1){
			cell = prev[cell];
		}
		return cell;
	}
}
