// SPDX-License-Identifier: GPL-3.0-or-later
(function () {
	"use strict";

	var spd = window.spd = window.spd || {};

	// ---- smoke test mode (?autotest, see AutoTest.java) -----------------------------------
	// Browsers pause requestAnimationFrame and throttle short timeouts in hidden tabs, which stops the game
	// (correct for players). For unattended test runs only, frames always come from a MessageChannel pump
	// (~60/s) and short timeouts use it too while the tab is hidden, so the bot keeps playing in a background window.
	if (/[?&]autotest\b/.test(location.search)) {
		(function () {
			var hidden = function () { return document.visibilityState === "hidden"; };
			var tasks = new MessageChannel(), queue = [];
			tasks.port1.onmessage = function () { var f = queue.shift(); if (f) f(); };
			var setTimeoutOriginal = window.setTimeout;
			window.setTimeout = function (fn, ms) {
				if (hidden() && typeof fn === "function" && !(ms > 4)) {
					var args = Array.prototype.slice.call(arguments, 2);
					queue.push(function () { fn.apply(null, args); });
					tasks.port2.postMessage(0);
					return 0;
				}
				return setTimeoutOriginal.apply(window, arguments);
			};
			var frames = new MessageChannel(), waiting = [], last = 0, pumping = false;
			frames.port1.onmessage = function () {
				var now = performance.now();
				if (now - last >= 16 && waiting.length) {
					last = now;
					var run = waiting; waiting = [];
					run.forEach(function (cb) { cb(now); });
				}
				if (waiting.length) frames.port2.postMessage(0); else pumping = false;
			};
			window.requestAnimationFrame = function (cb) {
				waiting.push(cb);
				if (!pumping) { pumping = true; frames.port2.postMessage(0); }
				return 0;
			};
		})();
	}
	var $ = function (id) { return document.getElementById(id); };
	var canvas = $("canvas");
	document.addEventListener("contextmenu", function (event) { event.preventDefault(); });
	document.querySelectorAll(".reload-game").forEach(function (button) {
		button.addEventListener("click", function () { location.reload(); });
	});
	var ready = false;
	var finished = false;
	var exiting = false;
	spd.prepareForDetach = function () { exiting = true; };
	document.addEventListener("visibilitychange", function (event) {
		// Detaching the frame can dispatch this after the backend input has been disposed.
		if (exiting) event.stopImmediatePropagation();
	}, true);
	function notifyParent(type, percent) {
		if (window.parent === window) return;
		var message = { type: type };
		if (typeof percent === "number") message.percent = percent;
		window.parent.postMessage(message, location.origin);
	}
	spd.ready = function () {
		if (ready || finished) return;
		ready = true;
		$("loader").classList.add("hidden");
		canvas.focus();
		notifyParent("noxac-original-ready");
	};

	// ---- loading screen -------------------------------------------------------------------
	var barFill = $("bar").firstElementChild;
	var lastPercent = -1;
	spd.progress = function (done, total) {
		var pct = total > 0 ? Math.floor(done * 100 / total) : 0;
		if (!Number.isFinite(pct)) return;
		pct = Math.max(0, Math.min(100, pct));
		if (pct === lastPercent) return;
		lastPercent = pct;
		barFill.style.width = pct + "%";
		$("status").textContent = "원본 게임 불러오는 중… " + pct + "%";
		notifyParent("noxac-original-progress", pct);
	};
	spd.loaded = function () {
		spd.progress(1, 1);
		$("status").textContent = "원본 게임을 시작하는 중…";
		// the game draws its first frames a moment after the assets are ready
		setTimeout(function () { $("loader").classList.add("hidden"); canvas.focus(); }, 400);
	};

	// ---- crash / exit ---------------------------------------------------------------------
	spd.crash = function (message) {
		if (finished) return;
		finished = true;
		$("loader").classList.add("hidden");
		$("crash-text").textContent = message;
		$("crash").classList.remove("hidden");
		notifyParent("noxac-original-error");
	};
	$("crash-copy").onclick = function () {
		var text = $("crash-text").textContent;
		if (navigator.clipboard) navigator.clipboard.writeText(text);
	};
	spd.exit = function () {
		if (finished) return;
		finished = true;
		exiting = true;
		$("closed").classList.remove("hidden");
		if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
		notifyParent("noxac-original-exit");
	};
	window.addEventListener("error", function (e) {
		// script failed to load or an error escaped the game loop
		if (e && e.target && e.target.tagName === "SCRIPT") {
			spd.crash("Failed to load " + e.target.src + "\nPlease check your connection and reload the page.");
		}
	}, true);

	// ---- fullscreen / orientation --------------------------------------------------------
	spd.setFullscreen = function (on) {
		var d = document.documentElement;
		var isFull = !!(document.fullscreenElement || document.webkitFullscreenElement);
		try {
			if (on && !isFull) {
				var req = d.requestFullscreen || d.webkitRequestFullscreen;
				if (req) {
					var p = req.call(d, { navigationUI: "hide" });
					if (p && p.catch) p.catch(function () { /* needs a user gesture, retried on next tap */ pendingFullscreen = true; });
				}
			} else if (!on && isFull) {
				var exit = document.exitFullscreen || document.webkitExitFullscreen;
				if (exit) exit.call(document);
			}
		} catch (e) { pendingFullscreen = on; }
		if (!on) pendingFullscreen = false;
	};
	var pendingFullscreen = false;
	spd.setLandscape = function (landscape) {
		try {
			if (screen.orientation && screen.orientation.lock) {
				screen.orientation.lock(landscape ? "landscape" : "portrait").catch(function () {});
			}
		} catch (e) { }
	};

	// ---- safe area insets (CSS px: left, top, right, bottom) ------------------------------
	spd.safeInsets = function () {
		var cs = getComputedStyle($("insets"));
		return [parseFloat(cs.paddingLeft) || 0, parseFloat(cs.paddingTop) || 0,
				parseFloat(cs.paddingRight) || 0, parseFloat(cs.paddingBottom) || 0];
	};

	// ---- on-screen keyboard for touch devices ---------------------------------------------
	var kb = $("kb");
	var kbWanted = false;
	var textInputActive = false;
	var isTouch = ("ontouchstart" in window) || navigator.maxTouchPoints > 0;
	spd.keyboard = function (visible, multiline) {
		textInputActive = visible;
		if (!isTouch) return;
		kbWanted = visible;
		if (visible) {
			kb.value = "";
			try { kb.focus({ preventScroll: true }); } catch (e) { }
		} else {
			kb.blur();
		}
	};
	function sendChar(c) {
		if (spd.onKeyboardChar) spd.onKeyboardChar(c);
	}
	// the field is kept empty: every insertion/deletion is forwarded as typed characters
	kb.addEventListener("beforeinput", function (e) {
		var t = e.inputType || "";
		if (t.indexOf("delete") === 0) {
			sendChar(8);
		} else if (t === "insertLineBreak" || t === "insertParagraph") {
			sendChar(10);
		} else if (e.data) {
			for (var i = 0; i < e.data.length; i++) sendChar(e.data.charCodeAt(i));
		}
		e.preventDefault();
	});
	// fallback for browsers without beforeinput support
	kb.addEventListener("input", function () {
		var v = kb.value;
		for (var i = 0; i < v.length; i++) sendChar(v.charCodeAt(i) === 10 ? 10 : v.charCodeAt(i));
		kb.value = "";
	});
	// keys typed into the hidden field must not also reach the game's keyboard handler
	["keydown", "keyup", "keypress"].forEach(function (type) {
		kb.addEventListener(type, function (e) {
			e.stopPropagation();
			if (type === "keydown" && (e.key === "Backspace" || e.key === "Enter") && e.keyCode !== 229 && !e.isComposing) {
				// hardware keys on a touch device: handled here since beforeinput may not fire for them
				sendChar(e.key === "Backspace" ? 8 : 10);
				e.preventDefault();
			}
		});
	});

	// Supply the movement code when an IME only reports a key label. Let libGDX track key state.
	var movementLabels = new Map([
		["w", "KeyW"], ["ᄌ", "KeyW"], ["ᄍ", "KeyW"],
		["a", "KeyA"], ["ᄆ", "KeyA"], ["s", "KeyS"], ["ᄂ", "KeyS"], ["d", "KeyD"], ["ᄋ", "KeyD"]
	]);
	function normalizeMovement(event) {
		if (event.target !== canvas || textInputActive || event.defaultPrevented ||
				(event.code && event.code !== "Unidentified")) return;
		if (event.type === "keydown" && (event.altKey || event.ctrlKey || event.metaKey)) return;
		var label = event.key.normalize("NFKC").toLowerCase();
		var code = movementLabels.get(label);
		if (event.location === KeyboardEvent.DOM_KEY_LOCATION_NUMPAD && /^[1-9]$/.test(label)) code = "Numpad" + label;
		if (/^Arrow(Up|Down|Left|Right)$/.test(event.key)) code = event.key;
		if (!code) return;
		event.preventDefault();
		event.stopImmediatePropagation();
		canvas.dispatchEvent(new KeyboardEvent(event.type, {
			key: event.key, code: code, location: event.location, repeat: event.repeat,
			shiftKey: event.shiftKey, altKey: event.altKey, ctrlKey: event.ctrlKey, metaKey: event.metaKey,
			bubbles: true, cancelable: true
		}));
	}
	document.addEventListener("keydown", normalizeMovement, true);
	document.addEventListener("keyup", normalizeMovement, true);

	// ---- user gestures: audio unlock, deferred fullscreen and keyboard focus --------------
	function onGesture() {
		// Howler unlocks itself on the first interaction too; this also covers a context that
		// the browser suspended again later (e.g. iOS after switching apps)
		try {
			if (window.Howler && Howler.ctx && Howler.ctx.state !== "running") Howler.ctx.resume();
		} catch (e) { }
		if (pendingFullscreen) { pendingFullscreen = false; spd.setFullscreen(true); }
		if (kbWanted && document.activeElement !== kb) {
			try { kb.focus({ preventScroll: true }); } catch (e) { }
		}
	}
	["pointerup", "touchend", "keydown", "mousedown"].forEach(function (type) {
		window.addEventListener(type, onGesture, { passive: true, capture: true });
	});

	// stop page scrolling / pinch zoom / iOS rubber banding on the game surface
	document.addEventListener("touchmove", function (e) { if (e.target === canvas) e.preventDefault(); }, { passive: false });
	document.addEventListener("gesturestart", function (e) { e.preventDefault(); });
	// browser shortcuts that clash with game key bindings (scrolling with space/arrows, tab focus change...)
	window.addEventListener("keydown", function (e) {
		if (e.target === kb) return;
		if (["Space", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Tab", "Backspace",
			 "PageUp", "PageDown", "Home", "End"].indexOf(e.code) >= 0) {
			e.preventDefault();
		}
	});

	// ---- save durability: write-ahead log for IndexedDB ------------------------------------
	// Game files (saves, badges, rankings, journal) live in IndexedDB. The game saves when it is paused,
	// which on the web happens in visibilitychange/pagehide while the tab is being closed or reloaded,
	// and browsers may drop IndexedDB transactions that are still in flight at that point.
	// Every write/delete to the game's database is therefore also recorded synchronously in
	// localStorage, removed once IndexedDB confirms it, and replayed on the next start if it never landed.
	var DB_NAME = "noxac-original-files";
	var WAL = "noxac-original-wal:";
	var walSeq = Date.now();

	function toBase64(bytes) {
		var s = "";
		for (var i = 0; i < bytes.length; i += 0x8000) {
			s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
		}
		return btoa(s);
	}
	function fromBase64(str) {
		var s = atob(str), bytes = new Uint8Array(s.length);
		for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i);
		return bytes;
	}
	function isGameDB(store) {
		try { return store.transaction.db.name === DB_NAME; } catch (e) { return false; }
	}
	function walRecord(key, entry) {
		var id = WAL + key;
		var seq = ++walSeq;
		entry.seq = seq;
		try {
			localStorage.setItem(id, JSON.stringify(entry));
		} catch (e) {
			return null; // storage full: IndexedDB alone will have to do
		}
		return function () {
			try {
				var cur = localStorage.getItem(id);
				if (cur && JSON.parse(cur).seq === seq) localStorage.removeItem(id);
			} catch (e) { }
		};
	}
	(function patchIndexedDB() {
		if (!window.IDBObjectStore) return;
		var put = IDBObjectStore.prototype.put, del = IDBObjectStore.prototype["delete"];
		IDBObjectStore.prototype.put = function (value, key) {
			var request = put.apply(this, arguments);
			if (isGameDB(this) && typeof key === "string") {
				var entry = { op: "put", type: value && value.type, date: value && value.date ? +value.date : Date.now() };
				if (value && value.contents) {
					var c = value.contents;
					entry.kind = c.constructor && c.constructor.name;
					entry.data = toBase64(new Uint8Array(c.buffer, c.byteOffset, c.byteLength));
				}
				var done = walRecord(key, entry);
				if (done) this.transaction.addEventListener("complete", done);
			}
			return request;
		};
		IDBObjectStore.prototype["delete"] = function (key) {
			var request = del.apply(this, arguments);
			if (isGameDB(this) && typeof key === "string") {
				var done = walRecord(key, { op: "delete" });
				if (done) this.transaction.addEventListener("complete", done);
			}
			return request;
		};
	})();
	// applies leftover log entries to IndexedDB, then calls back (always, even on errors)
	function replayWal(callback) {
		var keys = [];
		try {
			for (var i = 0; i < localStorage.length; i++) {
				var k = localStorage.key(i);
				if (k && k.indexOf(WAL) === 0) keys.push(k);
			}
		} catch (e) { }
		if (!keys.length || !window.indexedDB) { callback(); return; }
		var req = indexedDB.open(DB_NAME, 1);
		req.onupgradeneeded = function () { req.result.createObjectStore("FILE_DATA"); };
		req.onerror = function () { callback(); };
		req.onsuccess = function () {
			var db = req.result;
			try {
				var tx = db.transaction("FILE_DATA", "readwrite");
				var store = tx.objectStore("FILE_DATA");
				keys.forEach(function (k) {
					var e = JSON.parse(localStorage.getItem(k));
					var key = k.substring(WAL.length);
					if (e.op === "delete") {
						store["delete"](key);
					} else {
						var value = { type: e.type, date: new Date(e.date) };
						if (e.data !== undefined) {
							var bytes = fromBase64(e.data);
							var Kind = window[e.kind] || Int8Array;
							value.contents = new Kind(bytes.buffer);
						}
						store.put(value, key);
					}
				});
				tx.oncomplete = function () {
					keys.forEach(function (k) { localStorage.removeItem(k); });
					db.close();
					callback();
				};
				tx.onerror = tx.onabort = function () { db.close(); callback(); };
			} catch (e) {
				db.close();
				callback();
			}
		};
	}

	// ---- start ---------------------------------------------------------------------------
	function start() {
		if (typeof main !== "function") {
			spd.crash("The game script could not be loaded. Please reload the page.");
			return;
		}
		replayWal(function () {
			try {
				main([]);
			} catch (e) {
				spd.crash(String(e && e.stack || e));
			}
		});
	}

	// build files live in a content-addressed folder so they can be cached forever (see vercel.json)
	window.SPD_BASE = document.currentScript.dataset.buildBase;
	var script = document.createElement("script");
	script.src = window.SPD_BASE + "spd.js";
	script.charset = "utf-8";
	script.onload = start;
	script.onerror = function () {
		spd.crash("Failed to download the game (" + script.src + ").\nPlease check your connection and reload the page.");
	};
	document.body.appendChild(script);
})();
