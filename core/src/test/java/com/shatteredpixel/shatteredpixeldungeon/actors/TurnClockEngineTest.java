// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import static com.shatteredpixel.shatteredpixeldungeon.actors.EngineHarness.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Visual;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Regression tests for the party turn clock, running the real actor scheduler on its own thread. */
public class TurnClockEngineTest {

	private static final float FRAME = 0.016f;

	@BeforeClass
	public static void bootEngine() throws Exception {
		boot();
	}

	@After
	public void stop() throws Exception {
		stopActorThread();
	}

	private static void assertAlive() {
		assertTrue("the actor thread died", actorThreadAlive());
	}

	// ---- item 1: deferred host actions keep the scheduler on the hero ----

	@Test
	public void deferredHostActionHoldsTheSchedulerUntilItsOwnNext() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(12, 12));
		frames(3, FRAME);
		assertSame(hero, Actor.currentActor());
		int acts = heroActs;
		int monsterActs = monster.acts;
		int memberPos = member.pos;

		hero.busy(); // e.g. Item.cast(): the hero spends its time when the missile lands
		guest("M", 1, 0);
		frames(10, FRAME);
		assertEquals("Hero.act() ran during the animation", acts, heroActs);
		assertEquals("the guest acted before the throw resolved", memberPos, member.pos);
		assertEquals("a monster acted before the throw resolved", monsterActs, monster.acts);
		assertSame(hero, Actor.currentActor());
		assertEquals(0, ack());

		hero.spendAndNext(1f); // the missile's callback
		frames(6, FRAME);
		assertEquals(memberPos + 1, member.pos);
		assertEquals(1, ack());
		assertTrue(hero.ready);
		assertAlive();
	}

	@Test
	public void deferredActionDuringOwnCountdownFreezesTheGrace() throws Exception {
		newParty(15, 15, cell(3, 3), cell(8, 8));
		addDummy(cell(3, 9)).hunt(hero);
		frames(3, FRAME);
		guest("M", 1, 0);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		frames(60, FRAME);
		float before = TurnClock.graceRemaining();
		int acts = heroActs;
		hero.busy();
		frames(60, FRAME);
		assertEquals(before, TurnClock.graceRemaining(), 1e-4f);
		assertEquals(acts, heroActs);
		hero.spendAndNext(1f);
		frames(3, FRAME);
		assertAlive();
	}

	public static class StubMissile extends MissileSprite {
		static final List<Callback> inFlight = new ArrayList<>();
		@Override public void reset(int from, int to, Item item, Callback listener) { inFlight.add(listener); }
		@Override public void reset(Visual from, int to, Item item, Callback listener) { inFlight.add(listener); }
		@Override public void reset(int from, Visual to, Item item, Callback listener) { inFlight.add(listener); }
		@Override public void reset(Visual from, Visual to, Item item, Callback listener) { inFlight.add(listener); }
		@Override public void reset(PointF from, PointF to, Item item, Callback listener) { inFlight.add(listener); }
	}

	public static class StubGroup extends Group {
		@Override public synchronized Gizmo recycle(Class<? extends Gizmo> c) {
			try { return alloc(StubMissile.class); } catch (Exception e) { throw new RuntimeException(e); }
		}
	}

	public static class Rock extends Item {
		static int landed;
		{ stackable = false; defaultAction = AC_THROW; }
		@Override protected void onThrow(int cell) { landed++; Dungeon.level.drop(this, cell); }
		@Override public String name() { return "rock"; }
	}

	/** The interface path of a quick slot throw: ready-gated button, item action, ready-gated cell selector. */
	private static boolean throwFromUi(Rock rock, int target) throws Exception {
		if (!hero.isAlive() || !hero.ready) return false;
		if (!rock.executeFromUi(hero, Item.AC_THROW)) return false;
		if (!hero.ready) return false;
		Field cs = GameScene.class.getDeclaredField("cellSelector");
		cs.setAccessible(true);
		((CellSelector) cs.get(null)).listener.onSelect(target);
		return true;
	}

	private static int rocksOnFloor() {
		int n = 0;
		for (Heap heap : level.heaps.valueList()) for (Item item : heap.items) if (item instanceof Rock) n++;
		return n;
	}

	private static void doubleThrowIsRefused(boolean party) throws Exception {
		if (party) newParty(15, 15, cell(3, 3), cell(8, 8));
		else newSolo(15, 15, cell(3, 3));
		StubMissile.inFlight.clear();
		Rock.landed = 0;
		Field parent = Gizmo.class.getDeclaredField("parent");
		parent.setAccessible(true);
		parent.set(hero.sprite, new StubGroup());
		Rock rock = new Rock();
		hero.belongings.backpack.items.add(rock);
		frames(3, FRAME);

		assertTrue(throwFromUi(rock, cell(3, 7)));
		frames(3, FRAME);
		assertFalse("the hero became ready while the rock was in the air", hero.ready);
		assertFalse("the same rock was thrown twice", throwFromUi(rock, cell(7, 3)));
		assertEquals(1, StubMissile.inFlight.size());
		for (Callback c : new ArrayList<>(StubMissile.inFlight)) {
			c.call();
			frames(3, FRAME);
		}
		assertEquals(1, Rock.landed);
		assertEquals(1, rocksOnFloor());
		assertEquals(1f, time(hero), 1e-4f);
		assertTrue(hero.ready);
		assertAlive();
	}

	@Test
	public void doubleThrowIsRefusedInPartyPlay() throws Exception {
		doubleThrowIsRefused(true);
	}

	@Test
	public void soloMatchesTheOriginal_doubleThrowIsRefused() throws Exception {
		doubleThrowIsRefused(false);
	}

	@Test
	public void soloMatchesTheOriginal_schedulerStaysParkedDuringADeferredAction() throws Exception {
		newSolo(15, 15, cell(3, 3));
		Dummy monster = addDummy(cell(12, 12));
		monster.wander();
		frames(3, FRAME);
		assertFalse(TurnClock.partyActive());
		assertSame(hero, Actor.currentActor());
		int acts = heroActs;
		hero.busy();
		frames(30, FRAME);
		assertEquals(acts, heroActs);
		assertSame(hero, Actor.currentActor());
		assertEquals(0, monster.acts);
		hero.spendAndNext(1f);
		frames(4, FRAME);
		assertEquals(1f, time(hero), 1e-4f);
		assertEquals(1, monster.acts);
		assertTrue(hero.ready);
		// Idle: time does not move.
		float now = Actor.now();
		frames(120, FRAME);
		assertEquals(now, Actor.now(), 1e-4f);
		assertEquals(1, monster.acts);
	}

	// ---- item 2: only spent time counts as input ----

	@Test
	public void rejectedCommandsDoNotHoldOffTheCountdown() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 1));
		Dummy monster = addDummy(cell(5, 6));
		monster.hunt(member);
		frames(3, FRAME);
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		float memberTime = time(member);
		float lowest = TurnPolicy.TURN_GRACE_SECONDS;
		float next = 0;
		float start = wall;
		while (wall - start < 6f && time(member) == memberTime) {
			if (wall - start >= next && ack() == seq) {
				guest("M", 0, -1); // into the wall above
				next = wall - start + 0.15f;
			}
			frame(FRAME);
			if (TurnClock.waitingOn() == TurnClock.WAIT_GUEST) lowest = Math.min(lowest, TurnClock.graceRemaining());
		}
		assertTrue("commands were not acknowledged", ack() > 10);
		assertEquals("the member did not wait automatically", memberTime + 1f, time(member), 1e-4f);
		assertTrue("the grace was reset by rejected commands", lowest < 0.5f);
		assertTrue(wall - start < TurnPolicy.TURN_GRACE_SECONDS + 0.5f);
		assertAlive();
	}

	@Test
	public void rejectedCommandKeepsTheAfkGrace() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(5, 8)).hunt(member);
		frames(3, FRAME);
		for (int expiry = 0; expiry < TurnPolicy.AFK_EXPIRIES; expiry++) {
			hero.rest(false);
			frames(3, FRAME);
			assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
			assertEquals(TurnPolicy.TURN_GRACE_SECONDS, countdownSeconds(FRAME, 10), 0.1f);
			frames(2, FRAME);
		}
		hero.rest(false);
		frames(3, FRAME);
		assertTrue(TurnClock.graceRemaining() <= TurnPolicy.AFK_GRACE_SECONDS);
		guest("A", 99999, 0); // no such target: rejected, no time spent
		frames(2, FRAME);
		assertTrue("a rejected command restored the full grace",
				TurnClock.waitingOn() == TurnClock.WAIT_NONE || TurnClock.graceRemaining() <= TurnPolicy.AFK_GRACE_SECONDS);
		assertAlive();
	}

	@Test
	public void guestWaitCommandSpendsOneTurnAndCountsAsInput() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(5, 8)).hunt(member);
		frames(3, FRAME);
		hero.rest(false);
		frames(3, FRAME);
		frames(120, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		float memberTime = time(member);
		guest("W", 0, 0);
		frames(3, FRAME);
		assertEquals(memberTime + 1f, time(member), 1e-4f);
		assertEquals(1, ack());
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, TurnClock.graceRemaining(), 0.1f);
	}

	// ---- countdown, auto-wait and AFK through the real clock ----

	@Test
	public void countdownAutoWaitAndAfkThroughTheClock() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(3, 8));
		monster.hunt(hero);
		frames(3, FRAME);
		String label = Messages.get(TurnClock.class, "auto_wait");
		int dir = 1;
		for (int expiry = 0; expiry < 3; expiry++) {
			guest("M", dir, 0);
			dir = -dir;
			frames(3, FRAME);
			assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
			float heroTime = time(hero);
			statuses.clear();
			float took = countdownSeconds(FRAME, 10);
			float expected = expiry < TurnPolicy.AFK_EXPIRIES ? TurnPolicy.TURN_GRACE_SECONDS : TurnPolicy.AFK_GRACE_SECONDS;
			assertEquals(expected, took, 0.05f);
			frames(2, FRAME);
			assertEquals("auto-wait spends exactly one turn", heroTime + 1f, time(hero), 1e-4f);
			assertEquals("one status text, from the bundle", 1, statuses.size());
			assertEquals(label, statuses.get(0));
		}
		// Input with a time cost restores the full grace. The awake hunter is in the member's view too, so the
		// member gets its own countdown while the host is ahead; its step brings the host's turn back.
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		for (int i = 0; i < 3 && TurnClock.waitingOn() != TurnClock.WAIT_HOST; i++) {
			guest("M", dir, 0);
			dir = -dir;
			frames(3, FRAME);
		}
		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, countdownSeconds(FRAME, 10), 0.05f);
		assertAlive();
	}

	@Test
	public void graceIsWallClockAtLowFrameRates() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(3, 8)).hunt(hero);
		frames(3, FRAME);
		guest("M", 1, 0);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, countdownSeconds(1f, 30), 1.01f);
	}

	@Test
	public void hostInputCancelsItsCountdown() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(3, 8)).hunt(hero);
		frames(3, FRAME);
		guest("M", 1, 0);
		frames(3, FRAME);
		frames(100, FRAME);
		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		hero.rest(false);
		frames(3, FRAME);
		assertNotEquals(TurnClock.WAIT_HOST, TurnClock.waitingOn());
		assertEquals(1f, time(hero), 1e-4f);
	}

	// ---- skip and re-entry ----

	@Test
	public void guestWalkingAdvancesTheWorldWithoutChargingTheIdleHost() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(12, 12));
		monster.wander();
		frames(3, FRAME);
		int start = member.pos;
		for (int i = 0; i < 4; i++) {
			guest("M", 1, 0);
			frames(4, FRAME);
		}
		assertEquals(start + 4, member.pos);
		assertEquals(4, ack());
		assertEquals(4f, Actor.now(), 1e-4f);
		assertEquals("the host's clock only followed the world", Actor.now(), time(hero), 1e-4f);
		assertEquals(4, monster.acts);
		assertTrue(hero.ready);
		assertSame(hero, Actor.currentActor());
		assertFalse(TurnClock.detached(hero));
		// The host acts at the current time, not at the time it went idle.
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(5f, time(hero), 1e-4f);
		assertAlive();
	}

	@Test
	public void bothIdleFreezesTime() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(12, 12));
		monster.wander();
		frames(3, FRAME);
		float now = Actor.now();
		int woken = frames(300, FRAME);
		assertEquals(now, Actor.now(), 1e-4f);
		assertEquals(0, monster.acts);
		assertEquals(TurnClock.WAIT_NONE, TurnClock.waitingOn());
		assertTrue("the scheduler was woken while frozen", woken <= 1);
	}

	@Test
	public void reattachedHeroTakesANormalTurn() throws Exception {
		newParty(15, 15, cell(3, 3), cell(8, 8));
		Dummy monster = addDummy(cell(3, 9));
		monster.sleepNow();
		frames(3, FRAME);
		guest("M", 1, 0);
		frames(3, FRAME);
		int acts = heroActs;
		monster.pos = cell(3, 4);
		monster.hunt(hero);
		guest("M", -1, 0);
		frames(3, FRAME);
		assertTrue("Hero.act() did not run when the hero got its turn back", heroActs > acts);
		assertEquals(1, hero.visibleEnemies());
		assertEquals(1, attackCandidates());
		assertTrue(hero.ready);
		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		assertAlive();
	}

	// ---- item 3: hero.ready implies the scheduler is parked on the hero ----

	@Test
	public void heroIsNotReadyWhileTheActorThreadRunsThePartner() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		member.sprite.isMoving = true; // the previous step still animates: the actor thread waits on the sprite
		guest("M", 1, 0);
		TurnClock.update(FRAME);
		Actor.notifyActorThread(actorThread);
		Thread.sleep(50);
		assertFalse(Actor.parked());
		assertFalse("ready-gated interface was live while the actor thread ran", hero.ready);
		synchronized (member.sprite) {
			member.sprite.isMoving = false;
			member.sprite.notifyAll();
		}
		settle();
		frames(4, FRAME);
		assertTrue(hero.ready);
		assertTrue(Actor.parked());
		assertSame(hero, Actor.currentActor());
	}

	// ---- items 5, 6, 7, 8 ----

	@Test
	public void deadMemberReleasesTheCountdownAtOnce() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(5, 8)).hunt(member);
		frames(3, FRAME);
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		member.die(null);
		assertEquals(TurnClock.WAIT_NONE, TurnClock.waitingOn());
		frames(2, FRAME);
		assertSame(hero, Actor.currentActor());
		assertTrue(hero.ready);
		assertFalse(TurnClock.partyActive());
		assertAlive();
	}

	@Test
	public void closedLinkReleasesTheCountdownAtOnce() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		addDummy(cell(5, 8)).hunt(member);
		frames(3, FRAME);
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(state(), TurnClock.WAIT_GUEST, TurnClock.waitingOn());
		InnOriginalParty.close();
		frames(2, FRAME);
		assertSame(hero, Actor.currentActor());
		assertTrue(hero.ready);
		// The member is now an ordinary ally that spends its turns.
		float memberTime = time(member);
		hero.rest(false);
		frames(3, FRAME);
		assertTrue(time(member) > memberTime);
		assertAlive();
	}

	@Test
	public void memberEngagementSeesADoorOpening() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 8));
		for (int x = 1; x < 14; x++) Level.set(cell(x, 10), Terrain.WALL);
		Level.set(cell(5, 10), Terrain.DOOR);
		Dummy monster = addDummy(cell(5, 12));
		monster.wander();
		frames(3, FRAME);
		hero.rest(false);
		frames(3, FRAME);
		assertEquals("closed door: the member is skipped", TurnClock.WAIT_NONE, TurnClock.waitingOn());
		Level.set(cell(5, 10), Terrain.OPEN_DOOR);
		hero.rest(false);
		frames(3, FRAME);
		assertEquals("open door: the member gets a grace", TurnClock.WAIT_GUEST, TurnClock.waitingOn());
	}

	@Test
	public void commandSentWhileParalysedIsDropped() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		Buff.affect(member, Paralysis.class, 3f);
		int pos = member.pos;
		guest("M", 1, 0);
		frames(3, FRAME);
		assertEquals("the paralysed member's command was not acknowledged", 1, ack());
		for (int i = 0; i < 5; i++) {
			hero.rest(false);
			frames(3, FRAME);
		}
		assertEquals("the dropped command ran after the paralysis", pos, member.pos);
		assertAlive();
	}

	@Test
	public void pendingCommandDoesNotSpinTheSchedulerDuringATimeFreeze() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(10, 10));
		monster.wander();
		frames(3, FRAME);
		Buff.affect(hero, Swiftthistle.TimeBubble.class).reset();
		guest("M", 1, 0);
		int woken = frames(60, FRAME);
		assertTrue("woken " + woken + " times in 60 frames", woken <= 1);
		int pos = member.pos;
		hero.rest(false);
		frames(3, FRAME);
		assertEquals(0, monster.acts);
		assertEquals(pos, member.pos);
	}

	// ---- the host's open targeting survives the partner's turn ----

	static final class Aim extends CellSelector.Listener {
		int cancels;
		Integer chosen;
		@Override public void onSelect(Integer cell) { if (cell == null) cancels++; else chosen = cell; }
		@Override public String prompt() { return "aim"; }
	}

	private static CellSelector selector() throws Exception {
		Field cs = GameScene.class.getDeclaredField("cellSelector");
		cs.setAccessible(true);
		return (CellSelector) cs.get(null);
	}

	@Test
	public void hostTargetingSurvivesThePartnersTurn() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		Dummy monster = addDummy(cell(3, 8));
		monster.hunt(hero);
		frames(3, FRAME);
		Aim aim = new Aim();
		GameScene.selectCell(aim);
		assertEquals("aim", GameScene.cellSelectionPrompt());

		// While the actor thread runs the partner's step, the interface is not live but the prompt stays.
		member.sprite.isMoving = true;
		guest("M", 1, 0);
		TurnClock.update(FRAME);
		Actor.notifyActorThread(actorThread);
		Thread.sleep(50);
		assertFalse(Actor.parked());
		assertFalse(hero.ready);
		assertSame(aim, selector().listener);
		synchronized (member.sprite) {
			member.sprite.isMoving = false;
			member.sprite.notifyAll();
		}
		settle();
		int acts = heroActs;
		frames(4, FRAME);

		assertEquals(state(), TurnClock.WAIT_HOST, TurnClock.waitingOn());
		assertTrue(hero.ready);
		assertSame("the partner's turn cancelled the host's targeting", aim, selector().listener);
		assertEquals(0, aim.cancels);
		assertEquals("aim", GameScene.cellSelectionPrompt());
		assertTrue(selector().enabled);

		// The hero's own new turn still resets the interface, as in the original game.
		hero.rest(false);
		frames(3, FRAME);
		if (TurnClock.waitingOn() != TurnClock.WAIT_HOST) {
			guest("M", -1, 0);
			frames(4, FRAME);
		}
		assertTrue(hero.ready);
		assertEquals(1, aim.cancels);
		assertEquals(null, GameScene.cellSelectionPrompt());
		assertAlive();
	}

	@Test
	public void hostTargetingSurvivesBeingSkipped() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		Aim aim = new Aim();
		GameScene.selectCell(aim);
		for (int i = 0; i < 3; i++) {
			guest("M", i % 2 == 0 ? 1 : -1, 0);
			frames(4, FRAME);
		}
		assertEquals(3, ack());
		assertTrue(hero.ready);
		assertSame(aim, selector().listener);
		assertEquals(0, aim.cancels);
	}

	@Test
	public void hostTargetingEndsWhenItsItemIsGone() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		Rock rock = new Rock();
		hero.belongings.backpack.items.add(rock);
		assertTrue(rock.executeFromUi(hero, Item.AC_THROW));
		assertEquals(Messages.get(Item.class, "prompt"), GameScene.cellSelectionPrompt());
		hero.belongings.backpack.items.remove(rock);
		guest("M", 1, 0);
		frames(4, FRAME);
		assertTrue(hero.ready);
		assertEquals(null, GameScene.cellSelectionPrompt());
	}

	// ---- real monsters still notice, hunt and fight in both modes ----

	private static com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat addRat(int cell) throws Exception {
		com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat rat = new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat();
		rat.pos = cell;
		rat.state = rat.WANDERING;
		rat.spriteClass = IndicatorSprite.class;
		alloc(TestSprite.class).link(rat);
		level.mobs.add(rat);
		Actor.add(rat);
		return rat;
	}

	@Test
	public void realMonsterFightsTheSoloHero() throws Exception {
		newSolo(15, 15, cell(3, 3));
		com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat rat = addRat(cell(3, 6));
		frames(3, FRAME);
		for (int i = 0; i < 40 && hero.HP == hero.HT; i++) {
			if (hero.ready) hero.rest(false);
			frames(3, FRAME);
		}
		assertSame(rat.HUNTING, rat.state);
		assertTrue("the rat never attacked", hero.HP < hero.HT);
		assertAlive();
	}

	@Test
	public void realMonsterEngagesThePartyAndTheClockCountsDown() throws Exception {
		newParty(15, 15, cell(3, 3), cell(10, 3));
		com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat rat = addRat(cell(3, 6));
		frames(3, FRAME);
		boolean countdown = false;
		for (int i = 0; i < 40 && !countdown; i++) {
			guest("W", 0, 0);
			frames(3, FRAME);
			countdown = TurnClock.waitingOn() == TurnClock.WAIT_HOST;
		}
		// An awake rat in the host's view engages the host even before it has noticed anyone.
		assertTrue(state(), countdown);
		assertNotEquals(rat.SLEEPING, rat.state);
		float heroTime = time(hero);
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, countdownSeconds(FRAME, 10), 0.05f);
		frames(2, FRAME);
		assertEquals(heroTime + 1f, time(hero), 1e-4f);
		// Keep the world moving with guest waits and host auto-waits: the rat hunts and fights.
		for (int i = 0; i < 60 && hero.HP == hero.HT && member.HP == member.HT; i++) {
			if (TurnClock.waitingOn() == TurnClock.WAIT_GUEST || TurnClock.waitingOn() == TurnClock.WAIT_NONE) guest("W", 0, 0);
			if (hero.ready) hero.rest(false);
			frames(3, FRAME);
		}
		assertSame(rat.HUNTING, rat.state);
		assertTrue("the rat never attacked anyone", hero.HP < hero.HT || member.HP < member.HT);
		assertAlive();
	}

	// ---- snapshot cadence ----

	@Test
	public void idlePartySendsAHeartbeatAndAnswersCommandsAtOnce() throws Exception {
		newParty(15, 15, cell(3, 3), cell(5, 3));
		frames(3, FRAME);
		sent.clear();
		frames(Math.round(5f / FRAME), FRAME);
		assertTrue("idle snapshots in 5 s: " + sent.size(), sent.size() >= 4 && sent.size() <= 7);
		frames(10, FRAME);
		sent.clear();
		guest("M", 1, 0);
		frames(8, FRAME);
		assertFalse(sent.isEmpty());
		String[] fields = sent.get(0).split("\\|", -1);
		assertEquals("1", fields[7]);
		// Never more than about ten frames per second, even while things change.
		sent.clear();
		for (int i = 0; i < 20; i++) {
			guest("M", i % 2 == 0 ? -1 : 1, 0);
			frames(4, FRAME);
		}
		float seconds = 20 * 4 * FRAME;
		assertTrue("rate " + sent.size() / seconds, sent.size() / seconds <= 10.5f);
	}
}
