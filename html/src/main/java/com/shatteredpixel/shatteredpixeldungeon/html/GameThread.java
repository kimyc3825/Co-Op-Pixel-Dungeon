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

package com.shatteredpixel.shatteredpixeldungeon.html;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;

import java.util.ArrayDeque;

/**
 * Runs the game on a TeaVM green thread.
 *
 * The browser drives gdx-teavm from requestAnimationFrame / DOM event callbacks, which are not
 * TeaVM threads, so anything that blocks (Object.wait, contended synchronized blocks) would throw there.
 * Shattered relies on exactly that to coordinate its render thread with the actor thread
 * (GameScene/Actor/InterlevelScene), so all lifecycle calls, posted runnables and input events
 * are forwarded to one long-lived "render thread", like on desktop.
 */
public class GameThread implements ApplicationListener {

	private final WebInnGame game;

	private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
	private final ArrayDeque<Runnable> posted = new ArrayDeque<>();
	private final QueuedInput inputQueue = new QueuedInput();
	private InputProcessor gameInput;

	private AsyncCallback<Void> parked;
	private boolean renderQueued;
	private boolean crashed;
	private boolean ready;

	//diagnostics, readable from the browser console with spd.state()
	private int frames;
	private boolean running;

	private final Runnable renderTask = new Runnable() {
		@Override
		public void run() {
			renderQueued = false;
			runPosted();
			if (gameInput != null) {
				inputQueue.drain(gameInput);
			}
			game.render();
			frames++;
			if (!ready && com.watabou.noosa.Game.scene() != null) {
				ready = true;
				WebJS.ready();
			}
			AutoTest.step();
		}
	};

	public GameThread( WebInnGame game ){
		this.game = game;
		if (WebBuildConfig.VERSION.endsWith("-INDEV")) WebJS.registerAutoTest(new WebJS.Toggle() {
			@Override
			public void set(boolean on) {
				AutoTest.setEnabled(on);
			}
		}, new WebJS.StateProvider() {
			@Override
			public String state() {
				return AutoTest.status();
			}
		});
		WebJS.registerState(new WebJS.StateProvider() {
			@Override
			public String state() {
				return "frames=" + frames + " running=" + running + " parked=" + (parked != null)
						+ " queued=" + tasks.size() + " posted=" + posted.size() + " crashed=" + crashed
						+ " input=" + inputQueue.received + "/" + inputQueue.delivered
						+ " processor=" + (Gdx.input == null ? null : Gdx.input.getInputProcessor() == inputQueue)
						+ " game=" + com.watabou.noosa.Game.width + "x" + com.watabou.noosa.Game.height
						+ " gdx=" + (Gdx.graphics == null ? "-" : Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());
			}
		});
	}

	// ---- called from browser callbacks (non-threaded context): only queue work ----

	@Override
	public void create() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				loop();
			}
		}, "SHPD Render Thread");
		thread.start();

		final String seedCheck = WebBuildConfig.VERSION.endsWith("-INDEV") ? WebJS.queryParam("seedcheck") : null;
		if (seedCheck != null) {
			//1:1 verification mode (see SeedCheck), the game itself is not started
			queue(new Runnable() {
				@Override
				public void run() {
					String hero = WebJS.queryParam("hero");
					String depths = WebJS.queryParam("depths");
					String dump = SeedCheck.runAll(seedCheck, hero == null ? "warrior" : hero,
							depths == null ? 5 : Integer.parseInt(depths));
					WebJS.seedCheckResult(dump);
				}
			});
			crashed = true; //stops any further game tasks
			return;
		}

		queue(new Runnable() {
			@Override
			public void run() {
				game.create();
				//route DOM input through a queue that is drained on this thread, see class comment
				gameInput = Gdx.input.getInputProcessor();
				Gdx.input.setInputProcessor(inputQueue);
				WebKeyboard.install(inputQueue);
				WebJS.onShortcutFocusLoss(inputQueue::releaseShortcut);
				WebJS.onReturnRequest(() -> queue(game::returnToInn));
				//smoke test mode, see AutoTest and the ?autotest section of index.html
				if (WebBuildConfig.VERSION.endsWith("-INDEV") && WebJS.queryParam("autotest") != null) AutoTest.setEnabled(true);
			}
		});
	}

	@Override
	public void resize(final int width, final int height) {
		queue(new Runnable() {
			@Override
			public void run() {
				game.resize(width, height);
			}
		});
	}

	@Override
	public void render() {
		if (!renderQueued) {
			renderQueued = true;
			queue(renderTask);
		}
	}

	@Override
	public void pause() {
		queue(new Runnable() {
			@Override
			public void run() {
				game.pause();
			}
		});
	}

	@Override
	public void resume() {
		queue(new Runnable() {
			@Override
			public void run() {
				game.resume();
			}
		});
	}

	@Override
	public void dispose() {
		queue(new Runnable() {
			@Override
			public void run() {
				game.dispose();
			}
		});
	}

	//replacement for Gdx.app.postRunnable, runs before the next frame on the render thread
	public void post( Runnable r ){
		posted.add(r);
	}

	public boolean isCrashed(){
		return crashed;
	}

	// ---- render thread ----

	private void queue( Runnable r ){
		if (crashed) return;
		tasks.add(r);
		if (parked != null) {
			AsyncCallback<Void> cb = parked;
			parked = null;
			cb.complete(null);
		}
	}

	private void runPosted(){
		int count = posted.size();
		for (int i = 0; i < count && !posted.isEmpty(); i++) {
			posted.poll().run();
		}
	}

	private void loop(){
		while (true) {
			Runnable task = tasks.poll();
			if (task == null) {
				park(this);
				continue;
			}
			try {
				running = true;
				task.run();
				running = false;
			} catch (Throwable t) {
				crashed = true;
				tasks.clear();
				WebCrashHandler.handle(t);
				return;
			}
		}
	}

	@Async
	private static native void park( GameThread self );

	private static void park( GameThread self, AsyncCallback<Void> callback ){
		self.parked = callback;
	}

}
