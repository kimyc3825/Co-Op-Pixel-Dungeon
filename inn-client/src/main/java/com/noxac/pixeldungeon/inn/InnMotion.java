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

    static final long LOCAL_FRAME_SLACK = TimeUnit.MILLISECONDS.toNanos(17);
    private static final long STEP = TimeUnit.MILLISECONDS.toNanos(142);
    private static final long LOCAL_STEP = LobbySession.MOVE_INTERVAL + LOCAL_FRAME_SLACK;
    private static final long CORRECTION_STEP = TimeUnit.MILLISECONDS.toNanos(70);
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
    private double startX;
    private double startY;
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
        startX = cell.x();
        startY = cell.y();
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
        moveTo(target, state, now, STEP);
    }

    void moveLocalTo(Cell target, InnApi.State state, long now) {
        moveTo(target, state, now, LOCAL_STEP);
    }

    private void moveTo(Cell target, InnApi.State state, long now, long maxStep) {
        advance(now);
        if (target.equals(destination())) return;

        Cell next = route.peekFirst();
        Cell start = next == null ? cell : next;
        List<Cell> remaining = path(start, target, state);
        route.clear();

        if (remaining.isEmpty() && !start.equals(target)) {
            // A disconnected path can happen when the server replaces the map.
            settleAt(target, now);
            return;
        }

        if (next != null) route.addLast(next);
        route.addAll(remaining);
        // Once the next prediction is available, playback can continue at the input cadence.
        long cadence = maxStep == LOCAL_STEP && next != null ? LobbySession.MOVE_INTERVAL : maxStep;
        followingStepDuration = Math.max(FAST_STEP, Math.min(cadence, CATCH_UP / route.size()));
        if (next == null) {
            stepAt = now;
            stepDuration = followingStepDuration;
            if (!walked || now - stoppedAt > KEEP_STRIDE) walkAt = now;
            walked = true;
            faceNext();
        }
    }

    void correctTo(Cell target, InnApi.State state, long now) {
        Pose current = pose(now);
        if (target.equals(destination())) return;
        route.clear();
        if (current.x() != cell.x() || current.y() != cell.y()) route.addLast(cell);
        List<Cell> remaining = path(cell, target, state);
        if (remaining.isEmpty() && !cell.equals(target)) {
            settleAt(target, now);
            return;
        }
        route.addAll(remaining);
        if (route.isEmpty()) return;
        startX = current.x();
        startY = current.y();
        stepAt = now;
        stepDuration = followingStepDuration = CORRECTION_STEP;
        faceNext();
    }

    private void settleAt(Cell target, long now) {
        cell = target;
        startX = cell.x();
        startY = cell.y();
        stoppedAt = now;
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
        double x = startX + (next.x() - startX) * progress;
        double y = startY + (next.y() - startY) * progress;
        int frame = 2 + (int) ((now - walkAt) / RUN_FRAME) % 6;
        return new Pose(x, y, frame, facingLeft, drinkSeconds);
    }

    private void advance(long now) {
        while (!route.isEmpty() && now - stepAt >= stepDuration) {
            cell = route.removeFirst();
            startX = cell.x();
            startY = cell.y();
            stepAt += stepDuration;
            stepDuration = followingStepDuration;
            if (route.isEmpty()) {
                stoppedAt = stepAt;
            } else {
                faceNext();
            }
        }
    }

    private void faceNext() {
        int x = route.getFirst().x();
        if (x != startX) facingLeft = x < startX;
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
