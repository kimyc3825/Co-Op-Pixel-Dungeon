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

package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.watabou.input.ControllerHandler;
import com.watabou.noosa.Game;
import com.watabou.utils.PlatformSupport;
import com.watabou.utils.Point;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WindowsLibrary;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DesktopPlatformSupport extends PlatformSupport {

	private WindowIme windowIme;

	public void enableWindowImeOnTextInputOnly() {
		if (windowIme != null || Platform.get() != Platform.WINDOWS || !(Gdx.graphics instanceof Lwjgl3Graphics)) return;
		long glfwWindow = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
		long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
		if (hwnd == 0) return;
		WindowsLibrary library = new WindowsLibrary("imm32.dll");
		long associate = library.getFunctionAddress("ImmAssociateContext");
		if (associate == 0) {
			library.free();
			return;
		}
		windowIme = new WindowIme(library, associate, hwnd);
		windowIme.apply();
	}

	@Override
	public void setTextInputActive(boolean active) {
		WindowIme ime = windowIme;
		if (ime != null) ime.setTextInputActive(active);
	}

	public void restoreWindowIme() {
		WindowIme ime = windowIme;
		windowIme = null;
		if (ime != null) ime.close();
	}

	private static class WindowIme {
		private final Thread owner = Thread.currentThread();
		private final WindowsLibrary library;
		private final long associate;
		private final long hwnd;
		private final WindowProc windowProc;
		private final long previousWindowProc;
		private long context;
		private int editors;
		private boolean detached;
		private boolean closed;

		WindowIme(WindowsLibrary library, long associate, long hwnd) {
			this.library = library;
			this.associate = associate;
			this.hwnd = hwnd;
			WindowProc callback = WindowProc.create(this::handleWindowMessage);
			previousWindowProc = User32.SetWindowLongPtr(hwnd, User32.GWL_WNDPROC, callback.address());
			if (previousWindowProc == 0) {
				callback.free();
				windowProc = null;
			} else {
				windowProc = callback;
			}
		}

		private synchronized long handleWindowMessage(long window, int message, long key, long data) {
			// GLFW ignores VK_PROCESSKEY. Some Windows IMEs can still mark a movement
			// key as processed after the context is detached. Keep GLFW's key state
			// in sync, including releases if a text editor opens while a key is held.
			if (!closed && key == User32.VK_PROCESSKEY
					&& ((message == User32.WM_KEYDOWN && editors == 0) || message == User32.WM_KEYUP)) {
				int movementKey = movementVirtualKey((int) (data >>> 16) & 0x1FF);
				if (movementKey != 0) key = movementKey;
			}
			return User32.nCallWindowProc(previousWindowProc, window, message, key, data);
		}

		private static int movementVirtualKey(int scanCode) {
			switch (scanCode) {
				case 0x011: return 'W';
				case 0x01E: return 'A';
				case 0x01F: return 'S';
				case 0x020: return 'D';
				case 0x148: return User32.VK_UP;
				case 0x14B: return User32.VK_LEFT;
				case 0x150: return User32.VK_DOWN;
				case 0x14D: return User32.VK_RIGHT;
				case 0x047: return User32.VK_NUMPAD7;
				case 0x048: return User32.VK_NUMPAD8;
				case 0x049: return User32.VK_NUMPAD9;
				case 0x04B: return User32.VK_NUMPAD4;
				case 0x04D: return User32.VK_NUMPAD6;
				case 0x04F: return User32.VK_NUMPAD1;
				case 0x050: return User32.VK_NUMPAD2;
				case 0x051: return User32.VK_NUMPAD3;
				default: return 0;
			}
		}

		synchronized void setTextInputActive(boolean active) {
			if (closed) return;
			editors = active ? editors + 1 : Math.max(0, editors - 1);
			if (Thread.currentThread() == owner) apply();
			else Gdx.app.postRunnable(this::apply);
		}

		synchronized void apply() {
			boolean detach = editors == 0;
			if (closed || detach == detached) return;
			// GLFW drops key presses consumed by the Windows IME. Associate the original
			// context only while a text field is open, without changing the selected language.
			if (detach) context = JNI.invokePPP(hwnd, 0, associate);
			else JNI.invokePPP(hwnd, context, associate);
			detached = detach;
		}

		synchronized void close() {
			if (closed) return;
			closed = true;
			if (detached) JNI.invokePPP(hwnd, context, associate);
			if (windowProc != null) {
				User32.SetWindowLongPtr(hwnd, User32.GWL_WNDPROC, previousWindowProc);
				windowProc.free();
			}
			library.free();
		}
	}

	//we recall previous window sizes as a workaround to not save maximized size to settings
	//have to do this as updateDisplaySize is called before maximized is set =S
	protected static Point[] previousSizes = null;

	@Override
	public void updateDisplaySize() {
		if (previousSizes == null){
			previousSizes = new Point[2];
			previousSizes[1] = SPDSettings.windowResolution();
		} else {
			previousSizes[1] = previousSizes[0];
		}
		previousSizes[0] = new Point(Game.width, Game.height);
		if (!SPDSettings.fullscreen()) {
			SPDSettings.windowResolution( previousSizes[0] );
		}
	}

	private static boolean first = true;

	@Override
	public void updateSystemUI() {
		Gdx.app.postRunnable( new Runnable() {
			@Override
			public void run () {
				if (SPDSettings.fullscreen()){
					int monitorNum = 0;
					if (!first){
						Graphics.Monitor[] monitors = Gdx.graphics.getMonitors();
						for (int i = 0; i < monitors.length; i++){
							if (((Lwjgl3Graphics.Lwjgl3Monitor)Gdx.graphics.getMonitor()).getMonitorHandle()
									== ((Lwjgl3Graphics.Lwjgl3Monitor)monitors[i]).getMonitorHandle()) {
								monitorNum = i;
							}
						}
					} else {
						monitorNum = SPDSettings.fulLScreenMonitor();
					}

					Graphics.Monitor[] monitors = Gdx.graphics.getMonitors();
					if (monitors.length <= monitorNum) {
						monitorNum = 0;
					}
					Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode(monitors[monitorNum]));
					SPDSettings.fulLScreenMonitor(monitorNum);
				} else {
					Point p = SPDSettings.windowResolution();
					Gdx.graphics.setWindowedMode( p.x, p.y );
				}
				first = false;
			}
		} );
	}
	
	@Override
	public boolean connectedToUnmeteredNetwork() {
		return true; //no easy way to check this in desktop, just assume user doesn't care
	}

	@Override
	public boolean supportsVibration() {
		//only supports vibration via controller
		return ControllerHandler.vibrationSupported();
	}

	/* FONT SUPPORT */
	
	//custom pixel font, for use with Latin and Cyrillic languages
	private static FreeTypeFontGenerator basicFontGenerator;
	//droid sans fallback, for asian fonts
	private static FreeTypeFontGenerator asianFontGenerator;
	
	@Override
	public void setupFontGenerators(int pageSize, boolean systemfont) {
		//don't bother doing anything if nothing has changed
		if (fonts != null && this.pageSize == pageSize && this.systemfont == systemfont){
			return;
		}
		this.pageSize = pageSize;
		this.systemfont = systemfont;

		resetGenerators(false);
		fonts = new HashMap<>();

		if (systemfont) {
			basicFontGenerator = asianFontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("fonts/droid_sans.ttf"));
		} else {
			basicFontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("fonts/pixel_font.ttf"));
			asianFontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("fonts/droid_sans.ttf"));
		}
		
		fonts.put(basicFontGenerator, new HashMap<>());
		fonts.put(asianFontGenerator, new HashMap<>());
		
		packer = new PixmapPacker(pageSize, pageSize, Pixmap.Format.RGBA8888, 1, false);
	}
	
	private static Matcher asianMatcher = Pattern.compile("\\p{InHangul_Syllables}|\\p{InHangul_Jamo}|\\p{InHangul_Compatibility_Jamo}|" +
			"\\p{InCJK_Unified_Ideographs}|\\p{InCJK_Symbols_and_Punctuation}|\\p{InHalfwidth_and_Fullwidth_Forms}|" +
			"\\p{InHiragana}|\\p{InKatakana}").matcher("");

	@Override
	protected FreeTypeFontGenerator getGeneratorForString( String input ){
		if (asianMatcher.reset(input).find()){
			return asianFontGenerator;
		} else {
			return basicFontGenerator;
		}
	}
	
	//splits on newline (for layout), chinese/japanese (for font choice), and '_'/'**' (for highlighting)
	private Pattern regularsplitter = Pattern.compile(
			"(?<=\n)|(?=\n)|(?<=_)|(?=_)|(?<=\\*\\*)|(?=\\*\\*)|" +
					"(?<=\\p{InHiragana})|(?=\\p{InHiragana})|" +
					"(?<=\\p{InKatakana})|(?=\\p{InKatakana})|" +
					"(?<=\\p{InCJK_Unified_Ideographs})|(?=\\p{InCJK_Unified_Ideographs})|" +
					"(?<=\\p{InCJK_Symbols_and_Punctuation})|(?=\\p{InCJK_Symbols_and_Punctuation})");
	
	//additionally splits on spaces, so that each word can be laid out individually
	private Pattern regularsplitterMultiline = Pattern.compile(
			"(?<= )|(?= )|(?<=\n)|(?=\n)|(?<=_)|(?=_)|(?<=\\*\\*)|(?=\\*\\*)|" +
					"(?<=\\p{InHiragana})|(?=\\p{InHiragana})|" +
					"(?<=\\p{InKatakana})|(?=\\p{InKatakana})|" +
					"(?<=\\p{InCJK_Unified_Ideographs})|(?=\\p{InCJK_Unified_Ideographs})|" +
					"(?<=\\p{InCJK_Symbols_and_Punctuation})|(?=\\p{InCJK_Symbols_and_Punctuation})");
	
	@Override
	public String[] splitforTextBlock(String text, boolean multiline) {
		if (multiline) {
			return regularsplitterMultiline.split(text);
		} else {
			return regularsplitter.split(text);
		}
	}
}
