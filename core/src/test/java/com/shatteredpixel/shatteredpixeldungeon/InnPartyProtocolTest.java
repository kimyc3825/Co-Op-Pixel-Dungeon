// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The party wire protocol: guest commands and host snapshots, validated strictly. */
public class InnPartyProtocolTest {

	private final List<String> sent = new ArrayList<>();

	@After
	public void clear() {
		InnOriginalParty.clear();
	}

	private static String tiles(int cells) {
		int[] values = new int[cells];
		Arrays.fill(values, 1);
		return InnOriginalParty.encodeTiles(values);
	}

	/** A valid snapshot with one field replaced. */
	private static String[] snapshot(int field, String value) {
		String[] fields = ("S|7|2|3|0|3|3|5|1|42|" + tiles(9) + "|42,4,10,20,1,x|4,1|play|environment/tiles_sewers.png|30"
				+ "|w,10,1,64,worn shortsword +1;0,20,3,12,healing%2C x3|4,120,11")
				.split("\\|", -1);
		if (field >= 0) fields[field] = value;
		return fields;
	}

	private static InnOriginalParty.Snapshot parse(String[] fields) {
		try {
			return InnOriginalParty.Snapshot.parse(fields);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Test
	public void commandValidation() {
		assertTrue(InnOriginalParty.validCommand("M", 1, -1));
		assertTrue(InnOriginalParty.validCommand("M", 0, 1));
		assertFalse(InnOriginalParty.validCommand("M", 0, 0));
		assertFalse(InnOriginalParty.validCommand("M", 2, 0));
		assertFalse(InnOriginalParty.validCommand("M", Integer.MIN_VALUE, 0));
		assertTrue(InnOriginalParty.validCommand("A", 17, 0));
		assertFalse(InnOriginalParty.validCommand("A", 0, 0));
		assertFalse(InnOriginalParty.validCommand("A", 17, 1));
		assertTrue(InnOriginalParty.validCommand("T", 0, 0));
		assertFalse(InnOriginalParty.validCommand("T", -1, 0));
		assertFalse(InnOriginalParty.validCommand("T", 16000, 0));
		assertTrue(InnOriginalParty.validCommand("W", 0, 0));
		assertFalse(InnOriginalParty.validCommand("W", 1, 0));
		assertFalse(InnOriginalParty.validCommand("N", 0, 0));
		assertFalse(InnOriginalParty.validCommand("", 0, 0));
		assertTrue(InnOriginalParty.validCommand("E", 0, 0));
		assertTrue(InnOriginalParty.validCommand("E", 19, 0));
		assertFalse(InnOriginalParty.validCommand("E", 20, 0));
		assertFalse(InnOriginalParty.validCommand("E", -1, 0));
		assertTrue(InnOriginalParty.validCommand("R", 1, 0));
		assertFalse(InnOriginalParty.validCommand("R", 2, 0));
		assertTrue(InnOriginalParty.validCommand("U", 3, 2));
		assertFalse(InnOriginalParty.validCommand("U", 3, 3));
		assertTrue(InnOriginalParty.validCommand("H", 3, 15999));
		assertFalse(InnOriginalParty.validCommand("H", 3, 16000));
		assertFalse(InnOriginalParty.validCommand("D", 0, 1));
		assertTrue(InnOriginalParty.validCommand("G", 5, 0));
	}

	@Test
	public void gearAndPartyStatsParse() {
		InnOriginalParty.Snapshot state = parse(snapshot(-1, null));
		assertNotNull(state);
		assertEquals(4, state.level);
		assertEquals(120, state.gold);
		assertEquals(11, state.strength);
		assertEquals(2, state.gear.size());
		InnOriginalParty.Gear weapon = state.gear.get(0);
		assertEquals(InnOriginalParty.GEAR_WEAPON, weapon.slot);
		assertTrue(weapon.equipped());
		assertTrue(weapon.has(InnOriginalParty.FLAG_UPGRADABLE));
		InnOriginalParty.Gear potion = state.gear.get(1);
		assertEquals(0, potion.slot);
		assertEquals(3, potion.quantity);
		assertEquals("healing, x3", potion.title);
		assertTrue(potion.has(InnOriginalParty.FLAG_DRINK) && potion.has(InnOriginalParty.FLAG_THROW));
		// Invalid gear records are skipped, not fatal.
		assertEquals(1, parse(snapshot(16, "w,10,1,64,ok;99,1,1,0,bad;0,1,0,0,none;0,x,1,0,y")).gear.size());
		assertEquals(0, parse(snapshot(16, "")).gear.size());
		// The stats line is required and range checked.
		assertNull(parse(snapshot(17, "0,1,10")));
		assertNull(parse(snapshot(17, "1,-1,10")));
		assertNull(parse(snapshot(17, "1,1")));
	}

	@Test
	public void titlesRoundTripThroughTheEscaping() {
		String title = "50% off | a;b, c %2C";
		assertEquals(title, InnOriginalParty.unescape(InnOriginalParty.escape(title)));
		String escaped = InnOriginalParty.escape(title);
		assertFalse(escaped.contains("|") || escaped.contains(";") || escaped.contains(","));
	}

	@Test
	public void validSnapshotParses() {
		InnOriginalParty.Snapshot state = parse(snapshot(-1, null));
		assertNotNull(state);
		assertEquals(7, state.revision);
		assertEquals(2, state.floor);
		assertEquals(3, state.depth);
		assertEquals(5, state.ack);
		assertEquals(1, state.turn);
		assertEquals(42, state.player);
		assertEquals(30, state.grace);
		assertEquals(9, state.tiles.length);
	}

	@Test
	public void snapshotFieldsAreRangeChecked() {
		String[][] bad = {
				{"0", "S2"}, {"1", "0"}, {"1", "-3"}, {"1", "x"},
				{"2", "0"}, {"2", "100001"},
				{"3", "0"}, {"3", "31"}, {"3", "2147483647"},
				{"4", "-1"}, {"4", "10"}, {"4", "100000"},
				{"5", "2"}, {"5", "257"}, {"6", "2"},
				{"7", "-1"},
				{"8", "3"}, {"8", "-1"},
				{"9", "0"}, {"9", "-5"},
				{"10", "AB"},
				{"13", "won"},
				{"14", "environment/../x.png"}, {"14", "sprites/tiles_sewers.png"},
				{"15", "51"}, {"15", "-1"},
		};
		for (String[] change : bad) {
			assertNull("accepted field " + change[0] + "=" + change[1],
					parse(snapshot(Integer.parseInt(change[0]), change[1])));
		}
		// No countdown means no grace.
		String[] none = snapshot(8, "0");
		none[15] = "5";
		assertNull(parse(none));
		none[15] = "0";
		assertNotNull(parse(none));
		// Field count.
		assertNull(parse(Arrays.copyOf(snapshot(-1, null), 17)));
		assertNull(parse(Arrays.copyOf(snapshot(-1, null), 19)));
	}

	@Test
	public void guestKeepsTheNewestRevisionOnly() {
		InnOriginalParty.configure(false, "host", "WARRIOR", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty.receive(String.join("|", snapshot(1, "9")));
		assertEquals(9, InnOriginalParty.current().latest().revision);
		InnOriginalParty.receive(String.join("|", snapshot(1, "8")));
		assertEquals(9, InnOriginalParty.current().latest().revision);
		InnOriginalParty.receive(String.join("|", snapshot(3, "999")));
		assertEquals(9, InnOriginalParty.current().latest().revision);
		InnOriginalParty.receive("S|10|garbage");
		assertEquals(9, InnOriginalParty.current().latest().revision);
	}

	@Test
	public void guestResendsAPendingCommandOnlyAsASafetyNet() {
		InnOriginalParty.configure(false, "host", "WARRIOR", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		InnOriginalParty.receive(String.join("|", snapshot(7, "0")));
		assertEquals(1, link.command("M", 1, 0));
		assertEquals(Arrays.asList("C|2|1|M|1|0"), sent);
		for (int i = 0; i < 60; i++) link.updateGuest(0.016f);
		assertEquals("resent within a second", 1, sent.size());
		for (int i = 0; i < 10; i++) link.updateGuest(0.016f);
		assertEquals(2, sent.size());
		// Acknowledged: no more resends.
		InnOriginalParty.receive(String.join("|", snapshot(1, "8")).replace("|2|3|0|3|3|5|", "|2|3|0|3|3|1|"));
		sent.clear();
		for (int i = 0; i < 200; i++) link.updateGuest(0.016f);
		assertTrue(sent.isEmpty());
	}

	@Test
	public void hostAcceptsOnlyNewerCommandsForTheCurrentFloor() throws Exception {
		InnOriginalParty.configure(true, "guest", "MAGE", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		Field floor = InnOriginalParty.class.getDeclaredField("floor");
		floor.setAccessible(true);
		floor.setInt(link, 3);

		InnOriginalParty.receive("C|2|5|M|1|0");
		assertFalse("a command for an old floor was accepted", link.hasPendingCommand());
		InnOriginalParty.receive("C|3|5|M|1|0");
		assertTrue(link.hasPendingCommand());
		InnOriginalParty.receive("C|3|4|W|0|0");
		Field inbound = InnOriginalParty.class.getDeclaredField("inbound");
		inbound.setAccessible(true);
		Object command = inbound.get(link);
		Field seq = command.getClass().getDeclaredField("seq");
		seq.setAccessible(true);
		assertEquals("an older sequence replaced a newer one", 5, seq.getInt(command));
		InnOriginalParty.receive("C|3|6|M|5|0");
		assertEquals("an invalid command was accepted", 5, seq.getInt(inbound.get(link)));
		InnOriginalParty.receive("C|3|7|W|0|0|extra");
		assertEquals(5, seq.getInt(inbound.get(link)));
		link.dropPending();
		assertFalse(link.hasPendingCommand());
		InnOriginalParty.receive("C|3|5|M|1|0");
		assertFalse("an acknowledged sequence was accepted again", link.hasPendingCommand());
		InnOriginalParty.close();
		InnOriginalParty.receive("C|3|9|W|0|0");
		assertFalse(link.hasPendingCommand());
	}
}
