// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnEncounter;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

import java.util.Arrays;

/**
 * Engine side of the party turn clock. The rules live in {@link TurnPolicy}; this class maps the hero and
 * the host's {@link InnPartyMember} onto policy players, applies decisions to the actor queue and runs the
 * wall-clock grace countdown from the render thread.
 *
 * Solo play never reaches these decisions: without an active party member {@link Actor#process()} uses the
 * original scheduling path unchanged.
 *
 * Threading: the scheduling methods run on the actor thread. The render thread only polls
 * {@link #update(float)} and the HUD queries. The lock guards the policy state and is never held while
 * calling into the engine.
 */
public final class TurnClock {

	/** Whose input the world is waiting for while a grace countdown runs. */
	public static final int WAIT_NONE = 0;
	public static final int WAIT_HOST = 1;
	public static final int WAIT_GUEST = 2;

	/** Shown above a player who waited automatically when the grace ran out. */
	public static final String AUTO_WAIT_LABEL = "대기";

	private static final Object LOCK = new Object();
	private static final TurnPolicy.Player heroState = new TurnPolicy.Player();
	private static final TurnPolicy.Player memberState = new TurnPolicy.Player();

	// Set by the actor thread before it parks on a player; cleared when the scheduler is released.
	private static volatile Char blocked;
	private static volatile boolean countdown;
	// The hero's UI is disabled while the world runs past a skipped hero.
	private static volatile boolean heroSuspended;
	private static volatile boolean partyActive;

	// Actor thread only.
	private static boolean[] memberFov;
	private static int memberFovPos = -1;
	private static Level memberFovLevel;

	private TurnClock() {}

	public static void reset() {
		synchronized (LOCK) {
			resetPlayer(heroState);
			resetPlayer(memberState);
			blocked = null;
			countdown = false;
			heroSuspended = false;
			partyActive = false;
		}
		memberFov = null;
		memberFovPos = -1;
		memberFovLevel = null;
	}

	private static void resetPlayer(TurnPolicy.Player p) {
		p.detached = p.waiting = p.engaged = p.ready = p.stopsTime = false;
		p.alive = true;
		p.grace.input();
	}

	/** The host's party member while it takes part in scheduling, otherwise null. */
	public static InnPartyMember activeMember() {
		InnOriginalParty party = InnOriginalParty.current();
		if (party == null || Dungeon.level == null) return null;
		// A member that can no longer take commands is an ordinary ally that spends its turns.
		if (!party.acceptsCommands()) return null;
		InnPartyMember member = party.activeMember(Dungeon.level);
		return member != null && Actor.findById(member.id()) == member ? member : null;
	}

	/** True while the party turn clock schedules the hero and a party member. */
	public static boolean partyActive() {
		return partyActive;
	}

	static boolean isPlayer(Actor actor, Hero hero, InnPartyMember member) {
		return actor != null && (actor == hero || actor == member);
	}

	private static TurnPolicy.Player state(Actor actor) {
		return actor instanceof Hero ? heroState : memberState;
	}

	/** A skipped player does not hold the queue; its time does not pin the scheduler or fixTime. */
	static boolean detached(Actor actor) {
		if (!(actor instanceof Hero) && !(actor instanceof InnPartyMember)) return false;
		synchronized (LOCK) {
			return state(actor).detached;
		}
	}

	// ---- engine queries ----

	static boolean heroIdle(Hero hero) {
		return hero.ready && hero.curAction == null && !hero.resting && hero.paralysed == 0;
	}

	private static boolean heroHasInput(Hero hero) {
		return hero.curAction != null || hero.resting || (!hero.ready && !heroSuspended);
	}

	static boolean hasReadyAction(Actor actor) {
		if (actor instanceof Hero) {
			Hero hero = (Hero) actor;
			return hero.paralysed > 0 || heroHasInput(hero);
		}
		if (actor instanceof InnPartyMember) {
			InnPartyMember member = (InnPartyMember) actor;
			InnOriginalParty party = InnOriginalParty.current();
			// A member that cannot take commands spends its turns instead of blocking the world.
			return member.paralysed > 0 || party == null || !party.acceptsCommands() || party.hasPendingCommand();
		}
		return true;
	}

	private static boolean stopsTime(Actor actor) {
		return actor instanceof Hero && (((Hero) actor).buff(TimekeepersHourglass.timeFreeze.class) != null
				|| ((Hero) actor).buff(Swiftthistle.TimeBubble.class) != null);
	}

