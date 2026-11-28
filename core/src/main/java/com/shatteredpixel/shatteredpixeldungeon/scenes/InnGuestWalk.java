// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.watabou.utils.PathFinder;

/**
 * Guest screen: the cells a figure walks through to show a move between two host snapshots. Snapshots can be
 * several cells apart (the host moves a cell every 0.1 s and sends at most every 0.1 s, and a late snapshot
 * replaces the one before it), so the guest fills the gap with the shortest walk instead of jumping.
 */
final class InnGuestWalk {
    private InnGuestWalk() {}

    /**
     * Steps from {@code from} to {@code to}, without the start and with the destination; empty when the cells
     * are the same, or {@code null} when there is no walk of at most {@code max} steps through passable cells.
     * {@link PathFinder} must be set to the level size.
     */
    static int[] steps(int from, int to, boolean[] passable, int width, int max) {
        if (from == to) return new int[0];
        if (Math.abs(from % width - to % width) <= 1 && Math.abs(from / width - to / width) <= 1) {
            return new int[]{to};
        }
        PathFinder.Path path = PathFinder.find(from, to, passable);
        if (path == null || path.isEmpty() || path.size() > max) return null;
        int[] result = new int[path.size()];
        int index = 0;
        for (int cell : path) result[index++] = cell;
        return result;
    }
}
