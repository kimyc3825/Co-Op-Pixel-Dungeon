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

import com.badlogic.gdx.Preferences;

import java.util.HashMap;
import java.util.Map;

//non-persistent settings, used by the SeedCheck verification tool so it runs with default settings
public class MemoryPreferences implements Preferences {
	private final Map<String, Object> values = new HashMap<>();

	public Preferences putBoolean(String key, boolean val) { values.put(key, val); return this; }
	public Preferences putInteger(String key, int val) { values.put(key, val); return this; }
	public Preferences putLong(String key, long val) { values.put(key, val); return this; }
	public Preferences putFloat(String key, float val) { values.put(key, val); return this; }
	public Preferences putString(String key, String val) { values.put(key, val); return this; }
	public Preferences put(Map<String, ?> vals) { values.putAll(vals); return this; }
	public boolean getBoolean(String key) { return getBoolean(key, false); }
	public int getInteger(String key) { return getInteger(key, 0); }
	public long getLong(String key) { return getLong(key, 0); }
	public float getFloat(String key) { return getFloat(key, 0); }
	public String getString(String key) { return getString(key, ""); }
	public boolean getBoolean(String key, boolean defValue) { Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
	public int getInteger(String key, int defValue) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : defValue; }
	public long getLong(String key, long defValue) { Object v = values.get(key); return v instanceof Long ? (Long) v : defValue; }
	public float getFloat(String key, float defValue) { Object v = values.get(key); return v instanceof Float ? (Float) v : defValue; }
	public String getString(String key, String defValue) { Object v = values.get(key); return v instanceof String ? (String) v : defValue; }
	public Map<String, ?> get() { return values; }
	public boolean contains(String key) { return values.containsKey(key); }
	public void clear() { values.clear(); }
	public void remove(String key) { values.remove(key); }
	public void flush() { }
}
