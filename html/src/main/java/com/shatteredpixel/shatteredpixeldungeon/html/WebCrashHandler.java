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

import com.watabou.noosa.Game;

import java.io.PrintWriter;
import java.io.StringWriter;

//web equivalent of the crash dialog that DesktopLauncher shows through tinyfd
public class WebCrashHandler {

	private static boolean crashed = false;

	public static void handle( Throwable t ){
		try {
			Game.reportException(t);
		} catch (Throwable ignored) {
			//reporting must never hide the original error
		}
		if (crashed) return;
		crashed = true;

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		pw.flush();
		String msg = sw.toString();
		//errors raised by JavaScript itself (TypeError etc.) carry the useful stack in the JS error
		String jsStack = jsStack(t);
		if (jsStack != null) {
			msg += "\nJavaScript stack:\n" + jsStack;
		}
		if (msg.length() > 8000){
			msg = msg.substring(0, 8000) + "...";
		}
		WebJS.crash("Shattered Pixel Dungeon has run into an error it cannot recover from and has crashed, sorry about that!\n\n"
				+ "version: " + Game.version + "\n" + msg);
	}

	@org.teavm.jso.JSBody(params = "t", script = "var e = t && t.$jsException; return e && e.stack ? String(e.stack) : null;")
	private static native String jsStack(Throwable t);

	public static void install(){
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				handle(throwable);
			}
		});
	}

}
