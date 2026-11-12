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

package com.shatteredpixel.shatteredpixeldungeon.html.seedcheck;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.html.SeedCheck;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Runs SeedCheck on the JVM with the same libGDX version as the desktop build (headless backend,
 * no window), writing the dump to a file. Used by the html:seedCheckDesktop task.
 * Arguments: seeds, hero classes (comma separated lists), depth count, output file.
 */
public class SeedCheckDesktop {

	public static void main(String[] args) {
		final String seed = args[0];
		final String heroes = args[1];
		final int depths = Integer.parseInt(args[2]);
		final File out = new File(args[3]);

		//same values the web build uses (WebBuildConfig), Dungeon.init stores the version code
		Game.version = System.getProperty("spd.version", "4.0.2");
		Game.versionCode = Integer.getInteger("spd.versionCode", 0);

		//keep the developer's real desktop saves out of this: an empty temporary folder for game files
		// (SeedCheck itself switches to fresh in-memory settings)
		try {
			File tmp = Files.createTempDirectory("spd-seedcheck").toFile();
			tmp.deleteOnExit();
			FileUtils.setDefaultFileProperties(com.badlogic.gdx.Files.FileType.Absolute, tmp.getAbsolutePath() + "/");
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}

		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = -1;
		new HeadlessApplication(new ApplicationAdapter() {
			@Override
			public void create() {
				int code = 0;
				try {
					String dump = SeedCheck.runAll(seed, heroes, depths);
					out.getParentFile().mkdirs();
					Files.write(out.toPath(), dump.getBytes(StandardCharsets.UTF_8));
					System.out.println("[seedcheck] desktop dump written to " + out);
				} catch (Throwable t) {
					t.printStackTrace();
					code = 1;
				}
				Gdx.app.exit();
				if (code != 0) System.exit(code);
			}
		}, config);
	}
}
