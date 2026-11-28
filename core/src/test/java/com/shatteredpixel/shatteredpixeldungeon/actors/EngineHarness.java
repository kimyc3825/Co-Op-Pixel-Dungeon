// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.actors;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.InnAdventure;
import com.shatteredpixel.shatteredpixeldungeon.InnOriginalParty;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPartyMember;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.AttackIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.NinePatch;
import com.watabou.utils.Callback;
import com.watabou.utils.GameSettings;
import com.watabou.utils.PointF;
import com.watabou.utils.SparseArray;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Headless harness for the real scheduler: {@link Actor#process()} on its own thread, the real
 * {@link TurnClock}, {@link Hero#act()}, {@link InnPartyMember} and {@link InnOriginalParty}, with sprites and
 * interface singletons stubbed so no GL context is needed. {@link #frame(float)} emulates the part of
 * {@code GameScene.update()} that drives the actor thread, in the same order.
 *
 * Based on the review harness used to reproduce the turn clock defects.
 */
public final class EngineHarness {

	static sun.misc.Unsafe unsafe;
	private static boolean booted;

	public static Hero hero;
	public static int heroActs;
	public static InnPartyMember member;
	public static InnOriginalParty party;
	public static TestLevel level;
	public static Thread actorThread;
	public static final List<String> sent = new ArrayList<>();
	public static final List<String> statuses = new ArrayList<>();
	public static int width = 15;
	public static int seq;
	public static float wall;

	private EngineHarness() {}

	public static class TestSprite extends CharSprite {
		@Override public void link(Char ch) { this.ch = ch; ch.sprite = this; }
		@Override public void linkVisuals(Char ch) {}
		@Override public void place(int cell) {}
		@Override public void showStatus(int color, String text, Object... args) { statuses.add(text); }
		@Override public void showStatusWithIcon(int color, String text, int icon, Object... args) {}
		@Override public void idle() {}
		@Override public void move(int from, int to) {}
		@Override public void interruptMotion() {}
		// The attack animation ends at once, as if its last frame had been drawn.
		@Override public void attack(int cell) { if (ch != null) ch.onAttackComplete(); }
		@Override public synchronized void attack(int cell, Callback callback) { if (callback != null) callback.call(); }
		@Override public void operate(int cell) {}
		@Override public synchronized void operate(int cell, Callback callback) {}
		@Override public void zap(int cell) {}
		@Override public synchronized void zap(int cell, Callback callback) {}
		@Override public void turnTo(int from, int to) {}
		@Override public void die() {}
		@Override public void burst(int color, int n) {}
		@Override public void bloodBurstA(PointF from, int damage) {}
		@Override public void flash() {}
		@Override public void add(State state) {}
		@Override public void remove(State state) {}
		@Override public void showSleep() {}
		@Override public void hideSleep() {}
		@Override public void showAlert() {}
		@Override public void showAlert(Group effects) {}
		@Override public void hideAlert() {}
		@Override public void showInvestigate() {}
		@Override public void hideInvestigate() {}
		@Override public void showLost() {}
		@Override public void hideLost() {}
		@Override public void hideEmo() {}
		@Override public void kill() {}
		@Override public void killAndErase() {}
		@Override public void update() {}
		@Override public float width() { return 12; }
		@Override public float height() { return 12; }
	}

	public static class TestHeroSprite extends HeroSprite {
		@Override public void link(Char ch) { this.ch = ch; ch.sprite = this; }
		@Override public void place(int cell) {}
		@Override public void showStatus(int color, String text, Object... args) { statuses.add(text); }
		@Override public void showStatusWithIcon(int color, String text, int icon, Object... args) {}
		@Override public void idle() {}
		@Override public void move(int from, int to) {}
		@Override public void interruptMotion() {}
		@Override public void attack(int cell) {}
		@Override public synchronized void attack(int cell, Callback callback) {}
		@Override public void operate(int cell) {}
		@Override public synchronized void operate(int cell, Callback callback) {}
		@Override public void zap(int cell) {}
		@Override public synchronized void zap(int cell, Callback callback) {}
		@Override public void turnTo(int from, int to) {}
		@Override public void die() {}
		@Override public void burst(int color, int n) {}
		@Override public void bloodBurstA(PointF from, int damage) {}
		@Override public void flash() {}
		@Override public void add(State state) {}
		@Override public void remove(State state) {}
		@Override public void showSleep() {}
		@Override public void hideSleep() {}
		@Override public void hideEmo() {}
		@Override public void kill() {}
		@Override public void update() {}
		@Override public void sprint(float speed) {}
		@Override public synchronized void read() {}
	}

	public static class TestLevel extends Level {
		@Override protected boolean build() { return true; }
		@Override protected void createMobs() {}
		@Override protected void createItems() {}
		@Override public String tilesTex() { return "environment/tiles_sewers.png"; }
		@Override public String waterTex() { return null; }
	}

	/** Background of the stubbed attack indicator. */
	public static class StubPatch extends NinePatch {
		public StubPatch() { super(null, 0); }
		@Override public void size(float width, float height) {}
	}

	/** Sprite class the attack indicator can instantiate for a dummy target. */
	public static class IndicatorSprite extends TestSprite {
		public IndicatorSprite() { super(); }
	}

	/** Hostile mob with scripted behaviour: spends one turn per act; its state is set by the test. */
	public static class Dummy extends Mob {
		public volatile int acts;
		{ HP = HT = 50; alignment = Alignment.ENEMY; state = PASSIVE; actPriority = MOB_PRIO; spriteClass = IndicatorSprite.class; }
		public void hunt(Char target) { enemy = target; state = HUNTING; }
		public void wander() { enemy = null; state = WANDERING; }
		public void sleepNow() { enemy = null; state = SLEEPING; }
		@Override protected boolean act() { acts++; spend(TICK); return true; }
	}

	/** Hero that counts its turns. */
	public static class CountingHero extends Hero {
		@Override public boolean act() { heroActs++; return super.act(); }
	}

	@SuppressWarnings("unchecked")
	public static <T> T alloc(Class<T> cls) throws Exception { return (T) unsafe.allocateInstance(cls); }

	static void setField(Class<?> c, Object o, String name, Object v) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, v);
	}

	static Object getField(Class<?> c, Object o, String name) throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	public static synchronized void boot() throws Exception {
		if (booted) return;
		// Journal classes check the build version when items are identified.
		if (com.watabou.noosa.Game.version == null) com.watabou.noosa.Game.version = "0.0.0-test";
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		unsafe = (sun.misc.Unsafe) f.get(null);
		String assets = System.getProperty("assets", "src/main/assets");

		Game.instance = alloc(com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.class);
		AttackIndicator indicator = alloc(AttackIndicator.class);
		setField(AttackIndicator.class, indicator, "candidates", new ArrayList<Mob>());
		setField(Group.class, indicator, "members", new ArrayList<Gizmo>());
		setField(Tag.class, indicator, "bg", alloc(StubPatch.class));
		setField(com.shatteredpixel.shatteredpixeldungeon.ui.Button.class, indicator, "hotArea",
				alloc(com.watabou.noosa.PointerArea.class));
		setField(AttackIndicator.class, null, "instance", indicator);
		TargetHealthIndicator.instance = alloc(TargetHealthIndicator.class);

		Preferences prefs = (Preferences) Proxy.newProxyInstance(EngineHarness.class.getClassLoader(),
				new Class[]{Preferences.class}, (proxy, method, args) -> {
			String n = method.getName();
			if (n.startsWith("get") && args != null && args.length == 2) return args[1];
			if (n.equals("contains")) return false;
			if (n.equals("get")) return new HashMap<String, Object>();
			Class<?> r = method.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			if (r == float.class) return 0f;
			if (r == Preferences.class) return proxy;
			return null;
		});
		GameSettings.set(prefs);
		Gdx.app = (Application) Proxy.newProxyInstance(EngineHarness.class.getClassLoader(),
				new Class[]{Application.class}, (proxy, method, args) -> {
			if (method.getName().equals("getPreferences")) return prefs;
			if (method.getName().equals("getType")) return Application.ApplicationType.Desktop;
			Class<?> r = method.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			return null;
		});
		Gdx.files = (Files) Proxy.newProxyInstance(EngineHarness.class.getClassLoader(),
				new Class[]{Files.class}, (proxy, method, args) -> {
			if (method.getName().equals("internal") || method.getName().equals("getFileHandle")) {
				return new FileHandle(new File(assets, (String) args[0]));
			}
			return null;
		});
		// Every texture is a blank 256x256 stand-in, so combat text and effects can be created without GL.
		com.watabou.gltextures.SmartTexture blank = alloc(com.watabou.gltextures.SmartTexture.class);
		blank.width = blank.height = 256;
		setField(com.watabou.gltextures.TextureCache.class, null, "all", new HashMap<Object, com.watabou.gltextures.SmartTexture>() {
			@Override public boolean containsKey(Object key) { return true; }
			@Override public com.watabou.gltextures.SmartTexture get(Object key) { return blank; }
		});
		booted = true;
	}

	public static int cell(int x, int y) { return x + y * width; }

	/** An open room of the given size, walled at the border, with a fresh hero and no party. */
	private static void newLevel(int w, int h, int heroCell) throws Exception {
		width = w;
		Dungeon.level = null;
		Actor.clear();
		Dungeon.depth = 1;
		Dungeon.branch = 0;
		Dungeon.challenges = 0;
		level = new TestLevel();
		level.setSize(w, h);
		for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) level.map[x + y * w] = Terrain.EMPTY;
		level.mobs = new HashSet<>();
		level.heaps = new SparseArray<>();
		level.blobs = new HashMap<>();
		level.plants = new SparseArray<>();
		level.traps = new SparseArray<>();
		level.customTiles = new ArrayList<>();
		level.customWalls = new ArrayList<>();
		level.customTerrain = new ArrayList<>();
		level.transitions = new ArrayList<>();
		level.buildFlagMaps();
		level.cleanWalls();

		heroActs = 0;
		hero = new CountingHero();
		hero.live();
		hero.pos = heroCell;
		Dungeon.hero = hero;
		Dungeon.level = level;
		hero.fieldOfView = level.heroFOV;
		alloc(TestHeroSprite.class).link(hero);

		Field cs = GameScene.class.getDeclaredField("cellSelector");
		cs.setAccessible(true);
		if (cs.get(null) == null) cs.set(null, alloc(CellSelector.class));
		statuses.clear();
		seq = 0;
		wall = 0;
	}

	/** A party game: the host's hero at heroCell and the guest's member at memberCell. */
	public static void newParty(int w, int h, int heroCell, int memberCell) throws Exception {
		stopActorThread();
		InnAdventure.clear();
		InnAdventure.configure("host", "WARRIOR", "a", "b", "c", "d", true);
		sent.clear();
		InnOriginalParty.configure(true, "guest", "MAGE", "a", "b", "c", "d", 1L, false, sent::add);
		party = InnOriginalParty.current();
		newLevel(w, h, heroCell);
		InnOriginalParty.enterFloor(level, heroCell);
		member = party.activeMember(level);
		member.pos = memberCell;
		alloc(TestSprite.class).link(member);
		Actor.init();
		Dungeon.observe();
		startActorThread();
	}

	/** A solo expedition with no party link: the original scheduling path. */
	public static void newSolo(int w, int h, int heroCell) throws Exception {
		stopActorThread();
		InnAdventure.clear();
		InnAdventure.configure("host", "WARRIOR", "a", "b", "c", "d", true);
		party = null;
		member = null;
		newLevel(w, h, heroCell);
		Actor.init();
		Dungeon.observe();
		startActorThread();
	}

	public static Dummy addDummy(int cell) throws Exception {
		Dummy d = new Dummy();
		d.pos = cell;
		alloc(TestSprite.class).link(d);
		level.mobs.add(d);
		Actor.add(d);
		return d;
	}

	static void startActorThread() {
		Actor.keepActorThreadAlive = true;
		actorThread = new Thread(() -> {
			try {
				Actor.process();
			} catch (Throwable t) {
				t.printStackTrace(System.out);
			}
		});
		actorThread.setName("test actor thread");
		actorThread.setDaemon(true);
		actorThread.start();
		settle();
	}

	public static void stopActorThread() throws Exception {
		if (actorThread == null) return;
		Actor.keepActorThreadAlive = false;
		actorThread.interrupt();
		actorThread.join(2000);
		actorThread = null;
	}

	public static boolean actorThreadAlive() {
		return actorThread != null && actorThread.isAlive();
	}

	/** Waits until the actor thread is parked (or dead). */
	public static void settle() {
		long end = System.currentTimeMillis() + 3000;
		try { Thread.sleep(3); } catch (InterruptedException ignored) {}
		while (!Actor.parked() && actorThreadAlive() && System.currentTimeMillis() < end) {
			try { Thread.sleep(1); } catch (InterruptedException ignored) {}
		}
	}

	/** One emulated render frame, in the order GameScene.update() uses. Returns true if the actor thread was woken. */
	public static boolean frame(float dt) {
		wall += dt;
		if (party != null) {
			TurnClock.update(dt);
			party.updateHost(dt);
		}
		if (!Actor.processing() && Dungeon.hero.isAlive()) {
			Actor.notifyActorThread(actorThread);
			settle();
			return true;
		}
		return false;
	}

	public static int frames(int n, float dt) {
		int woken = 0;
		for (int i = 0; i < n; i++) if (frame(dt)) woken++;
		return woken;
	}

	/** Runs frames until the countdown ends or max seconds pass; returns the wall time it took. */
	public static float countdownSeconds(float dt, float max) {
		float start = wall;
		while (TurnClock.waitingOn() != TurnClock.WAIT_NONE && wall - start < max) frame(dt);
		return wall - start;
	}

	/** A guest command as it arrives from the network. */
	public static void guest(String kind, int a, int b) {
		InnOriginalParty.receive("C|" + floor() + "|" + (++seq) + "|" + kind + "|" + a + "|" + b);
	}

	public static int floor() {
		try { return (Integer) getField(InnOriginalParty.class, party, "floor"); }
		catch (Exception e) { throw new RuntimeException(e); }
	}

	public static int ack() {
		try { return (Integer) getField(InnOriginalParty.class, party, "ack"); }
		catch (Exception e) { throw new RuntimeException(e); }
	}

	public static float time(Actor actor) {
		return Actor.timeOf(actor);
	}

	/** Scheduler state for assertion messages. */
	public static String state() {
		Actor current = Actor.currentActor();
		return String.format("now=%.2f hero[t=%.2f ready=%b action=%s detached=%b pos=%d] member[t=%.2f detached=%b pos=%d]"
						+ " current=%s parked=%b wait=%d grace=%.2f ack=%d seq=%d",
				Actor.now(), time(hero), hero.ready, hero.curAction, TurnClock.detached(hero), hero.pos,
				member == null ? -1f : time(member), member != null && TurnClock.detached(member), member == null ? -1 : member.pos,
				current == hero ? "hero" : current == member ? "member" : String.valueOf(current), Actor.parked(),
				TurnClock.waitingOn(), TurnClock.graceRemaining(), party == null ? -1 : ack(), seq);
	}

	public static int attackCandidates() throws Exception {
		Object indicator = getField(AttackIndicator.class, null, "instance");
		return ((ArrayList<?>) getField(AttackIndicator.class, indicator, "candidates")).size();
	}
}
