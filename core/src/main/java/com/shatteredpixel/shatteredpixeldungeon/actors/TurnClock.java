// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnEncounter;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

/**
 * Engine side of the party turn clock. The rules live in {@link TurnPolicy}; this class maps the hero and
 * the host's {@link InnPartyMember} onto policy players, applies decisions to the actor queue and runs the
 * wall-clock grace countdown from the render thread.
 *
 * Solo play never reaches these decisions: without an active party member {@link Actor#process()} uses the
 * original scheduling path unchanged.
 *
 * Invariant kept from the original game: {@code hero.ready} is only true while the actor thread is parked
 * holding the hero, so every ready-gated interface path stays safe. When the partner is allowed to act while
 * the hero waits, the hero's ready flag is cleared first ("suspended") and restored by the next decision.
 * Hero input always ends in {@code hero.next()}; this class never releases the scheduler on the hero's state,
 * and a hero that is held but not ready has an action in flight (a thrown item, a zap) that ends with its
 * own {@code next()}.
 *
 * Threading: the scheduling methods run on the actor thread. The render thread only calls
 * {@link #update(float)} and the HUD queries. The lock guards the policy state and is never held while
 * calling into the engine.
 */
public final class TurnClock {

	/** Whose input the world is waiting for while a grace countdown runs. */
	public static final int WAIT_NONE = 0;
	public static final int WAIT_HOST = 1;
	public static final int WAIT_GUEST = 2;

	/** Longest render frame counted against a grace; longer gaps (a paused or stalled game) are discarded. */
	public static final float MAX_FRAME_SECONDS = 1f;

	private static final Object LOCK = new Object();
	private static final TurnPolicy.Player heroState = new TurnPolicy.Player();
	private static final TurnPolicy.Player memberState = new TurnPolicy.Player();

	// Set by the actor thread before it parks on a player; cleared when the scheduler is released.
	private static volatile Char blocked;
	private static volatile boolean countdown;
	// hero.ready was cleared by the clock while the hero waits without input (see the class comment).
	private static volatile boolean heroSuspended;
	// The member's time when the hero was suspended for the member's command during a freeze.
	private static volatile float suspendedAtMemberTime = Float.NaN;
	private static volatile boolean partyActive;
	// The hero takes a Hero.act() only to refresh its view after the partner acted: keep its open targeting.
	private static volatile boolean refreshingHero;

	private TurnClock() {}

	public static void reset() {
		synchronized (LOCK) {
			resetPlayer(heroState);
			resetPlayer(memberState);
			blocked = null;
			countdown = false;
			heroSuspended = false;
			partyActive = false;
			refreshingHero = false;
		}
	}

