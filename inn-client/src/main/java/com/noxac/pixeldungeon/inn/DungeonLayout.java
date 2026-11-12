// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record DungeonLayout(String theme, List<String> map, Cell host, Cell partner, Cell enemy1, Cell enemy2) {
    static final String SEWERS = "sewers";
    static final String PRISON = "prison";
    private static final int COLUMNS = 3;
    private static final int ROWS = 2;
    private static final int BAY_SIZE = 6;
    private static final DungeonLayout SEWER_LAYOUT = new DungeonLayout(SEWERS, List.of(
        "#############",
        "#...........#",
        "#.....#.....#",
        "#.....#.....#",
        "#...........#",
        "#..##.......#",
        "#...........#",
        "#.....#.....#",
        "#############"
    ), new Cell(2, 2), new Cell(3, 2), new Cell(9, 2), new Cell(10, 6));

    record Cell(int x, int y) {}

    static String themeOrDefault(String theme) {
        return PRISON.equals(theme) ? PRISON : SEWERS;
    }

    static DungeonLayout create(String theme, long seed) {
        return PRISON.equals(themeOrDefault(theme)) ? prison(seed) : SEWER_LAYOUT;
    }

    boolean walkable(int x, int y) {
        return y >= 0 && y < map.size() && x >= 0 && x < map.get(y).length() && map.get(y).charAt(x) == '.';
    }

    private static DungeonLayout prison(long seed) {
        LayoutRandom random = new LayoutRandom(seed);
        char[][] grid = new char[ROWS * BAY_SIZE + 1][COLUMNS * BAY_SIZE + 1];
        for (char[] row : grid) Arrays.fill(row, '#');

        // As in RegularLevel, place the rooms before carving the LoopBuilder-style route and its branches.
        Cell[] centers = new Cell[COLUMNS * ROWS];
        for (int index = 0; index < centers.length; index++) {
            int width = 3 + random.next(3);
            int height = 3 + random.next(3);
            int x = 1 + index % COLUMNS * BAY_SIZE + random.next(BAY_SIZE - width);
            int y = 1 + index / COLUMNS * BAY_SIZE + random.next(BAY_SIZE - height);
            for (int row = y; row < y + height; row++) horizontal(grid, row, x, x + width - 1);
            centers[index] = new Cell(x + width / 2, y + height / 2);
        }

        int loopColumn = random.next(COLUMNS - 1);
        int[] loop = {loopColumn, loopColumn + 1, COLUMNS + loopColumn + 1, COLUMNS + loopColumn};
        for (int i = 0; i < loop.length; i++) connect(grid, centers, loop[i], loop[(i + 1) % loop.length]);

        int branchColumn = loopColumn == 0 ? COLUMNS - 1 : 0;
        int branch = random.next(3);
        if (branch != 2) connect(grid, centers, 1, branchColumn);
        if (branch != 1) connect(grid, centers, COLUMNS + 1, COLUMNS + branchColumn);
        if (branch != 0) connect(grid, centers, branchColumn, COLUMNS + branchColumn);

        Cell entrance = centers[random.next(ROWS) * COLUMNS];
        List<String> rows = new ArrayList<>(grid.length);
        for (char[] row : grid) rows.add(new String(row));
        return new DungeonLayout(PRISON, List.copyOf(rows), new Cell(entrance.x - 1, entrance.y), entrance,
            centers[COLUMNS - 1], centers[centers.length - 1]);
    }

    private static void connect(char[][] grid, Cell[] centers, int from, int to) {
        Cell a = centers[from];
        Cell b = centers[to];
        if (from / COLUMNS == to / COLUMNS) {
            int divider = Math.max(from % COLUMNS, to % COLUMNS) * BAY_SIZE;
            horizontal(grid, a.y, a.x, divider);
            vertical(grid, divider, a.y, b.y);
            horizontal(grid, b.y, divider, b.x);
        } else {
            int divider = Math.max(from / COLUMNS, to / COLUMNS) * BAY_SIZE;
            vertical(grid, a.x, a.y, divider);
            horizontal(grid, divider, a.x, b.x);
            vertical(grid, b.x, divider, b.y);
        }
    }

    private static void horizontal(char[][] grid, int y, int from, int to) {
        Arrays.fill(grid[y], Math.min(from, to), Math.max(from, to) + 1, '.');
    }

    private static void vertical(char[][] grid, int x, int from, int to) {
        for (int y = Math.min(from, to); y <= Math.max(from, to); y++) grid[y][x] = '.';
    }

    // Keep the UTF-16 seed hash and xorshift operations in sync with worker/play/layout.mjs.
    private static final class LayoutRandom {
        private int state = 0x811c9dc5;

        LayoutRandom(long seed) {
            String text = Long.toString(seed);
            for (int i = 0; i < text.length(); i++) state = (state ^ text.charAt(i)) * 0x01000193;
            if (state == 0) state = 0x6d2b79f5;
        }

        int next(int bound) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            return (state >>> 1) % bound;
        }
    }
}
