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

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetInstance;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoader;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoaderListener;

//downloads the asset manifest while the page shows its own HTML loading screen (see index.html)
public class WebPreloader extends ApplicationAdapter {

	private AssetLoader loader;
	private int total = -1;
	private boolean done;

	@Override
	public void create() {
		loader = AssetInstance.getLoaderInstance();
		loader.preload(new AssetLoaderListener<Void>() {
			@Override
			public void onSuccess(String url, Void result) {
				total = loader.getQueue();
			}

			@Override
			public void onFailure(String url) {
				WebJS.crash("Failed to download the game files (" + url + "). Please check your connection and reload the page.");
			}
		});
	}

	@Override
	public void render() {
		Gdx.gl.glClearColor(0, 0, 0, 1);
		Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT);
		if (total < 0 || done) return;

		int remaining = loader.getQueue();
		WebJS.loadProgress(total - remaining, total);
		if (remaining == 0 && !loader.isDownloading()) {
			done = true;
			WebJS.loaded();
			WebApplication.get().setPreloadReady();
		}
	}
}
