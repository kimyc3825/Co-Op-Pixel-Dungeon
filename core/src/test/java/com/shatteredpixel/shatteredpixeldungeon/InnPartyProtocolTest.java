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
				+ "|w,10,1,64,worn shortsword +1;0,20,3,12,healing%2C x3|4,120,11|3,42,5;4,42,4")
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
		assertNull(parse(Arrays.copyOf(snapshot(-1, null), 18)));
		assertNull(parse(Arrays.copyOf(snapshot(-1, null), 20)));
	}

	@Test
	public void trailParsesSkippingInvalidSteps() {
		assertEquals("3,42,5;4,42,4", parse(snapshot(-1, null)).trail);
		assertNotNull("an empty trail is valid", parse(snapshot(18, "")));
		List<int[]> steps = InnOriginalParty.parseTrail("1,42,10;x,1,2;2,0,5;3,42,-1;4,42,11;5,42;6,42,16000");
		assertEquals(2, steps.size());
		assertEquals("[1, 42, 10]", Arrays.toString(steps.get(0)));
		assertEquals("[4, 42, 11]", Arrays.toString(steps.get(1)));
		assertTrue(InnOriginalParty.parseTrail("").isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static java.util.Set<Integer> shownIds(InnOriginalParty link) throws Exception {
		Field shown = InnOriginalParty.class.getDeclaredField("shownIds");
		shown.setAccessible(true);
		return (java.util.Set<Integer>) shown.get(link);
	}

	@Test
	public void hostRepeatsEachStepInSeveralSnapshotsForShownActorsOnly() throws Exception {
		InnOriginalParty.configure(true, "guest", "MAGE", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		shownIds(link).add(42);
		link.addMove(42, 10);
		link.addMove(99, 11);
		link.addMove(42, 11);
		String trail = link.trailRecord(1);
		assertEquals("steps of actors the guest cannot see are left out", "1,42,10;3,42,11", trail);
		for (int next = 2; next <= InnOriginalParty.TRAIL_SNAPSHOTS; next++) {
			assertEquals("a step is repeated in following snapshots", trail, link.trailRecord(next));
		}
		assertEquals("", link.trailRecord(InnOriginalParty.TRAIL_SNAPSHOTS + 1));
	}

	@Test
	public void hostSendsOnlyTheNewestSteps() throws Exception {
		InnOriginalParty.configure(true, "guest", "MAGE", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		shownIds(link).add(42);
		for (int cell = 0; cell < 200; cell++) link.addMove(42, cell);
		List<int[]> steps = InnOriginalParty.parseTrail(link.trailRecord(1));
		assertEquals(InnOriginalParty.MAX_TRAIL, steps.size());
		assertEquals(200, steps.get(steps.size() - 1)[0]);
		assertEquals(199, steps.get(steps.size() - 1)[2]);
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
	public void guestSendsSeveralCommandsWithoutWaiting() {
		InnOriginalParty.configure(false, "host", "WARRIOR", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		InnOriginalParty.receive(String.join("|", snapshot(7, "0")));
		assertEquals(1, link.command("M", 1, 0));
		assertEquals(2, link.command("M", 1, 0));
		assertEquals(3, link.command("M", 0, 1));
		assertEquals(Arrays.asList("C|2|1|M|1|0", "C|2|2|M|1|0", "C|2|3|M|0|1"), sent);
		assertEquals(3, link.inFlight());
		assertTrue(link.awaitingAction());

		// The host answered the first two: one left, and only it is resent.
		InnOriginalParty.receive(String.join("|", snapshot(1, "8")).replace("|2|3|0|3|3|5|", "|2|3|0|3|3|2|"));
		assertEquals(1, link.inFlight());
		sent.clear();
		for (int i = 0; i < 70; i++) link.updateGuest(0.016f);
		assertEquals(Arrays.asList("C|2|3|M|0|1"), sent);

		// All answered.
		InnOriginalParty.receive(String.join("|", snapshot(1, "9")).replace("|2|3|0|3|3|5|", "|2|3|0|3|3|3|"));
		assertEquals(0, link.inFlight());
		assertFalse(link.awaitingAction());
	}

	private static int queued(InnOriginalParty link) throws Exception {
		Field inbound = InnOriginalParty.class.getDeclaredField("inbound");
		inbound.setAccessible(true);
		return ((java.util.Collection<?>) inbound.get(link)).size();
	}

	@Test
	public void hostQueuesCommandsInOrderForTheCurrentFloor() throws Exception {
		InnOriginalParty.configure(true, "guest", "MAGE", "a", "b", "c", "d", 1L, false, sent::add);
		InnOriginalParty link = InnOriginalParty.current();
		Field floor = InnOriginalParty.class.getDeclaredField("floor");
		floor.setAccessible(true);
		floor.setInt(link, 3);

		InnOriginalParty.receive("C|2|5|M|1|0");
		assertFalse("a command for an old floor was accepted", link.hasPendingCommand());
		InnOriginalParty.receive("C|3|5|M|1|0");
		assertTrue(link.hasPendingCommand());
		InnOriginalParty.receive("C|3|6|M|1|0");
		InnOriginalParty.receive("C|3|7|W|0|0");
		assertEquals("in-flight commands were not all queued", 3, queued(link));
		InnOriginalParty.receive("C|3|6|M|1|0");
		InnOriginalParty.receive("C|3|4|W|0|0");
		assertEquals("a resent or older command was queued again", 3, queued(link));
		InnOriginalParty.receive("C|3|8|M|5|0");
		InnOriginalParty.receive("C|3|9|W|0|0|extra");
		assertEquals("an invalid command was queued", 3, queued(link));

		// A full queue takes nothing more until the member catches up.
		for (int seq = 10; seq < 10 + InnOriginalParty.MAX_QUEUED; seq++) InnOriginalParty.receive("C|3|" + seq + "|W|0|0");
		assertEquals(InnOriginalParty.MAX_QUEUED, queued(link));

		link.dropPending();
		assertFalse(link.hasPendingCommand());
		assertEquals(0, queued(link));
		InnOriginalParty.receive("C|3|6|M|1|0");
		assertFalse("an acknowledged sequence was accepted again", link.hasPendingCommand());
		InnOriginalParty.close();
		InnOriginalParty.receive("C|3|99|W|0|0");
		assertFalse(link.hasPendingCommand());
	}
}