	private static boolean engaged(Actor actor) {
		Level level = Dungeon.level;
		if (level == null) return false;
		if (actor instanceof Hero) {
			Hero hero = (Hero) actor;
			return InnEncounter.engaged(hero, hero.fieldOfView != null ? hero.fieldOfView : level.heroFOV, level);
		}
		InnPartyMember member = (InnPartyMember) actor;
		if (!member.isAlive()) return false;
		if (memberFov == null || memberFovLevel != level || memberFovPos != member.pos
				|| memberFov.length != level.length()) {
			if (memberFov == null || memberFov.length != level.length()) memberFov = new boolean[level.length()];
			else Arrays.fill(memberFov, false);
			level.updateFieldOfView(member, memberFov);
			memberFovLevel = level;
			memberFovPos = member.pos;
		}
		return InnEncounter.engaged(member, memberFov, level);
	}

	/** Engine facts about one player, gathered without holding the lock. */
	private static final class Facts {
		final float time;
		final boolean alive, ready, stopsTime, engaged;

		Facts(Actor actor, boolean withEngagement) {
			time = Actor.timeOf(actor);
			alive = ((Char) actor).isAlive();
			ready = hasReadyAction(actor);
			stopsTime = stopsTime(actor);
			engaged = withEngagement && alive && engaged(actor);
		}

		void into(TurnPolicy.Player p) {
			p.time = time;
			p.alive = alive;
			p.ready = ready;
			p.stopsTime = stopsTime;
			p.engaged = engaged;
		}
	}

	// ---- scheduling, actor thread ----

	/**
	 * Start of every scheduler pass in party play: skipped players follow the current time and rejoin the
	 * queue once they have input or become engaged.
	 */
	static void beginPass(Hero hero, InnPartyMember member, float now) {
		partyActive = true;
		blocked = null;
		countdown = false;
		for (Actor actor : new Actor[]{hero, member}) {
			if (!detached(actor)) continue;
			if (Actor.timeOf(actor) < now) Actor.setTime(actor, now);
			Facts facts = new Facts(actor, true);
			boolean rejoin;
			synchronized (LOCK) {
				TurnPolicy.Player p = state(actor);
				facts.into(p);
				rejoin = TurnPolicy.shouldReattach(p);
			}
			if (rejoin) reattach(actor, now);
		}
	}

	private static void reattach(Actor actor, float now) {
		synchronized (LOCK) {
			TurnPolicy.reattach(state(actor), now);
		}
		Actor.setTime(actor, TurnPolicy.reentryTime(Actor.timeOf(actor), now));
		if (actor instanceof Hero && heroSuspended) {
			Hero hero = (Hero) actor;
			heroSuspended = false;
			// The world moved while the hero was skipped: refresh what the hero sees before input resumes.
			Dungeon.observe();
			hero.checkVisibleMobs();
			hero.ready = true;
		}
	}

	/** Tie rule: between players at the same time, the one with an action ready goes first. */
	static Actor preferReady(Actor chosen, Hero hero, InnPartyMember member) {
		if (!isPlayer(chosen, hero, member)) return chosen;
		Actor other = chosen == hero ? member : hero;
		Facts chosenFacts = new Facts(chosen, false);
		Facts otherFacts = new Facts(other, false);
		synchronized (LOCK) {
			chosenFacts.into(state(chosen));
			otherFacts.into(state(other));
			return TurnPolicy.preferReady(state(chosen), state(other)) == state(chosen) ? chosen : other;
		}
	}

	/** A player is about to act with a ready action. */
	static void acting(Actor actor) {
		boolean input;
		if (actor instanceof Hero) {
			Hero hero = (Hero) actor;
			boolean waiting;
			synchronized (LOCK) {
				waiting = heroState.waiting;
			}
			// A new turn starts with ready unset; only an action the player chose counts as input.
			input = hero.paralysed == 0 && (hero.curAction != null || hero.resting || waiting && !hero.ready);
		} else {
			InnOriginalParty party = InnOriginalParty.current();
			input = ((InnPartyMember) actor).paralysed == 0 && party != null && party.hasPendingCommand();
			memberFovPos = -1;
		}
		synchronized (LOCK) {
			TurnPolicy.acting(state(actor), input);
		}
	}

