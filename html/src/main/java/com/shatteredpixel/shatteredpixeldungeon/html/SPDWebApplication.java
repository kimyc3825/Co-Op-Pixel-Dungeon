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
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import com.github.xpenatan.gdx.teavm.backends.web.WebGraphics;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetInstance;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoaderListener;
import com.github.xpenatan.gdx.teavm.backends.web.webaudio.howler.HowlTeaAudio;

public class SPDWebApplication extends WebApplication {

	private static GameThread gameThread;

	public SPDWebApplication(GameThread game, ApplicationListener preloader, WebApplicationConfiguration config) {
		super(register(game), preloader, config);
	}

	//the game thread must be known before the superclass constructor starts the app
	private static GameThread register(GameThread game){
		gameThread = game;
		return game;
	}

	public static GameThread gameThread(){
		return gameThread;
	}

	//libGDX runnables must run on the game thread, not in the browser's animation frame callback
	@Override
	public void postRunnable(Runnable runnable) {
		gameThread.post(runnable);
	}

	@Override
	public int getVersion() {
		//Gdx.app.getVersion is the OS major version on mobile (used by DeviceCompat.getPlatformVersion)
		return WebJS.isIOS() ? WebJS.iOSVersion() : 0;
	}

	@Override
	public void exit() {
		WebJS.exit();
	}

	@Override
	protected void initAudio() {
		addInitQueue();
		AssetInstance.getLoaderInstance().loadScript("howler.js", new AssetLoaderListener<String>() {
			@Override
			public void onSuccess(String url, String result) {
				subtractInitQueue();
				Gdx.audio = new WebAudioSupport(new HowlTeaAudio());
			}
		});
	}

	@Override
	protected WebGraphics createGraphics(WebApplicationConfiguration config) {
		return new SPDWebGLGraphics(config);
	}

	@Override
	protected void onError(Throwable error) {
		WebCrashHandler.handle(error);
	}
}
