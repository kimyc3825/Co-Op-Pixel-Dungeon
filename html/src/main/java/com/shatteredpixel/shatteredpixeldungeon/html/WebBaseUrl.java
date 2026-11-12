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

import com.github.xpenatan.gdx.teavm.backends.web.utils.WebBaseUrlProvider;

import org.teavm.jso.JSBody;

//assets and scripts are loaded from the versioned build folder given by index.html (window.SPD_BASE)
public class WebBaseUrl implements WebBaseUrlProvider {

	@Override
	public String getBaseUrl() {
		return baseUrl();
	}

	@JSBody(script = "var base = window.SPD_BASE || '';"
			+ "if (base.indexOf('%') >= 0) base = '';" //unprocessed template, e.g. the raw debug build
			+ "return new URL(base, document.baseURI).href;")
	private static native String baseUrl();
}
