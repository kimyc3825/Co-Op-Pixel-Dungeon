// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

/**
 * Rules of the action-driven party turn clock, kept free of engine and libGDX types so they can be
 * unit tested. Game time only advances because players act; this class decides what the scheduler
 * does when a player is the earliest actor in the queue.
 *
 * <ul>
 *   <li>A player with an action ready acts and pays its normal turn time.</li>
 *   <li>An idle player with no living player ahead freezes the world, as in the original game.</li>
 *   <li>An idle player who is not engaged is skipped while a partner is ahead. A skipped player does not
 *       hold the queue and later acts at the current time.</li>
 *   <li>An idle engaged player gets a wall-clock grace countdown; on expiry it waits exactly one turn.
 *       After {@link #AFK_EXPIRIES} expiries without input the grace shortens to {@link #AFK_GRACE_SECONDS}.</li>
 * </ul>
 */
public final class TurnPolicy {

	public static final float TURN_GRACE_SECONDS = 5f;
	public static final float AFK_GRACE_SECONDS = 0.5f;
	public static final int AFK_EXPIRIES = 2;
	/** Turn time spent by an automatic wait, the original rest action. */
	public static final float AUTO_WAIT_TURNS = 1f;

	public enum Decision {
		/** The player has an action ready and takes its turn. */
		ACT,
		/** Nobody is ahead: the world waits for input without a countdown. */
		FREEZE,
		/** The player stops holding the queue and re-enters at the current time when it acts. */
		SKIP,
		/** The world waits for this player while its grace countdown runs. */
		COUNTDOWN,
		/** The grace expired: the player waits one turn. */
		AUTO_WAIT
	}

	private TurnPolicy() {}

	/** Scheduler-visible state of one player. The scheduler refreshes the fields before each decision. */
	public static final class Player {
		public float time;
		public boolean alive = true;
		/** Has an action ready (input, a queued multi-turn action, or an automatic action such as paralysis). */
		public boolean ready;
		/** A living hostile mob hunts this player, or is awake inside the player's field of view. */
		public boolean engaged;
		/** The player stopped holding the queue (see {@link Decision#SKIP}). */
		public boolean detached;
		/** The player stops time for everyone else (time freeze effects); the world always waits for them. */
		public boolean stopsTime;
		/** The player was idle at its last decision; its next voluntary action counts as input. */
		public boolean waiting;
		public final Grace grace = new Grace();

		/** True while this player takes part in scheduling. */
		public boolean schedulable() {
			return alive && !detached;
		}
	}

	/** Wall-clock grace countdown of one player. */
	public static final class Grace {
		private float remaining = -1f;
		private int expiries;

		public boolean running() {
			return remaining >= 0f;
		}

		/** Seconds left, or 0 when no countdown runs. */
		public float remaining() {
			return Math.max(0f, remaining);
		}

		public boolean afk() {
			return expiries >= AFK_EXPIRIES;
		}

		public int expiries() {
			return expiries;
		}

		/** Starts the countdown unless one is already running. */
		public void start() {
			if (!running()) remaining = afk() ? AFK_GRACE_SECONDS : TURN_GRACE_SECONDS;
		}

		/** Advances a running countdown; returns true once it has expired. */
		public boolean tick(float seconds) {
			if (!running()) return false;
			if (seconds > 0f) remaining = Math.max(0f, remaining - seconds);
			return remaining <= 0f;
		}

		public boolean expired() {
			return running() && remaining <= 0f;
		}

		/** Any input from the player cancels the countdown and the AFK shortening. */
		public void input() {
			remaining = -1f;
			expiries = 0;
		}

		/** The countdown ended in an automatic wait. */
		public void autoWaited() {
			remaining = -1f;
			expiries++;
		}

		/** The world stopped waiting for this player without input or expiry. */
		public void cancel() {
			remaining = -1f;
		}
	}

	/** True if another living, scheduled player has already committed past {@code p}'s time. */
	public static boolean someoneAhead(Player p, Player... players) {
		for (Player q : players) {
			if (q != null && q != p && q.schedulable() && q.time > p.time) return true;
		}
		return false;
	}

	/** Decision for {@code p}, the earliest actor in the queue. */
	public static Decision decide(Player p, Player... players) {
		if (p.ready) return Decision.ACT;
		if (p.stopsTime || !someoneAhead(p, players)) return Decision.FREEZE;
		if (!p.engaged) return Decision.SKIP;
		return p.grace.expired() ? Decision.AUTO_WAIT : Decision.COUNTDOWN;
	}

	/** Applies the bookkeeping that goes with a decision for an idle player. */
	public static void apply(Decision decision, Player p) {
		switch (decision) {
			case FREEZE:
				p.grace.cancel();
				p.waiting = true;
				break;
			case SKIP:
				p.grace.cancel();
				p.detached = true;
				p.waiting = true;
				break;
			case COUNTDOWN:
				p.grace.start();
				p.waiting = true;
				break;
			case AUTO_WAIT:
				p.grace.autoWaited();
				p.waiting = false;
				p.time += AUTO_WAIT_TURNS;
				break;
			case ACT:
			default:
				break;
		}
	}

	/**
	 * Called when {@code p} is about to act with a ready action. {@code input} says whether the action came
	 * from the player (rather than an automatic action such as paralysis); input cancels the grace countdown
	 * and the AFK shortening.
	 */
	public static void acting(Player p, boolean input) {
		p.waiting = false;
		if (input) p.grace.input();
	}

	/** Among players tied at the earliest time, the one with a ready action goes first. */
	public static Player preferReady(Player chosen, Player... players) {
		if (chosen.ready || chosen.stopsTime) return chosen;
		for (Player q : players) {
			if (q != null && q != chosen && q.schedulable() && q.ready && q.time == chosen.time) return q;
		}
		return chosen;
	}

	/** A skipped player rejoins the queue once it has an action ready or becomes engaged. */
	public static boolean shouldReattach(Player p) {
		return p.detached && p.alive && (p.ready || p.engaged);
	}

	/** Time at which a skipped player re-enters the queue. */
	public static float reentryTime(float playerTime, float now) {
		return Math.max(playerTime, now);
	}

	public static void reattach(Player p, float now) {
		p.detached = false;
		p.time = reentryTime(p.time, now);
	}
}