	/** Result of a decision for an idle player: the actor the scheduler holds (or null) and doNext. */
	static final class Decided {
		final Actor hold;
		final boolean doNext;

		Decided(Actor hold, boolean doNext) {
			this.hold = hold;
			this.doNext = doNext;
		}
	}

	/** Decides for an idle player that is the earliest actor. */
	static Decided decideIdle(Actor idle, Hero hero, InnPartyMember member, float now) {
		Actor other = idle == hero ? member : hero;
		Facts otherFacts = new Facts(other, false);
		Facts idleFacts = new Facts(idle, false);
		TurnPolicy.Decision decision;
		boolean needsEngagement;
		synchronized (LOCK) {
			otherFacts.into(state(other));
			idleFacts.into(state(idle));
			needsEngagement = !idleFacts.ready && !idleFacts.stopsTime
					&& TurnPolicy.someoneAhead(state(idle), state(other));
		}
		boolean isEngaged = needsEngagement && engaged(idle);
		boolean otherDetached;
		synchronized (LOCK) {
			TurnPolicy.Player p = state(idle);
			p.engaged = isEngaged;
			decision = TurnPolicy.decide(p, state(other));
			TurnPolicy.apply(decision, p);
			otherDetached = state(other).detached && state(other).alive;
		}
		switch (decision) {
			case FREEZE:
				// Everyone idle at this time waits together; hold the hero, as the original game does.
				if (otherDetached) reattach(other, now);
				Actor hold = hero.isAlive() && Actor.timeOf(hero) <= now && heroIdle(hero) ? hero : idle;
				blocked = (Char) hold;
				return new Decided(hold, false);
			case SKIP:
				if (idle == hero) {
					heroSuspended = true;
					hero.ready = false;
				}
				return new Decided(null, true);
			case COUNTDOWN:
				countdown = true;
				blocked = (Char) idle;
				return new Decided(idle, false);
			case AUTO_WAIT:
				if (idle == hero) {
					hero.rest(false);
				} else {
					member.waitOneTurn();
				}
				CharSprite sprite = ((Char) idle).sprite;
				if (sprite != null) sprite.showStatus(CharSprite.NEUTRAL, AUTO_WAIT_LABEL);
				// Yield a frame, like any hero action, before the queue continues.
				return new Decided(null, false);
			case ACT:
			default:
				return new Decided(idle, true);
		}
	}

	/** Leaving party play (solo, the member died or left): the hero holds the queue again. */
	static void endParty(Hero hero, float now) {
		if (!partyActive) return;
		boolean heroDetached = detached(hero);
		if (hero != null && heroDetached && Actor.timeOf(hero) < now) Actor.setTime(hero, now);
		if (hero != null && heroSuspended) hero.ready = true;
		reset();
	}

	// ---- render thread ----

	/**
	 * Polls the blocked player from the render thread: releases the scheduler on input, and runs the grace
	 * countdown. Only called while the host's game scene updates, so the countdown stops while it is paused.
	 */
	public static void update(float elapsed) {
		Char held = blocked;
		if (held == null || !Actor.parked() || Actor.currentActor() != held) return;
		Hero hero = Dungeon.hero;
		InnPartyMember member = activeMember();
		boolean heroInput = hero != null && heroHasInput(hero);
		boolean memberInput = member != null && hasReadyAction(member);
		boolean release;
		synchronized (LOCK) {
			if (blocked != held) return;
			if (countdown) {
				// Partners ahead keep their queued input; only the waited-for player or the clock releases.
				release = held == hero ? heroInput : memberInput;
				// Clamp long frames so a stall does not consume the whole grace at once.
				if (!release) release = state(held).grace.tick(Math.min(elapsed, 0.1f));
			} else {
				release = heroInput || memberInput;
			}
			if (release) {
				blocked = null;
				countdown = false;
			}
		}
		if (release) Actor.release(held);
	}

	/** Whose input the world is waiting for with a countdown: {@link #WAIT_HOST}, {@link #WAIT_GUEST} or none. */
	public static int waitingOn() {
		Char held = blocked;
		if (held == null || !countdown) return WAIT_NONE;
		return held instanceof Hero ? WAIT_HOST : WAIT_GUEST;
	}

	/** Seconds left in the running countdown, or 0. */
	public static float graceRemaining() {
		synchronized (LOCK) {
			Char held = blocked;
			if (held == null || !countdown) return 0f;
			return state(held).grace.remaining();
		}
	}
}
