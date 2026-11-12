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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * 1:1 verification tool (not part of the game).
 *
 * Generates the first floors of a run for a given seed and hero class, exactly like the game does when
 * a run is started (Dungeon.init, then Dungeon.newLevel for each depth, as the debug start in
 * InterlevelScene does), and dumps everything that level generation decides: terrain, transitions,
 * items (class, quantity, upgrade level, curse), mobs, traps, plants and blobs.
 *
 * Lines starting with '~' are not determined by the seed (also not on desktop) and are only informative.
 *
 * The same code runs on the JVM (desktop, see the seedCheckDesktop task) and in the browser
 * (index.html?seedcheck=SEED), so the two dumps can be compared line by line.
 */
public class SeedCheck {

	//seeds and heroes are comma separated lists, every combination is generated in order
	public static String runAll( String seeds, String heroes, int depths ){
		if (seeds.equals("numberdump")) return numberDump();
		StringBuilder out = new StringBuilder();
		for (String seed : seeds.split(",")){
			for (String hero : heroes.split(",")){
				out.append(run(seed.trim(), HeroClass.valueOf(hero.trim().toUpperCase(Locale.ROOT)), depths));
			}
		}
		return out.toString();
	}

	//float semantics probe: these differ between 32-bit float math (JVM) and double math (plain JS)
	public static String floatProbe(){
		float a = Float.parseFloat("0.7");
		float b = Float.parseFloat("0.1");
		float c = (float) Integer.parseInt("16777217"); //rounds to 16777216 as a float
		float sum = 0;
		for (int i = 0; i < 10; i++) sum += b;
		return "floats " + (int)(a * 10) + " " + (sum == 1f) + " " + Float.floatToIntBits(sum)
				+ " " + Float.floatToIntBits(a / 3f) + " " + (int)(c + 1f) + " " + Float.floatToIntBits((float)Math.sqrt(a));
	}

	//number <-> text probe: Bundle (saves) stores floats/doubles as JSON text
	public static String numberProbe(){
		java.util.Random r = new java.util.Random(12345);
		int floatRoundTripFail = 0, doubleRoundTripFail = 0, jsonFail = 0, floatParseFail = 0;
		long textHash = 17, hFloatAsDouble = 17, hDouble = 17, hFloat = 17, hJson = 17;
		for (int i = 0; i < 20000; i++){
			float f;
			switch (i % 4){
				case 0: f = r.nextFloat(); break;
				case 1: f = r.nextFloat() * 1000f; break;
				case 2: f = (r.nextInt(2000) - 1000) / 10f; break;
				default: f = Float.intBitsToFloat(r.nextInt()); if (Float.isNaN(f) || Float.isInfinite(f)) f = 1f;
			}
			double d = r.nextDouble() * Math.pow(10, r.nextInt(20) - 10);

			String fs = Double.toString(f);
			String ds = Double.toString(d);
			String ffs = Float.toString(f);
			textHash = textHash * 31 + fs.hashCode();
			textHash = textHash * 31 + ds.hashCode();
			textHash = textHash * 31 + ffs.hashCode();
			hFloatAsDouble = hFloatAsDouble * 31 + fs.hashCode();
			hDouble = hDouble * 31 + ds.hashCode();
			hFloat = hFloat * 31 + ffs.hashCode();

			if ((float) Double.parseDouble(fs) != f) floatRoundTripFail++;
			if (Double.parseDouble(ds) != d) doubleRoundTripFail++;
			if (Float.parseFloat(ffs) != f) floatParseFail++;

			try {
				org.json.JSONObject o = new org.json.JSONObject();
				o.put("f", f);
				String json = o.toString();
				textHash = textHash * 31 + json.hashCode();
				hJson = hJson * 31 + json.hashCode();
				if ((float) new org.json.JSONObject(json).optDouble("f", 0.0) != f) jsonFail++;
			} catch (org.json.JSONException e){
				jsonFail++;
			}
		}
		StringBuilder samples = new StringBuilder();
		float[] fs = {1e10f, 1.1f, 0.1f, 100f, 1e-5f, Float.MAX_VALUE, Float.MIN_NORMAL, 0.3f, 16777216f, 123456.79f, 1e7f, 9999999f, 0.001f, 1234.5f};
		for (float f : fs) samples.append(' ').append(Float.toString(f));
		double[] ds = {0.1 + 0.2, 1e21, 1e-7, 100.0, 1e7, 12345678.9, 0.001, 1e-3, 123456789012.0, 2.0 / 3, (double) 0.1f};
		for (double d : ds) samples.append(' ').append(Double.toString(d));
		return "numbers floatRT=" + floatRoundTripFail + " doubleRT=" + doubleRoundTripFail
				+ " floatParse=" + floatParseFail + " json=" + jsonFail + " text=" + Long.toHexString(textHash)
				+ " floatAsDoubleText=" + Long.toHexString(hFloatAsDouble) + " doubleText=" + Long.toHexString(hDouble)
				+ " floatText=" + Long.toHexString(hFloat) + " jsonText=" + Long.toHexString(hJson)
				+ "\nsamples" + samples;
	}

