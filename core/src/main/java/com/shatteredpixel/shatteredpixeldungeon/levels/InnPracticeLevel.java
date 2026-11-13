// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.levels;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnCompanion;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.InnPracticeTarget;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.SparseArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** An isolated training room using the original dungeon scene, controls and actions. */
public final class InnPracticeLevel extends Level {
    private static final String[] PLAN = {
        "#################",
        "#...............#",
        "#...#.......#...#",
        "#...#.......#...#",
        "#...#.......#...#",
        "#...###...###...#",
        "#...............#",
        "#...............#",
        "#...............#",
        "#...............#",
        "#################"
    };

    @Override
    public void create() {
        // A practice floor has neither campaign generation rewards nor randomized item spawns.
        TargetedCell.cells.clear();
        viewDistance = 9;
        transitions = new ArrayList<>();
        mobs = new HashSet<>();
        heaps = new SparseArray<>();
        blobs = new HashMap<>();
        plants = new SparseArray<>();
        traps = new SparseArray<>();
        customTiles = new ArrayList<>();
        customTerrain = new ArrayList<>();
        customWalls = new ArrayList<>();
        build();
        buildFlagMaps();
        cleanWalls();
        createMobs();
    }

    @Override
    protected boolean build() {
        setSize(PLAN[0].length(), PLAN.length);
        for (int y = 0; y < height(); y++) {
            for (int x = 0; x < width(); x++) {
                map[cell(x, y)] = PLAN[y].charAt(x) == '#' ? Terrain.WALL : Terrain.EMPTY;
            }
        }
        int entrance = cell(8, 8);
        transitions.add(new LevelTransition(this, entrance, LevelTransition.Type.REGULAR_ENTRANCE));
        return true;
    }

    @Override
    protected void createMobs() {
        InnCompanion.ensurePresent(this, entrance());
        addTarget(new InnPracticeTarget(), 8, 4);
        addTarget(new InnPracticeTarget.Rations(), 5, 7);
        addTarget(new InnPracticeTarget.Recovery(), 11, 7);
    }

    private void addTarget(Mob mob, int x, int y) {
        mob.pos = cell(x, y);
        mobs.add(mob);
    }

    private int cell(int x, int y) {
        return x + y * width();
    }

    @Override protected void createItems() {}
    @Override public Mob createMob() { return null; }
    @Override public Actor addRespawner() { return null; }
    @Override public String tilesTex() { return Assets.Environment.TILES_SEWERS; }
    @Override public String waterTex() { return Assets.Environment.WATER_SEWERS; }

    @Override
    public boolean activateTransition(Hero hero, LevelTransition transition) {
        return false;
    }

    @Override
    public int randomRespawnCell(Char ch) {
        return entrance();
    }

    public static String hint() {
        return Messages.get(InnPracticeLevel.class, "hint");
    }
}
