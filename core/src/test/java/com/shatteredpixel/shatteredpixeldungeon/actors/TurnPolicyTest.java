// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.shatteredpixel.shatteredpixeldungeon.actors.TurnPolicy.Decision;
import com.shatteredpixel.shatteredpixeldungeon.actors.TurnPolicy.Player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TurnPolicyTest {

	private static final float EPS = 1e-6f;

	private static Player player(float time, boolean ready) {
		Player p = new Player();
		p.time = time;
		p.ready = ready;
		return p;
	}

	@Test
	public void soloIdlePlayerFreezesTheWorld() {
		Player hero = player(3, false);
		hero.engaged = true;
		assertEquals(Decision.FREEZE, TurnPolicy.decide(hero));
		TurnPolicy.apply(Decision.FREEZE, hero);
		assertFalse(hero.grace.running());
		assertFalse(hero.detached);
	}

	@Test
	public void soloPlayerWithInputActs() {
		Player hero = player(3, true);
		assertEquals(Decision.ACT, TurnPolicy.decide(hero));
	}

	@Test
	public void allIdleFreezesWithoutCountdown() {
		Player hero = player(5, false);
		Player member = player(5, false);
		hero.engaged = member.engaged = true;
		assertSame(hero, TurnPolicy.preferReady(hero, hero, member));
		assertEquals(Decision.FREEZE, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.apply(Decision.FREEZE, hero);
		assertFalse(hero.grace.running());
		assertFalse(member.grace.running());
	}

	@Test
	public void idleUnengagedPlayerIsSkippedWhilePartnerIsAhead() {
		Player hero = player(0, false);
		Player member = player(1, false);
		assertTrue(TurnPolicy.someoneAhead(hero, hero, member));
		assertEquals(Decision.SKIP, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.apply(Decision.SKIP, hero);
		assertTrue(hero.detached);
		assertFalse(hero.grace.running());
		// Once the partner is idle too, the skipped player is not "ahead", so the world freezes.
		assertFalse(TurnPolicy.someoneAhead(member, hero, member));
		assertEquals(Decision.FREEZE, TurnPolicy.decide(member, hero, member));
	}

	@Test
	public void skippedPlayerDoesNotHoldTheQueueAndReentersAtNow() {
		Sim sim = new Sim();
		Player hero = sim.add(player(0, false));
		Player member = sim.add(player(0, true));
		// The member keeps acting; the idle, unengaged hero is skipped instead of holding the queue.
		for (int i = 0; i < 100 && sim.now < 6; i++) {
			member.ready = true;
			sim.step();
		}
		assertTrue(hero.detached);
		assertTrue(sim.now >= 5f);
		assertTrue(member.time > sim.now - EPS);
		// It was never charged for the turns it sat out: its clock only followed the world.
		assertTrue(hero.time <= sim.now + EPS);
		float now = sim.now;
		// The hero was never charged for the turns it sat out, and acts at the current time.
		hero.ready = true;
		assertTrue(TurnPolicy.shouldReattach(hero));
		TurnPolicy.reattach(hero, now);
		assertFalse(hero.detached);
		assertEquals(now, hero.time, EPS);
		assertEquals(now, TurnPolicy.reentryTime(0f, now), EPS);
		assertEquals(now + 2, TurnPolicy.reentryTime(now + 2, now), EPS);
	}

	@Test
	public void skippedPlayerRejoinsWhenEngaged() {
		Player hero = player(0, false);
		hero.detached = true;
		assertFalse(TurnPolicy.shouldReattach(hero));
		hero.engaged = true;
		assertTrue(TurnPolicy.shouldReattach(hero));
	}

	@Test
	public void engagedIdlePlayerCountsDownThenWaitsExactlyOneTurn() {
		Player hero = player(4, false);
		Player member = player(5, false);
		hero.engaged = true;
		assertEquals(Decision.COUNTDOWN, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.apply(Decision.COUNTDOWN, hero);
		assertTrue(hero.grace.running());
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, hero.grace.remaining(), EPS);

		assertFalse(hero.grace.tick(4.9f));
		// Re-deciding while blocked keeps the running countdown.
		assertEquals(Decision.COUNTDOWN, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.apply(Decision.COUNTDOWN, hero);
		assertEquals(0.1f, hero.grace.remaining(), 1e-4f);

		assertTrue(hero.grace.tick(0.2f));
		assertEquals(Decision.AUTO_WAIT, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.apply(Decision.AUTO_WAIT, hero);
		assertEquals(5f, hero.time, EPS);
		assertEquals(1f, TurnPolicy.AUTO_WAIT_TURNS, EPS);
		assertFalse(hero.grace.running());
		assertEquals(1, hero.grace.expiries());
	}

	@Test
	public void inputCancelsTheCountdown() {
		Player hero = player(4, false);
		Player member = player(5, false);
		hero.engaged = true;
		TurnPolicy.apply(TurnPolicy.decide(hero, hero, member), hero);
		hero.grace.tick(3f);
		hero.ready = true;
		assertEquals(Decision.ACT, TurnPolicy.decide(hero, hero, member));
		TurnPolicy.acting(hero, true);
		assertFalse(hero.grace.running());
		assertFalse(hero.waiting);
	}

	@Test
	public void afkShortensGraceAfterTwoExpiriesAndInputResetsIt() {
		Player hero = player(0, false);
		Player member = player(10, false);
		hero.engaged = true;
		for (int expiry = 0; expiry < TurnPolicy.AFK_EXPIRIES; expiry++) {
			TurnPolicy.apply(TurnPolicy.decide(hero, hero, member), hero);
			assertEquals(TurnPolicy.TURN_GRACE_SECONDS, hero.grace.remaining(), EPS);
			assertTrue(hero.grace.tick(TurnPolicy.TURN_GRACE_SECONDS));
			assertEquals(Decision.AUTO_WAIT, TurnPolicy.decide(hero, hero, member));
			TurnPolicy.apply(Decision.AUTO_WAIT, hero);
			// The automatic turn is not input.
			TurnPolicy.acting(hero, false);
		}
		assertTrue(hero.grace.afk());
		TurnPolicy.apply(TurnPolicy.decide(hero, hero, member), hero);
		assertEquals(TurnPolicy.AFK_GRACE_SECONDS, hero.grace.remaining(), EPS);
		assertTrue(hero.grace.tick(TurnPolicy.AFK_GRACE_SECONDS));
		TurnPolicy.apply(TurnPolicy.decide(hero, hero, member), hero);
		assertTrue(hero.grace.afk());

		// Any input restores the full grace.
		hero.ready = true;
		TurnPolicy.acting(hero, true);
		hero.ready = false;
		assertFalse(hero.grace.afk());
		TurnPolicy.apply(TurnPolicy.decide(hero, hero, member), hero);
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, hero.grace.remaining(), EPS);
	}

	@Test
	public void tieBetweenPlayersGoesToTheReadyOne() {
		Player hero = player(2, false);
		Player member = player(2, true);
		assertSame(member, TurnPolicy.preferReady(hero, hero, member));
		hero.ready = true;
		assertSame(hero, TurnPolicy.preferReady(hero, hero, member));
		// Only exact ties count.
		Player late = player(3, true);
		hero.ready = false;
		assertSame(hero, TurnPolicy.preferReady(hero, hero, late));
	}

	@Test
	public void deadPlayerIsExcluded() {
		Player hero = player(2, false);
		Player member = player(6, false);
		member.alive = false;
		hero.engaged = true;
		assertFalse(TurnPolicy.someoneAhead(hero, hero, member));
		assertEquals(Decision.FREEZE, TurnPolicy.decide(hero, hero, member));

		Player deadReady = player(2, true);
		deadReady.alive = false;
		assertSame(hero, TurnPolicy.preferReady(hero, hero, deadReady));

		deadReady.detached = true;
		assertFalse(TurnPolicy.shouldReattach(deadReady));
	}

	@Test
	public void timeStopAlwaysFreezes() {
		Player hero = player(2, false);
		Player member = player(4, false);
		hero.engaged = true;
		hero.stopsTime = true;
		assertEquals(Decision.FREEZE, TurnPolicy.decide(hero, hero, member));
		member.ready = true;
		member.time = 2;
		assertSame(hero, TurnPolicy.preferReady(hero, hero, member));
	}

	@Test
	public void graceOnlyTicksWhileRunning() {
		TurnPolicy.Grace grace = new TurnPolicy.Grace();
		assertFalse(grace.tick(100f));
		assertFalse(grace.running());
		assertEquals(0f, grace.remaining(), EPS);
		grace.start();
		grace.tick(-1f);
		assertEquals(TurnPolicy.TURN_GRACE_SECONDS, grace.remaining(), EPS);
		grace.cancel();
		assertFalse(grace.expired());
	}

	/**
	 * A minimal model of the actor queue: players plus one ordinary actor that always acts and spends a
	 * turn. It follows the same order as {@code Actor.process()}: earliest time, players before ordinary
	 * actors on ties, the ready player first between players, skipped players excluded.
	 */
	private static final class Sim {
		final List<Player> players = new ArrayList<>();
		float mobTime;
		float now;

		Player add(Player p) {
			players.add(p);
			return p;
		}

		void step() {
			// Like Actor.process(): a skipped player's clock follows the current time.
			for (Player p : players) {
				if (p.detached) p.time = TurnPolicy.reentryTime(p.time, now);
			}
			Player chosen = null;
			for (Player p : players) {
				if (!p.schedulable()) continue;
				if (chosen == null || p.time < chosen.time) chosen = p;
			}
			if (chosen != null && chosen.time <= mobTime) {
				chosen = TurnPolicy.preferReady(chosen, players.toArray(new Player[0]));
				now = chosen.time;
				Decision decision = TurnPolicy.decide(chosen, players.toArray(new Player[0]));
				if (decision == Decision.ACT) {
					TurnPolicy.acting(chosen, true);
					chosen.time += 1;
					chosen.ready = false;
				} else {
					TurnPolicy.apply(decision, chosen);
					if (decision == Decision.FREEZE || decision == Decision.COUNTDOWN) return;
				}
			} else {
				now = mobTime;
				mobTime += 1;
			}
		}
	}
}
