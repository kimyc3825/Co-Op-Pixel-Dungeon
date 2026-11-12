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
 * Small bridge to the browser and to the helpers defined in webapp/index.html (window.spd).
 * Every helper on the page side is optional, so the game still runs if the page is replaced.
 */
public class WebJS {

	@JSFunctor
	public interface Callback extends JSObject {
		void call();
	}

	@JSFunctor
	public interface StateProvider extends JSObject {
		String state();
	}

	@JSBody(params = "p", script = "window.spd = window.spd || {}; window.spd.state = p;")
	public static native void registerState(StateProvider p);

	@JSFunctor
	public interface Toggle extends JSObject {
		void set(boolean on);
	}

	//test tooling, see AutoTest
	@JSBody(params = { "toggle", "status" }, script = "window.spd = window.spd || {};"
			+ " window.spd.autotest = toggle; window.spd.autotestStatus = status;")
	public static native void registerAutoTest(Toggle toggle, StateProvider status);

	@JSBody(params = "cb", script = "window.spd = window.spd || {}; window.spd.onFullscreenExit = cb;")
	public static native void onFullscreenExit(Callback cb);

	@JSBody(script = "return /iPad|iPhone|iPod/.test(navigator.userAgent)"
			+ " || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);")
	public static native boolean isIOS();

	@JSBody(script = "return (window.spd && window.spd.language) || navigator.language || (navigator.languages && navigator.languages[0]) || 'en';")
	public static native String browserLanguage();

	@JSBody(script = "return /Android/i.test(navigator.userAgent);")
	public static native boolean isAndroid();

	@JSBody(script = "return /Mac/i.test(navigator.platform || navigator.userAgent);")
	public static native boolean isMac();

	@JSBody(script = "return /Win/i.test(navigator.platform || navigator.userAgent);")
	public static native boolean isWindows();

	//major iOS version, 0 if unknown / not iOS
	@JSBody(script = "var m = navigator.userAgent.match(/OS (\\d+)_/) || navigator.userAgent.match(/Version\\/(\\d+)/);"
			+ "return m ? parseInt(m[1]) : 0;")
	public static native int iOSVersion();

	@JSBody(script = "try { var a = document.createElement('audio');"
			+ " return !!a.canPlayType && a.canPlayType('audio/ogg; codecs=\"vorbis\"') !== ''; }"
			+ " catch (e) { return false; }")
	public static native boolean canPlayOgg();

	@JSBody(script = "return !!(navigator.vibrate);")
	public static native boolean canVibrate();

	@JSBody(params = "ms", script = "try { navigator.vibrate(ms); } catch (e) {}")
	public static native void vibrate(int ms);

	@JSBody(script = "var d = document.documentElement;"
			+ " return !!(d.requestFullscreen || d.webkitRequestFullscreen);")
	public static native boolean fullscreenSupported();

	@JSBody(params = "on", script = "if (window.spd && window.spd.setFullscreen) window.spd.setFullscreen(on);")
	public static native void setFullscreen(boolean on);

	@JSBody(params = "landscape", script = "if (window.spd && window.spd.setLandscape) window.spd.setLandscape(landscape);")
	public static native void setLandscape(boolean landscape);

	@JSBody(params = "url", script = "var w = window.open(url, '_blank', 'noopener'); return !!w;")
	public static native boolean openURL(String url);

	//safe area insets in CSS pixels, [left, top, right, bottom]
	@JSBody(script = "return (window.spd && window.spd.safeInsets) ? window.spd.safeInsets() : [0,0,0,0];")
	public static native double[] safeInsets();

	@JSBody(script = "return window.devicePixelRatio || 1;")
	public static native double devicePixelRatio();

	@JSBody(params = { "done", "total" }, script = "if (window.spd && window.spd.progress) window.spd.progress(done, total);")
	public static native void loadProgress(int done, int total);

	@JSBody(script = "if (window.spd && window.spd.loaded) window.spd.loaded();")
	public static native void loaded();

	@JSBody(params = "message", script = "console.error(message); if (window.spd && window.spd.crash) window.spd.crash(message);")
	public static native void crash(String message);

	public static void exit() { exit(false); }

	@JSBody(params = "defeated", script = "if (window.spd && window.spd.exit) window.spd.exit(defeated ? 'death' : null);")
	public static native void exit(boolean defeated);

	@JSBody(script = "if (window.spd && window.spd.ready) window.spd.ready();")
	public static native void ready();

	@JSBody(params = "callback", script = "window.addEventListener('message', function (event) {"
			+ " if (window.parent !== window && event.source === window.parent && event.origin === location.origin"
			+ " && event.data && event.data.type === 'noxac-original-return-request') callback(); });")
	public static native void onReturnRequest(Callback callback);

	@JSBody(params = "dump", script = "window.spd = window.spd || {}; window.spd.seedCheckResult = dump;"
			+ " console.log(dump); if (window.spd.loaded) window.spd.loaded();"
			+ " var pre = document.createElement('pre'); pre.id = 'seedcheck'; pre.textContent = dump;"
			+ " pre.style.cssText = 'position:fixed;inset:0;margin:0;overflow:auto;background:#000;color:#ddd;font-size:11px;z-index:20;user-select:text;-webkit-user-select:text';"
			+ " document.body.appendChild(pre);"
			+ " var a = document.createElement('a'); a.textContent = 'Download'; a.download = 'seedcheck-web.txt';"
			+ " a.href = URL.createObjectURL(new Blob([dump], {type: 'text/plain'}));"
			+ " a.style.cssText = 'position:fixed;top:8px;right:8px;z-index:21;padding:6px 12px;background:#ffcc33;color:#000;font:14px monospace';"
			+ " document.body.appendChild(a);")
	public static native void seedCheckResult(String dump);

	@JSBody(params = "key", script = "try { return new URLSearchParams(location.search).get(key); } catch (e) { return null; }")
	public static native String queryParam(String key);

	@JSBody(params = "key", script = "return window.parent === window ? null : new URLSearchParams(location.hash.slice(1)).get(key);")
	public static native String innProfileField(String key);

}