	private static void resetPlayer(TurnPolicy.Player p) {
		p.detached = p.waiting = p.engaged = p.ready = p.stopsTime = false;
		p.alive = true;
		p.waitingSince = 0;
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

	/** True while the hero's current act() only refreshes its turn after the partner acted. */
	public static boolean refreshingHero() {
		return refreshingHero;
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

	/**
	 * True if the hero has something to do this turn: chosen input, a multi-turn action, paralysis, or a turn
	 * that has not been evaluated yet (ready unset by the hero's own last action, not by the clock).
	 */
	static boolean hasReadyAction(Actor actor) {
		if (actor instanceof Hero) {
			Hero hero = (Hero) actor;
			return hero.paralysed > 0 || hero.curAction != null || hero.resting || (!hero.ready && !heroSuspended);
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
			boolean[] fov = hero.fieldOfView != null ? hero.fieldOfView : level.heroFOV;
			return InnEncounter.engaged(hero, () -> fov, level);
		}
		InnPartyMember member = (InnPartyMember) actor;
		// Recomputed whenever it is needed: doors and other terrain can change around a member standing still.
		return InnEncounter.engaged(member, () -> {
			boolean[] fov = new boolean[level.length()];
			level.updateFieldOfView(member, fov);
			return fov;
		}, level);
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
			TurnPolicy.spent(p, time);
			p.alive = alive;
			p.ready = ready;
			p.stopsTime = stopsTime;
			p.engaged = engaged;
		}
	}

	// ---- scheduling, actor thread ----

	/**
	 * Start of every scheduler pass in party play: notices time the players spent through the interface,
	 * lets skipped players follow the current time and rejoins them once they have input or become engaged.
	 */
	static void beginPass(Hero hero, InnPartyMember member, float now) {
		partyActive = true;
		blocked = null;
		countdown = false;
		for (Actor actor : new Actor[]{hero, member}) {
			if (!detached(actor)) {
				Facts facts = new Facts(actor, false);
				synchronized (LOCK) {
					TurnPolicy.spent(state(actor), facts.time);
				}
				continue;
			}
			if (Actor.timeOf(actor) < now) Actor.setTime(actor, now);
			Facts facts = new Facts(actor, true);
			boolean rejoin;
			synchronized (LOCK) {
				TurnPolicy.Player p = state(actor);
				TurnPolicy.follow(p, facts.time);
				facts.into(p);
				rejoin = TurnPolicy.shouldReattach(p);
			}
			if (rejoin) reattach(actor, now);
		}
	}

	private static void reattach(Actor actor, float now) {
		Actor.setTime(actor, TurnPolicy.reentryTime(Actor.timeOf(actor), now));
		synchronized (LOCK) {
			TurnPolicy.reattach(state(actor), now);
		}
		if (actor instanceof Hero) {
			// The world moved on while the hero sat out: it takes a fresh turn through Hero.act(), which observes,
			// refreshes the visible enemies and the attack indicator, and readies the interface.
			heroSuspended = false;
			refreshingHero = true;
			((Hero) actor).ready = false;
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
		if (((Char) actor).paralysed > 0) {
			synchronized (LOCK) {
				TurnPolicy.automatic(state(actor));
			}
		}
	}

	/** A player finished its act(): time it spent counts as input. */
	static void acted(Actor actor) {
		if (actor instanceof Hero) refreshingHero = false;
		float time = Actor.timeOf(actor);
		synchronized (LOCK) {
			TurnPolicy.spent(state(actor), time);
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
		boolean heroTurnStale = false;
		if (idle == hero && heroSuspended) {
			if (member != null && Actor.timeOf(member) == suspendedAtMemberTime) {
				// The partner's command spent no time, so nothing else could act either: the world is exactly as
				// the hero left it. Give the interface back and decide again.
				heroSuspended = false;
				hero.ready = true;
			} else {
				// The partner and the world acted while the hero's interface was taken away.
				heroTurnStale = true;
			}
		}
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
		boolean heroDetached;
		synchronized (LOCK) {
			TurnPolicy.Player p = state(idle);
			p.engaged = isEngaged;
			decision = TurnPolicy.decide(p, state(other));
			TurnPolicy.apply(decision, p);
			heroDetached = heroState.detached && heroState.alive;
		}
		if (heroTurnStale && (decision == TurnPolicy.Decision.FREEZE || decision == TurnPolicy.Decision.COUNTDOWN)) {
			// The hero will wait for input again: it gets its turn back through the normal Hero.act(), which
			// observes, refreshes visible enemies and the attack indicator, readies the interface, and is then
			// decided on again (a running grace keeps running).
			heroSuspended = false;
			refreshingHero = true;
			return new Decided(null, true);
		}
		switch (decision) {
			case FREEZE:
				if (idle == member && heroDetached) {
					// Everyone idle waits together, held on the hero as in the original game: the skipped hero
					// rejoins and takes its turn first.
					reattach(hero, now);
					return new Decided(null, true);
				}
				Char partner = idle == hero ? member : hero;
				if (partner.isAlive() && detached(partner)) reattach(partner, now);
				blocked = (Char) idle;
				return new Decided(idle, false);
			case SKIP:
				if (idle == hero) {
					suspendedAtMemberTime = Float.NaN;
					heroSuspended = true;
					hero.ready = false;
				}
				return new Decided(null, true);
			case COUNTDOWN:
				countdown = true;
				blocked = (Char) idle;
				return new Decided(idle, false);
			case AUTO_WAIT:
				String label = Messages.get(TurnClock.class, "auto_wait");
				if (idle == hero) {
					heroSuspended = false;
					hero.rest(false, label);
				} else {
					member.waitOneTurn();
					CharSprite sprite = member.sprite;
					if (sprite != null) sprite.showStatus(CharSprite.NEUTRAL, label);
				}
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
		if (hero != null && detached(hero) && Actor.timeOf(hero) < now) Actor.setTime(hero, now);
		if (hero != null && heroSuspended) {
			// Take a normal turn so the interface is readied through Hero.act().
			heroSuspended = false;
		}
		reset();
	}

	// ---- render thread ----

	/**
	 * Polls the blocked player from the render thread. Releases the scheduler when the waited-for member
	 * sends a command, when a held member is gone, when the partner may act during a freeze, or when the grace
	 * runs out. Only called while the host's game scene updates, so the countdown stops while it is paused.
	 *
	 * @param seconds wall-clock seconds since the previous frame
	 */
	public static void update(float seconds) {
		Char held = blocked;
		if (held == null || !Actor.parked() || Actor.currentActor() != held) return;
		Hero hero = Dungeon.hero;
		if (held == hero && !hero.ready) {
			// The hero started an action that finishes in a later callback: it ends with the hero's own next().
			return;
		}
		InnPartyMember member = activeMember();
		boolean gone = held != hero && (held != member || !held.isAlive());
		boolean memberInput = member != null && member.isAlive() && hasReadyAction(member);
		float memberTime = member == null ? Float.NaN : Actor.timeOf(member);
		boolean partnerMayAct = held == hero && memberInput && !stopsTime(hero) && memberTime <= Actor.timeOf(hero);
		boolean release;
		boolean suspend = false;
		synchronized (LOCK) {
			if (blocked != held) return;
			if (gone) {
				release = true;
			} else if (countdown) {
				boolean expired = state(held).grace.tick(Math.max(0f, Math.min(seconds, MAX_FRAME_SECONDS)));
				// Partners ahead keep their queued input; only the waited-for member's command or the clock releases.
				release = expired || held == member && memberInput;
			} else {
				suspend = partnerMayAct;
				release = partnerMayAct || held != hero && memberInput;
			}
			if (release) {
				blocked = null;
				countdown = false;
				if (suspend) {
					heroSuspended = true;
					suspendedAtMemberTime = memberTime;
				}
			}
		}
		// The actor thread is parked holding the hero, so taking the interface away here is race free.
		if (suspend) hero.ready = false;
		if (release) Actor.release(held);
	}

	/** Whose input the world is waiting for with a countdown: {@link #WAIT_HOST}, {@link #WAIT_GUEST} or none. */
	public static int waitingOn() {
		Char held = blocked;
		if (held == null || !countdown || !held.isAlive()) return WAIT_NONE;
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
