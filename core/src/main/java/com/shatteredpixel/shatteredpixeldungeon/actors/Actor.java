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

package com.shatteredpixel.shatteredpixeldungeon.actors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.SparseArray;

import java.util.HashSet;

public abstract class Actor implements Bundlable {
	
	public static final float TICK	= 1f;

	private float time;

	private int id = 0;

	//default priority values for general actor categories
	//note that some specific actors pick more specific values
	//e.g. a buff acting before all normal buffs might have priority BUFF_PRIO + 1
	protected static final int VFX_PRIO    = 100;   //visual effects take priority
	protected static final int HERO_PRIO   = 0;     //positive is before hero, negative after
	protected static final int BLOB_PRIO   = -10;   //blobs act after hero, before mobs
	protected static final int MOB_PRIO    = -20;   //mobs act between buffs and blobs
	protected static final int BUFF_PRIO   = -30;   //buffs act last in a turn
	private static final int   DEFAULT     = -100;  //if no priority is given, act after all else

	//used to determine what order actors act in if their time is equal. Higher values act earlier.
	protected int actPriority = DEFAULT;

	protected abstract boolean act();

	//Always spends exactly the specified amount of time, regardless of time-influencing factors
	protected void spendConstant( float time ){
		this.time += time;
		//if time is very close to a whole number, round to a whole number to fix errors
		float ex = Math.abs(this.time % 1f);
		if (ex < .001f){
			this.time = Math.round(this.time);
		}
	}

	//sends time, but the amount can be influenced
	protected void spend( float time ) {
		spendConstant( time );
	}

	public void spendToWhole(){
		time = (float)Math.ceil(time);
	}
	
	protected void postpone( float time ) {
		if (this.time < now + time) {
			this.time = now + time;
			//if time is very close to a whole number, round to a whole number to fix errors
			float ex = Math.abs(this.time % 1f);
			if (ex < .001f){
				this.time = Math.round(this.time);
			}
		}
	}
	
	public float cooldown() {
		return time - now;
	}

	public void clearTime() {
		spendConstant(-Actor.now());
		if (this instanceof Char){
			for (Buff b : ((Char) this).buffs()){
				b.spendConstant(-Actor.now());
			}
		}
	}

	public void timeToNow() {
		time = now;
	}

	//used when now is being cleared as a part of static fixTime()
	public void fixTime(float decrement){
		time -= decrement;
	}
	
	protected void diactivate() {
		time = Float.MAX_VALUE;
	}
	
	protected void onAdd() {}
	
	protected void onRemove() {}

	private static final String TIME    = "time";
	private static final String ID      = "id";

	@Override
	public void storeInBundle( Bundle bundle ) {
		bundle.put( TIME, time );
		bundle.put( ID, id );
	}

	@Override
	public void restoreFromBundle( Bundle bundle ) {
		time = bundle.getFloat( TIME );
		int incomingID = bundle.getInt( ID );
		if (Actor.findById(incomingID) == null){
			id = incomingID;
		} else {
			id = nextID++;
		}
	}

	public int id() {
		if (id > 0) {
			return id;
		} else {
			return (id = nextID++);
		}
	}

	// **********************
	// *** Static members ***
	// **********************
	
	private static HashSet<Actor> all = new HashSet<>();
	private static HashSet<Char> chars = new HashSet<>();
	private static volatile Actor current;

	private static SparseArray<Actor> ids = new SparseArray<>();
	private static int nextID = 1;

	private static volatile float now = 0;
	//true while the actor thread waits for the render thread at the end of a scheduler pass
	private static volatile boolean parked;
	
	public static float now(){
		return now;
	}

	static float timeOf( Actor actor ){
		return actor.time;
	}

	static void setTime( Actor actor, float time ){
		actor.time = time;
	}

	/** True while the actor thread is idle, so the render thread can read the game state safely. */
	public static boolean parked(){
		return parked;
	}

	static Actor currentActor(){
		return current;
	}

	/** Lets the scheduler continue past a player it was waiting for (see {@link TurnClock}). */
	static synchronized void release( Actor held ){
		if (current == held) {
			current = null;
		}
	}
	
	public static synchronized void clear() {
		
		now = 0;
		TurnClock.reset();

		all.clear();
		chars.clear();

		ids.clear();
	}

	public static synchronized void fixTime() {
		
		if (all.isEmpty()) return;
		
		float min = Float.MAX_VALUE;
		for (Actor a : all) {
			//a skipped party player follows the current time and must not pin it
			if (TurnClock.detached(a) && a.time < now) a.time = now;
			if (a.time < min) {
				min = a.time;
			}
		}

		//Only pull everything back by whole numbers
		//So that turns always align with a whole number
		min = (int)min;
		for (Actor a : all) {
			a.fixTime(min);
		}
		TargetedCell.fixTime(min);

		if (Dungeon.hero != null && all.contains( Dungeon.hero )) {
			Statistics.duration += min;
		}
		now -= min;
	}
	
	public static void init() {
		
		add( Dungeon.hero );
		
		for (Mob mob : Dungeon.level.mobs) {
			add( mob );
		}

		//mobs need to remember their targets after every actor is added
		for (Mob mob : Dungeon.level.mobs) {
			mob.restoreEnemy();
		}
		
		for (Blob blob : Dungeon.level.blobs.values()) {
			add( blob );
		}
		
		current = null;
	}

	private static final String NEXTID = "nextid";

