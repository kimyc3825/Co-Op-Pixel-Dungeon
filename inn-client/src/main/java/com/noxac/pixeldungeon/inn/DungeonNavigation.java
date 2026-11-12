// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.noxac.pixeldungeon.inn.DungeonLayout.Cell;

/** Finds the next legal ATB step for a clicked tile or a melee target. */
final class DungeonNavigation {
    private static final List<Cell> DIRECTIONS = List.of(
        new Cell(1, 0), new Cell(-1, 0), new Cell(0, -1), new Cell(0, 1),
        new Cell(1, -1), new Cell(-1, -1), new Cell(1, 1), new Cell(-1, 1)
    );

    private record Route(Cell tile, Cell firstStep) {}

    private DungeonNavigation() {}

    static Cell next(InnApi.DungeonState state, InnApi.Combatant self, int x, int y, boolean adjacent) {
        List<String> map = state.map();
        if (!floor(map, x, y)) return null;
        Cell target = new Cell(x, y);
        Route route = find(state, self, adjacent ? from -> inReach(map, from, target) : target::equals);
        return route == null ? null : route.firstStep();
    }

    static Cell nearestLoot(InnApi.DungeonState state, InnApi.Combatant self) {
        Set<Cell> unclaimed = new HashSet<>();
        for (InnApi.Drop drop : state.drops()) {
            if (drop.pickedBy() == null) unclaimed.add(new Cell(drop.x(), drop.y()));
        }
        if (unclaimed.isEmpty()) return null;
        Route route = find(state, self, unclaimed::contains);
        return route == null ? null : route.tile();
    }

    private static Route find(InnApi.DungeonState state, InnApi.Combatant self, Predicate<Cell> goal) {
        List<String> map = state.map();
        Set<Cell> occupied = new HashSet<>();
        for (InnApi.Combatant actor : state.actors()) {
            if (actor.hp() > 0 && !actor.id().equals(self.id())) occupied.add(new Cell(actor.x(), actor.y()));
        }
        Cell start = new Cell(self.x(), self.y());
        ArrayDeque<Route> queue = new ArrayDeque<>();
        Set<Cell> seen = new HashSet<>(occupied);
        seen.add(start);
        queue.add(new Route(start, null));
        while (!queue.isEmpty()) {
            Route route = queue.removeFirst();
            Cell from = route.tile();
            if (goal.test(from)) return route;
            for (Cell step : DIRECTIONS) {
                Cell to = new Cell(from.x() + step.x(), from.y() + step.y());
                if (!floor(map, to.x(), to.y()) || seen.contains(to)) continue;
                if (step.x() != 0 && step.y() != 0 && !floor(map, to.x(), from.y()) && !floor(map, from.x(), to.y())) {
                    continue;
                }
                seen.add(to);
                queue.add(new Route(to, route.firstStep() == null ? step : route.firstStep()));
            }
        }
        return null;
    }

    private static boolean inReach(List<String> map, Cell from, Cell to) {
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        return Math.max(dx, dy) == 1 && (dx == 0 || dy == 0 ||
            floor(map, to.x(), from.y()) && floor(map, from.x(), to.y()));
    }

    private static boolean floor(List<String> map, int x, int y) {
        return y >= 0 && y < map.size() && x >= 0 && x < map.get(y).length() && map.get(y).charAt(x) == '.';
    }
}