	//locale-dependent formatting, done the way Messages does it, for every game language
	public static String formatProbe(){
		StringBuilder out = new StringBuilder();
		double[] decimals = {1.5, 0.333, 2.0, 12.345, 1234.5, 0.005, 2.675, 100, 0.1f, 1/3f};
		for (com.shatteredpixel.shatteredpixeldungeon.messages.Languages lang
				: com.shatteredpixel.shatteredpixeldungeon.messages.Languages.values()){
			Locale locale = lang == com.shatteredpixel.shatteredpixeldungeon.messages.Languages.ENGLISH
					? Locale.ENGLISH : new Locale(lang.code());
			StringBuilder l = new StringBuilder("format ").append(lang.code()).append(' ');
			Object[][] cases = {
					{"%,d", 1234567}, {"%d", -42}, {"%+d", 5}, {"%+d", -5}, {"%.0f%%", 12.5f}, {"%.0f%%", 13.5},
					{"%s", 1.5f}, {"%s", 0.1f}, {"%s", 2.0}, {"%s x%d", "item", 3}, {"%02d", 7}, {"%d%%", 50},
					{"% d", 4}, {"%.1f", 0.25}, {"%.2f", 1.005}, {"%s", 1e10f}, {"%s", 1e-5}
			};
			for (Object[] c : cases){
				Object[] args = java.util.Arrays.copyOfRange(c, 1, c.length);
				try {
					l.append(String.format(locale, (String) c[0], args));
				} catch (Exception e){
					l.append("ERR[").append(c[0]).append(':').append(e.getClass().getName()).append(':').append(e.getMessage()).append(']');
				}
				l.append('|');
			}
			java.text.DecimalFormat df = new java.text.DecimalFormat("#.##", java.text.DecimalFormatSymbols.getInstance(locale));
			java.text.DecimalFormat dfx = new java.text.DecimalFormat("#.##x", java.text.DecimalFormatSymbols.getInstance(locale));
			java.text.DecimalFormat d0 = new java.text.DecimalFormat("#", java.text.DecimalFormatSymbols.getInstance(locale));
			for (double d : decimals){
				l.append('|').append(df.format(d)).append('/').append(dfx.format(d)).append('/').append(d0.format(d));
			}
			l.append('|').append("istanbul iI".toUpperCase(locale)).append('|').append("ISTANBUL Iİ".toLowerCase(locale));
			out.append(l).append('\n');
		}
		return out.toString();
	}