	public static void storeNextID( Bundle bundle){
		bundle.put( NEXTID, nextID );
	}

	public static void restoreNextID( Bundle bundle){
		nextID = bundle.getInt( NEXTID );
	}

	public static void resetNextID(){
		nextID = 1;
	}

	/*protected*/public void next() {
		if (current == this) {
			current = null;
		}
	}

	public static boolean processing(){
		return current != null;
	}

	public static int curActorPriority() {
		return current != null ? current.actPriority : HERO_PRIO;
	}
	
	public static boolean keepActorThreadAlive = true;
	
	public static void process() {
		
		boolean doNext;
		boolean interrupted = false;

		do {
			
			current = null;
			//party play: the hero and the host's party member share an action-driven turn clock
			Hero hero = Dungeon.hero;
			InnPartyMember member = hero != null && hero.isAlive() ? TurnClock.activeMember() : null;
			if (member == null) TurnClock.endParty(hero, now);

			if (!interrupted && !Game.switchingScene()) {
				if (member != null) TurnClock.beginPass(hero, member, now);
				boolean heroSkipped = member != null && TurnClock.detached(hero);
				boolean memberSkipped = member != null && TurnClock.detached(member);
				float earliest = Float.MAX_VALUE;

				synchronized (Actor.class) {
					for (Actor actor : all) {

						//skipped party players do not hold the queue
						if (heroSkipped && actor == hero || memberSkipped && actor == member) continue;

						//some actors will always go before others if time is equal.
						if (actor.time < earliest ||
								actor.time == earliest && (current == null || actor.actPriority > current.actPriority)) {
							earliest = actor.time;
							current = actor;
						}

					}
				}

				if (member != null) current = TurnClock.preferReady(current, hero, member);
			}

			if  (current != null) {

				now = current.time;
				Actor acting = current;

				if (member != null && TurnClock.isPlayer(acting, hero, member) && !TurnClock.hasReadyAction(acting)) {

					//an idle player is the earliest actor: freeze, skip, count down or wait automatically
					TurnClock.Decided decided = TurnClock.decideIdle(acting, hero, member, now);
					current = decided.hold;
					doNext = decided.doNext;

				} else {

					if (acting instanceof Char && ((Char) acting).sprite != null) {
						// If it's character's turn to act, but its sprite
						// is moving, wait till the movement is over
						try {
							synchronized (((Char)acting).sprite) {
								if (((Char)acting).sprite.isMoving) {
									((Char) acting).sprite.wait();
								}
							}
						} catch (InterruptedException e) {
							interrupted = true;
						}
					}
					
					interrupted = interrupted || Thread.interrupted();
					
					if (interrupted){
						doNext = false;
						current = null;
					} else {
						if (member != null && TurnClock.isPlayer(acting, hero, member)) TurnClock.acting(acting);
						doNext = acting.act();
						if (member != null && acting == hero && !doNext && current == hero && TurnClock.heroIdle(hero)) {
							//the hero found nothing to do this turn
							TurnClock.Decided decided = TurnClock.decideIdle(hero, hero, member, now);
							current = decided.hold;
							doNext = decided.doNext;
						}
						if (doNext && (Dungeon.hero == null || !Dungeon.hero.isAlive())) {
							doNext = false;
							current = null;
						}
					}
				}
			} else {
				doNext = false;
			}

			if (!doNext){
				synchronized (Thread.currentThread()) {
					
					interrupted = interrupted || Thread.interrupted();
					
					if (interrupted){
						current = null;
						interrupted = false;
					}

					//signals to the gamescene that actor processing is finished for now
					Thread.currentThread().notify();
					
					parked = true;
					try {
						Thread.currentThread().wait();
					} catch (InterruptedException e) {
						interrupted = true;
					} finally {
						parked = false;
					}
				}
			}

		} while (keepActorThreadAlive);
	}
	
	public static void add( Actor actor ) {
		add( actor, now );
	}
	
	public static void addDelayed( Actor actor, float delay ) {
		add( actor, now + Math.max(delay, 0) );
	}
	
	private static synchronized void add( Actor actor, float time ) {
		
		if (all.contains( actor )) {
			return;
		}

		ids.put( actor.id(),  actor );

		all.add( actor );
		actor.time += time;
		actor.onAdd();
		
		if (actor instanceof Char) {
			Char ch = (Char)actor;
			chars.add( ch );
			for (Buff buff : ch.buffs()) {
				add(buff);
			}
		}
	}
	
	public static synchronized void remove( Actor actor ) {
		
		if (actor != null) {
			all.remove( actor );
			chars.remove( actor );
			actor.onRemove();

			if (actor.id > 0) {
				ids.remove( actor.id );
			}
		}
	}

	//'freezes' a character in time for a specified amount of time
	//USE CAREFULLY! Manipulating time like this is useful for some gameplay effects but is tricky
	public static void delayChar( Char ch, float time ){
		ch.spendConstant(time);
		for (Buff b : ch.buffs()){
			b.spendConstant(time);
		}
	}
	
	public static synchronized Char findChar( int pos ) {
		for (Char ch : chars){
			if (ch.pos == pos)
				return ch;
		}
		return null;
	}

	public static synchronized Actor findById( int id ) {
		return ids.get( id );
	}

	public static synchronized HashSet<Actor> all() {
		return new HashSet<>(all);
	}

	public static synchronized HashSet<Char> chars() { return new HashSet<>(chars); }
}
