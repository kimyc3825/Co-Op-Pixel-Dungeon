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

package emu.com.badlogic.gdx.utils;

import com.badlogic.gdx.utils.Architecture;
import com.badlogic.gdx.utils.Os;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetInstance;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoader;

/**
 * TeaVM replacement for com.badlogic.gdx.utils.SharedLibraryLoader.
 * Shadows gdx-teavm's emulation (which only has load()) to also provide the static platform fields
 * that DeviceCompat reads. WebLauncher sets 'os' from the browser's user agent at startup.
 */
public class SharedLibraryLoader {

	public static Os os = Os.Linux;
	public static Architecture architecture = Architecture.x86;
	public static Architecture.Bitness bitness = Architecture.Bitness._32;
	public static String architectureString = "web";
	public static boolean isARM = false;
	public static boolean is64Bit = false;

	public SharedLibraryLoader () {
	}

	public SharedLibraryLoader (String nativesJar) {
	}

	public void load (String libraryName) {
		AssetLoader assetLoader = AssetInstance.getLoaderInstance();
		assetLoader.loadScript(libraryName + ".js", null);
	}
}
