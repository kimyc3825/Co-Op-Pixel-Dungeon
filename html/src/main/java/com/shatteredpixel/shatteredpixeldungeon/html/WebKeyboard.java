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
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * On-screen keyboard for touch devices.
 * Browsers only show a virtual keyboard for a focused text element, so the page keeps a hidden
 * <input> (see index.html). Characters typed into it are sent to the game as libGDX keyTyped events,
 * which is what scene2d's TextField (used by Shattered's TextInput) consumes, including '\b' and '\n'.
 */
public class WebKeyboard {

	@JSFunctor
	public interface CharListener extends JSObject {
		void typed( int c );
	}

	private static QueuedInput input;

	public static void install( QueuedInput target ){
		input = target;
		register(new CharListener() {
			@Override
			public void typed(int c) {
				if (input != null) input.keyTyped((char) c);
			}
		});
	}

	public static void setVisible( boolean visible, boolean multiline ){
		setVisibleJS(visible, multiline);
	}

	@JSBody(params = "listener", script = "window.spd = window.spd || {}; window.spd.onKeyboardChar = listener;")
	private static native void register( CharListener listener );

	@JSBody(params = { "visible", "multiline" },
			script = "if (window.spd && window.spd.keyboard) window.spd.keyboard(visible, multiline);")
	private static native void setVisibleJS( boolean visible, boolean multiline );

}
