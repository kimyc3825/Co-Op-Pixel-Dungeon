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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.watabou.input.ControllerHandler;
import com.watabou.utils.DeviceCompat;
import com.watabou.utils.PlatformSupport;
import com.watabou.utils.RectF;

import java.util.ArrayList;
import java.util.HashMap;

public class WebPlatformSupport extends PlatformSupport {

	public WebPlatformSupport(){
		WebJS.onFullscreenExit(new WebJS.Callback() {
			@Override
			public void call() {
				SPDWebApplication.gameThread().post(new Runnable() {
					@Override
					public void run() {
						if (SPDSettings.fullscreen()) SPDSettings.fullscreen(false);
					}
				});
			}
		});
	}

	@Override
	public void updateDisplaySize() {
		//the canvas always fills the browser viewport, nothing to remember (desktop stores window size)
	}

	@Override
	public boolean supportsFullScreen() {
		//the play page's button owns full screen
		return false;
	}

	@Override
	public void updateSystemUI() {
		if (supportsFullScreen()) {
			WebJS.setFullscreen(SPDSettings.fullscreen());
		}
		if (DeviceCompat.isAndroid()) {
			WebJS.setLandscape(SPDSettings.landscape());
		}
	}

	@Override
	public boolean connectedToUnmeteredNetwork() {
		return true; //no updates or news are downloaded by the web build
	}

	@Override
	public boolean supportsVibration() {
		return WebJS.canVibrate() || ControllerHandler.vibrationSupported();
	}

	@Override
	public void vibrate(int millis) {
		if (ControllerHandler.isControllerConnected()) {
			ControllerHandler.vibrate(millis);
		} else if (WebJS.canVibrate()) {
			WebJS.vibrate(millis);
		}
	}

	@Override
	public RectF getSafeInsets(int level) {
		double[] insets = WebJS.safeInsets();
		float scale = (float) WebJS.devicePixelRatio();
		if (insets == null || insets.length < 4) return new RectF();
		return new RectF(
				(float) insets[0] * scale,
				(float) insets[1] * scale,
				(float) insets[2] * scale,
				(float) insets[3] * scale);
	}

	@Override
	public boolean openURI(String uri) {
		return WebJS.openURL(uri);
	}

	@Override
	public void setOnscreenKeyboardVisible(boolean value, boolean multiline) {
		WebKeyboard.setVisible(value, multiline);
	}

	/* FONT SUPPORT, identical to DesktopPlatformSupport */

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

	@Override
	protected FreeTypeFontGenerator getGeneratorForString( String input ){
		for (int i = 0; i < input.length(); i++){
			if (isAsian(input.charAt(i))) return asianFontGenerator;
		}
		return basicFontGenerator;
	}

	/*
	 * Desktop uses java.util.regex with \p{InXxx} unicode block classes for the two methods below.
	 * TeaVM's regex engine doesn't implement unicode blocks, so the same rules are applied by hand,
	 * with the exact block ranges used by the JDK:
	 *   Hangul_Syllables                 AC00-D7AF
	 *   CJK_Unified_Ideographs           4E00-9FFF
	 *   CJK_Symbols_and_Punctuation      3000-303F
	 *   Halfwidth_and_Fullwidth_Forms    FF00-FFEF
	 *   Hiragana                         3040-309F
	 *   Katakana                         30A0-30FF
	 */

	//font selection: asianMatcher in DesktopPlatformSupport
	private static boolean isAsian( char c ){
		return (c >= 0xAC00 && c <= 0xD7AF)
				|| isSplitBlock(c) != 0
				|| (c >= 0xFF00 && c <= 0xFFEF);
	}

	//blocks that text is split around in DesktopPlatformSupport.regularsplitter, 0 = none
	private static int isSplitBlock( char c ){
		if (c >= 0x3040 && c <= 0x309F) return 1; //Hiragana
		if (c >= 0x30A0 && c <= 0x30FF) return 2; //Katakana
		if (c >= 0x4E00 && c <= 0x9FFF) return 3; //CJK_Unified_Ideographs
		if (c >= 0x3000 && c <= 0x303F) return 4; //CJK_Symbols_and_Punctuation
		return 0;
	}

	@Override
	public String[] splitforTextBlock(String text, boolean multiline) {
		//Equivalent of Pattern.split with zero-width lookaround splits:
		// a boundary exists before/after every '\n', '_', '**' (and ' ' when multiline)
		// and before/after every character in one of the CJK blocks above.
		// Like Pattern.split, empty leading/trailing pieces are dropped,
		// and no empty strings are produced for adjacent boundaries.
		int len = text.length();
		boolean[] boundary = new boolean[len + 1];

		for (int i = 0; i < len; i++){
			char c = text.charAt(i);
			if (c == '\n' || c == '_' || (multiline && c == ' ') || isSplitBlock(c) != 0){
				boundary[i] = true;
				boundary[i+1] = true;
			} else if (c == '*' && i + 1 < len && text.charAt(i+1) == '*'){
				boundary[i] = true;
				boundary[i+2] = true;
			}
		}
		//"(?<=\*\*)" also matches in the middle of "***", which the loop above can miss
		for (int i = 2; i <= len; i++){
			if (text.charAt(i-1) == '*' && text.charAt(i-2) == '*') boundary[i] = true;
		}

		ArrayList<String> result = new ArrayList<>();
		int start = 0;
		for (int i = 1; i < len; i++){
			if (boundary[i]){
				result.add(text.substring(start, i));
				start = i;
			}
		}
		if (len > 0) result.add(text.substring(start));

		//Pattern.split removes trailing empty strings; a zero-width match at index 0 never
		// produces a leading empty string (Java 8+), so nothing else to strip.
		return result.toArray(new String[0]);
	}
}
