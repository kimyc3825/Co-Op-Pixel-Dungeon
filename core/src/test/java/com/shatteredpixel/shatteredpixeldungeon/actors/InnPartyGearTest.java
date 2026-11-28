// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import static com.shatteredpixel.shatteredpixeldungeon.actors.EngineHarness.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/** The party companion's own equipment and bag on the host, driven by guest commands. */
public class InnPartyGearTest {
	private static final float FRAME = 0.016f;

	@BeforeClass
	public static void bootEngine() throws Exception {
		boot();
		// Potion colours, as a new game sets them up; drinking identifies the potion.
		com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion.initColors();
	}

	@After
	public void stop() throws Exception {
		stopActorThread();
	}

	private static void put(int cell, Item item) {
		Heap heap = new Heap();
		heap.pos = cell;
		heap.drop(item);
		level.heaps.put(cell, heap);
	}

	/** Sends a guest command and runs frames until the host acknowledged it. */
	private static void command(String kind, int first, int second) {
		guest(kind, first, second);
		int target = seq;
		for (int i = 0; i < 120 && ack() < target; i++) frame(FRAME);
		assertEquals(state(), target, ack());
		frames(3, FRAME);
	}

	@Test
	public void companionStartsWithItsClassKit() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		// The guest in the harness plays a mage.
		assertTrue(member.weapon() instanceof MagesStaff);
		assertTrue(member.armor() instanceof ClothArmor);
		assertEquals(1, member.bag().size());
		assertTrue(member.bag().get(0) instanceof Food);
	}

	@Test
	public void steppingOnItemsPicksThemUpAndGoldGoesToThePurse() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		put(cell(6, 3), new PotionOfHealing());
		put(cell(7, 3), new Gold(25));
		put(cell(8, 3), new IronKey(Dungeon.depth));
		int gold = Dungeon.gold;
		command("M", 1, 0);
		command("M", 1, 0);
		command("M", 1, 0);
		assertEquals(cell(8, 3), member.pos);
		assertEquals(2, member.bag().size());
		assertTrue(member.bag().get(1) instanceof PotionOfHealing);
		assertEquals(gold + 25, Dungeon.gold);
		assertNotNull("the companion took the host's key", level.heaps.get(cell(8, 3)));
	}

	@Test
	public void equipDrinkGiveAndDrop() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		put(cell(6, 3), new Dagger().identify(false));
		put(cell(7, 3), new PotionOfHealing());
		command("M", 1, 0);
		command("M", 1, 0);
		assertEquals(3, member.bag().size());

		// Equip the dagger: the staff goes into the dagger's slot.
		command("E", 1, 0);
		assertTrue(member.weapon() instanceof Dagger);
		assertTrue(member.bag().get(1) instanceof MagesStaff);

		// Drink the healing potion.
		member.HP = 3;
		command("U", 2, 0);
		assertEquals(2, member.bag().size());
		assertTrue("the potion did not heal: " + member.HP, member.HP > 3
				|| member.buff(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing.class) != null);

		// Give the ration to the host.
		int rations = hero.belongings.getAllItems(Food.class).size();
		command("G", 0, 0);
		assertEquals(rations + 1, hero.belongings.getAllItems(Food.class).size());
		assertEquals(1, member.bag().size());

		// Drop the staff where the companion stands.
		command("D", 0, 0);
		assertEquals(0, member.bag().size());
		Heap heap = level.heaps.get(member.pos);
		assertNotNull(heap);
		assertTrue(heap.peek() instanceof MagesStaff);

		// Unequip the armor into the empty bag.
		command("R", InnPartyMember.SLOT_ARMOR, 0);
		assertNull(member.armor());
		assertTrue(member.bag().get(0) instanceof ClothArmor);
	}

	@Test
	public void cursedEquipmentCannotBeTakenOff() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		Dagger cursed = new Dagger();
		cursed.cursed = true;
		put(cell(6, 3), cursed);
		command("M", 1, 0);
		command("E", 1, 0);
		assertSame(cursed, member.weapon());
		assertTrue(cursed.cursedKnown);
		command("R", InnPartyMember.SLOT_WEAPON, 0);
		assertSame("a cursed weapon came off", cursed, member.weapon());
	}

	@Test
	public void steppingIntoAChestOpensItInsteadOfStandingOnIt() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		Heap chest = new Heap();
		chest.pos = cell(6, 3);
		chest.type = Heap.Type.CHEST;
		chest.drop(new PotionOfHealing());
		level.heaps.put(chest.pos, chest);
		Heap locked = new Heap();
		locked.pos = cell(5, 4);
		locked.type = Heap.Type.LOCKED_CHEST;
		locked.drop(new Food());
		level.heaps.put(locked.pos, locked);

		command("M", 1, 0);
		assertEquals("the companion stood on the chest", cell(5, 3), member.pos);
		assertEquals(Heap.Type.HEAP, chest.type);

		command("M", 0, 1);
		assertEquals(cell(5, 3), member.pos);
		assertEquals("the companion opened a locked chest", Heap.Type.LOCKED_CHEST, locked.type);

		// The opened chest is a plain heap now: stepping on it picks up its item.
		command("M", 1, 0);
		assertEquals(cell(6, 3), member.pos);
		assertTrue(member.bag().get(member.bag().size() - 1) instanceof PotionOfHealing);
	}

	@Test
	public void queuedStepsAreCarriedOutInOrder() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		guest("M", 1, 0);
		guest("M", 1, 0);
		guest("M", 0, 1);
		int last = seq;
		for (int i = 0; i < 200 && ack() < last; i++) frame(FRAME);
		assertEquals(state(), last, ack());
		assertEquals("the queued steps were not all taken", cell(7, 4), member.pos);
	}

	@Test
	public void companionHealthFollowsTheHostLevel() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		hero.lvl = 3;
		command("W", 0, 0);
		assertEquals(30, member.HT);
	}
}
