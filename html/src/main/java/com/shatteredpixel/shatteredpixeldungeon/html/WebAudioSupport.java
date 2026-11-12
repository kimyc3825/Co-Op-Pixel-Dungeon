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

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetInstance;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoader;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoaderListener;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetType;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.WebBlob;

/**
 * Wraps the Howler based audio of gdx-teavm.
 * - Music files are not part of the startup download (they are ~18MB); a track is fetched the first
 *   time it is played, and starts as soon as it arrives.
 * - Browsers that can't decode Ogg Vorbis get the mp3 copies of the music that the iOS build uses.
 */
public class WebAudioSupport implements Audio {

	private final Audio delegate;
	private final boolean ogg;

	public WebAudioSupport(Audio delegate) {
		this.delegate = delegate;
		this.ogg = WebJS.canPlayOgg();
	}

	@Override
	public Sound newSound(FileHandle fileHandle) {
		return delegate.newSound(fileHandle);
	}

	@Override
	public Music newMusic(FileHandle file) {
		String path = file.path();
		if (!ogg && path.endsWith(".ogg")){
			path = path.substring(0, path.length() - 4) + ".mp3";
		}
		return new LazyMusic(path);
	}

	@Override
	public AudioDevice newAudioDevice(int samplingRate, boolean isMono) {
		return delegate.newAudioDevice(samplingRate, isMono);
	}

	@Override
	public AudioRecorder newAudioRecorder(int samplingRate, boolean isMono) {
		return delegate.newAudioRecorder(samplingRate, isMono);
	}

	@Override
	public boolean switchOutputDevice(String deviceIdentifier) {
		return delegate.switchOutputDevice(deviceIdentifier);
	}

	@Override
	public String[] getAvailableOutputDevices() {
		return delegate.getAvailableOutputDevices();
	}

	@Override
	public void dispose() {
		delegate.dispose();
	}

	private class LazyMusic implements Music {

		private Music music;
		private boolean disposed;

		private boolean playing;
		private boolean looping;
		private float volume = 1f;
		private float pan = 0f;
		private float position = 0f;
		private OnCompletionListener listener;

		LazyMusic(final String path){
			final AssetLoader loader = AssetInstance.getLoaderInstance();
			if (loader.isAssetLoaded(Files.FileType.Internal, path)){
				create(path);
			} else {
				loader.loadAsset(path, AssetType.Binary, Files.FileType.Internal, new AssetLoaderListener<WebBlob>() {
					@Override
					public void onSuccess(String url, WebBlob result) {
						//applied on the game thread, like every other audio call
						SPDWebApplication.gameThread().post(new Runnable() {
							@Override
							public void run() {
								create(path);
							}
						});
					}

					@Override
					public void onFailure(String url) {
						Gdx.app.error("AUDIO", "could not load music " + url);
					}
				});
			}
		}

		private void create(String path){
			if (disposed || music != null) return;
			music = delegate.newMusic(Gdx.files.internal(path));
			music.setLooping(looping);
			music.setVolume(volume);
			if (pan != 0f) music.setPan(pan, volume);
			if (position > 0f) music.setPosition(position);
			attachCompletion();
			if (playing) music.play();
		}

		@Override
		public void play() {
			playing = true;
			if (music != null) music.play();
		}

		@Override
		public void pause() {
			playing = false;
			if (music != null) music.pause();
		}

		@Override
		public void stop() {
			playing = false;
			position = 0;
			if (music != null) music.stop();
		}

		@Override
		public boolean isPlaying() {
			//while the track is downloading it counts as playing, so the game doesn't consider it finished
			return music != null ? music.isPlaying() : playing;
		}

		@Override
		public void setLooping(boolean isLooping) {
			looping = isLooping;
			if (music != null) music.setLooping(isLooping);
		}

		@Override
		public boolean isLooping() {
			return looping;
		}

		@Override
		public void setVolume(float volume) {
			this.volume = volume;
			if (music != null) music.setVolume(volume);
		}

		@Override
		public float getVolume() {
			return volume;
		}

		@Override
		public void setPan(float pan, float volume) {
			this.pan = pan;
			this.volume = volume;
			if (music != null) music.setPan(pan, volume);
		}

		@Override
		public void setPosition(float position) {
			this.position = position;
			if (music != null) music.setPosition(position);
		}

		@Override
		public float getPosition() {
			return music != null ? music.getPosition() : position;
		}

		@Override
		public void dispose() {
			disposed = true;
			playing = false;
			if (music != null) {
				music.dispose();
				music = null;
			}
		}

		//noosa's Music sets a new listener for every track it plays and clears it with null for single
		// (looping) tracks. gdx-teavm's HowlMusic adds one more Howler "end" handler on every call, null
		// listeners included, and that handler then calls the null listener. So the Howl gets exactly one
		// handler (in create) that looks up the current listener when the track ends.
		@Override
		public void setOnCompletionListener(OnCompletionListener listener) {
			this.listener = listener;
		}

		private void attachCompletion(){
			music.setOnCompletionListener(new OnCompletionListener() {
				@Override
				public void onCompletion(Music m) {
					//Howler reports completion from a browser callback; hand it to the game thread
					SPDWebApplication.gameThread().post(new Runnable() {
						@Override
						public void run() {
							//Howler also reports the end of every loop of a looping track, the desktop
							// backends don't: a looping track never completes
							OnCompletionListener current = listener;
							if (!disposed && !looping && current != null) current.onCompletion(LazyMusic.this);
						}
					});
				}
			});
		}
	}
}
