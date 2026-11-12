// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class InnMotion {
    record Cell(int x, int y) {
        int distanceTo(Cell other) {
            return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
        }
    }

    record Pose(double x, double y, int frame, boolean facingLeft, double drinkSeconds) {}

    private static final long STEP = TimeUnit.MILLISECONDS.toNanos(142);
    private static final long FAST_STEP = TimeUnit.MILLISECONDS.toNanos(80);
    private static final long CATCH_UP = TimeUnit.MILLISECONDS.toNanos(560);
    private static final long RUN_FRAME = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long DRINK_FRAME = TimeUnit.MILLISECONDS.toNanos(125);
    private static final long DRINK_GESTURE = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long KEEP_STRIDE = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    // HeroSprite uses the first two frames for idle, 2–7 for running and 16–17 for operating.
    private static final int[] IDLE_FRAMES = {0, 0, 0, 1, 0, 0, 1, 1};
    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, -1}, {0, 1}, {1, -1}, {-1, -1}, {1, 1}, {-1, 1}
    };

    private final ArrayDeque<Cell> route = new ArrayDeque<>();
    private final int idlePhase;
    private Cell cell;
    private long stepAt;
    private long stepDuration = STEP;
    private long followingStepDuration = STEP;
    private long walkAt;
    private long stoppedAt;
    private long drinkAt;
    private boolean facingLeft;
    private boolean walked;
    private boolean drank;

    InnMotion(Cell cell, int idlePhase) {
        this.cell = cell;
        this.idlePhase = Math.floorMod(idlePhase, IDLE_FRAMES.length);
    }

    Cell destination() {
        return route.isEmpty() ? cell : route.getLast();
    }

    void face(Cell other) {
        if (other.x() != cell.x()) facingLeft = other.x() < cell.x();
    }

    void drink(long now) {
        drinkAt = now;
        drank = true;
    }

    void moveTo(Cell target, InnApi.State state, long now) {
        advance(now);
        if (target.equals(destination())) return;

        Cell next = route.peekFirst();
        Cell start = next == null ? cell : next;
        List<Cell> remaining = path(start, target, state);
        route.clear();

        if (remaining.isEmpty() && !start.equals(target)) {
            // A disconnected path can happen when the server replaces the map.
            cell = target;
            stoppedAt = now;
            return;
        }

        if (next != null) route.addLast(next);
        route.addAll(remaining);
        followingStepDuration = Math.max(FAST_STEP, Math.min(STEP, CATCH_UP / route.size()));
        if (next == null) {
            stepAt = now;
            stepDuration = followingStepDuration;
            if (!walked || now - stoppedAt > KEEP_STRIDE) walkAt = now;
            walked = true;
            face(route.getFirst());
        }
    }

    Pose pose(long now) {
        advance(now);
        Cell next = route.peekFirst();
        long drinking = drank ? now - drinkAt : Long.MAX_VALUE;
        double drinkSeconds = drinking < 1_650_000_000L ? (double) drinking / SECOND : -1;
        if (next == null) {
            int frame = drinking < DRINK_GESTURE
                ? 16 + (int) (drinking / DRINK_FRAME) % 2
                : IDLE_FRAMES[(int) Math.floorMod(now / SECOND + idlePhase, IDLE_FRAMES.length)];
            return new Pose(cell.x(), cell.y(), frame, facingLeft, drinkSeconds);
        }
        double progress = (double) (now - stepAt) / stepDuration;
        double x = cell.x() + (next.x() - cell.x()) * progress;
        double y = cell.y() + (next.y() - cell.y()) * progress;
        int frame = 2 + (int) ((now - walkAt) / RUN_FRAME) % 6;
        return new Pose(x, y, frame, facingLeft, drinkSeconds);
    }

    private void advance(long now) {
        while (!route.isEmpty() && now - stepAt >= stepDuration) {
            cell = route.removeFirst();
            stepAt += stepDuration;
            stepDuration = followingStepDuration;
            if (route.isEmpty()) {
                stoppedAt = stepAt;
            } else {
                face(route.getFirst());
            }
        }
    }

    static boolean canStep(InnApi.State state, Cell from, Cell to) {
        return state.canStep(from.x(), from.y(), to.x(), to.y());
    }

    static List<Cell> path(Cell start, Cell goal, InnApi.State state) {
        if (start.equals(goal) || !state.walkable(goal.x(), goal.y())) return List.of();
        if (canStep(state, start, goal)) return List.of(goal);

        ArrayDeque<Cell> open = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        open.add(start);
        previous.put(start, start);
        while (!open.isEmpty()) {
            Cell from = open.removeFirst();
            for (int[] direction : DIRECTIONS) {
                Cell next = new Cell(from.x() + direction[0], from.y() + direction[1]);
                if (!canStep(state, from, next) || previous.putIfAbsent(next, from) != null) continue;
                if (next.equals(goal)) {
                    List<Cell> path = new ArrayList<>();
                    for (Cell step = goal; !step.equals(start); step = previous.get(step)) path.add(step);
                    return path.reversed();
                }
                open.addLast(next);
            }
        }
        return List.of();
    }
}