	//every number-to-text result of the number probe, one per line (only used by the ?numberdump page)
	public static String numberDump(){
		java.util.Random r = new java.util.Random(12345);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 20000; i++){
			float f;
			switch (i % 4){
				case 0: f = r.nextFloat(); break;
				case 1: f = r.nextFloat() * 1000f; break;
				case 2: f = (r.nextInt(2000) - 1000) / 10f; break;
				default: f = Float.intBitsToFloat(r.nextInt()); if (Float.isNaN(f) || Float.isInfinite(f)) f = 1f;
			}
			double d = r.nextDouble() * Math.pow(10, r.nextInt(20) - 10);
			out.append(i).append(' ').append(Float.toString(f)).append(' ').append(Double.toString(f))
					.append(' ').append(Double.toString(d)).append('\n');
		}
		return out.toString();
	}

	public static String run( String seedText, HeroClass heroClass, int depths ){
		StringBuilder out = new StringBuilder();
		out.append(floatProbe()).append('\n');
		out.append(numberProbe()).append('\n');
		out.append(formatProbe());

		long seed = DungeonSeed.convertFromText(seedText);
		out.append("seed ").append(DungeonSeed.convertToCode(seed)).append(" (").append(seed).append(")")
				.append(" hero ").append(heroClass.name()).append('\n');

		//identical, default settings on every platform (challenges off, default language...)
		// The tutorial is marked as done and the first guide pages as found: those are placed with an
		// unseeded generator by EntranceRoom.placeEarlyGuidePages, and the cells they occupy then shift
		// trap/decoration placement on floor 1, so they would make even two desktop runs differ.
		MemoryPreferences prefs = new MemoryPreferences();
		prefs.putBoolean(SPDSettings.KEY_INTRO, false);
		SPDSettings.set(prefs);
		Document.ADVENTURERS_GUIDE.findPage(Document.GUIDE_INTRO);
		Document.ADVENTURERS_GUIDE.readPage(Document.GUIDE_INTRO);
		Document.ADVENTURERS_GUIDE.findPage(Document.GUIDE_SEARCHING);

		GamesInProgress.selectedClass = heroClass;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.seed = seed;
		Dungeon.customSeedText = seedText;
		Dungeon.init();
		out.append("challenges ").append(Dungeon.challenges).append(" version ").append(Dungeon.initialVersion).append('\n');

		for (int depth = 1; depth <= depths; depth++){
			Dungeon.depth = depth;
			Dungeon.branch = 0;
			Level level = Dungeon.newLevel();
			Dungeon.level = level;
			dumpLevel(level, depth, out);
			com.watabou.utils.Bundle heroSave = new com.watabou.utils.Bundle();
			heroSave.put("hero", Dungeon.hero);
			out.append("hero ").append(heroSave.toString()).append('\n');
			//what a save file contains for this floor (Bundle JSON, the same text Dungeon.saveLevel compresses)
			com.watabou.utils.Bundle save = new com.watabou.utils.Bundle();
			save.put("level", level);
			out.append("save ").append(save.toString()).append('\n');
		}
		return out.toString();
	}

	private static void dumpLevel( Level level, int depth, StringBuilder out ){
		out.append("== depth ").append(depth).append(' ').append(level.getClass().getName())
				.append(' ').append(level.width()).append('x').append(level.height()).append('\n');

		StringBuilder map = new StringBuilder();
		for (int y = 0; y < level.height(); y++){
			map.append("map ");
			for (int x = 0; x < level.width(); x++){
				int t = level.map[x + y * level.width()];
				map.append((char)(t < 10 ? '0' + t : 'A' + t - 10));
			}
			map.append('\n');
		}
		out.append(map);

		ArrayList<String> lines = new ArrayList<>();
		for (LevelTransition t : level.transitions){
			lines.add("transition " + t.cell() + " " + t.type + " -> " + t.destDepth + "/" + t.destBranch);
		}
		Collections.sort(lines);
		appendAll(out, lines);

		lines.clear();
		for (Heap heap : level.heaps.valueList()){
			StringBuilder h = new StringBuilder("heap ").append(heap.pos).append(' ').append(heap.type);
			for (Item item : heap.items){
				h.append(" | ").append(item.getClass().getName())
						.append(" x").append(item.quantity())
						.append(" +").append(item.trueLevel())
						.append(item.cursed ? " cursed" : "");
			}
			String line = h.toString();
			//EntranceRoom.placeEarlyGuidePages deliberately uses an unseeded generator, and later guide pages
			// depend on which pages the player already found, so these are not part of the seed: marked with ~
			if (line.contains(".journal.Guidebook") || line.contains(".journal.GuidePage")) {
				line = "~" + line;
			}
			lines.add(line);
		}
		Collections.sort(lines);
		appendAll(out, lines);

		lines.clear();
		for (Mob mob : level.mobs){
			lines.add("mob " + mob.pos + " " + mob.getClass().getName() + " hp=" + mob.HP + "/" + mob.HT + " " + (mob.state == null ? "null" : mob.state.getClass().getName()));
		}
		Collections.sort(lines);
		appendAll(out, lines);

		lines.clear();
		for (Trap trap : level.traps.valueList()){
			lines.add("trap " + trap.pos + " " + trap.getClass().getName() + (trap.visible ? " visible" : "") + (trap.active ? "" : " inactive"));
		}
		for (Plant plant : level.plants.valueList()){
			lines.add("plant " + plant.pos + " " + plant.getClass().getName());
		}
		for (Blob blob : level.blobs.values()){
			lines.add("blob " + blob.getClass().getName() + " volume=" + blob.volume);
		}
		Collections.sort(lines);
		appendAll(out, lines);
	}

	private static void appendAll( StringBuilder out, ArrayList<String> lines ){
		for (String l : lines){
			out.append(l).append('\n');
		}
	}

}
