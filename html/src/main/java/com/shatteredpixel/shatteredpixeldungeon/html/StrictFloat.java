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

import org.teavm.jso.JSBody;

/**
 * Runtime half of html.compiler.SPDStrictFloat: rounds a JavaScript number to the nearest 32-bit float,
 * which is what the JVM does after every float operation.
 */
public final class StrictFloat {

	private StrictFloat(){}

	//Called from the calls html.compiler.SPDStrictFloat inserts. This must stay a regular method:
	// TeaVM's JSO rewrites call sites of @JSBody methods, and depending on the transformer order it can
	// run before SPDStrictFloat added them, leaving a call to a native method with no implementation.
	public static float round( float x ){
		return fround( x );
	}

	@JSBody(params = "x", script = "return Math.fround(x);")
	private static native float fround( float x );

}
