// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.badlogic.gdx.Input.Keys;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

final class KeyboardMovement {
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int UP = 4;
    private static final int DOWN = 8;
    private static final int HORIZONTAL = LEFT | RIGHT;
    private static final int VERTICAL = UP | DOWN;
    private static final long CHORD_MILLIS = 90;
    private static final long RELEASE_MILLIS = 45;

    private static final int[] CODES = {
        Keys.A, Keys.LEFT, Keys.NUMPAD_4, Keys.D, Keys.RIGHT, Keys.NUMPAD_6,
        Keys.W, Keys.UP, Keys.NUMPAD_8, Keys.S, Keys.DOWN, Keys.NUMPAD_2,
        Keys.NUMPAD_7, Keys.NUMPAD_9, Keys.NUMPAD_1, Keys.NUMPAD_3
    };
    private static final int[] DIRECTIONS = {
        LEFT, LEFT, LEFT, RIGHT, RIGHT, RIGHT, UP, UP, UP, DOWN, DOWN, DOWN,
        LEFT | UP, RIGHT | UP, LEFT | DOWN, RIGHT | DOWN
    };

    record Input(int dx, int dy, boolean fresh) {}

    private final boolean[] down = new boolean[CODES.length];
    private final LongSupplier clock;
    private int direction;
    private int releasedDirection;
    private int tap;
    private boolean composed;
    private boolean fresh;
    private boolean tapOnRelease;
    private long nextAt;
    private long releaseAt;

    KeyboardMovement() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    KeyboardMovement(LongSupplier clock) {
        this.clock = clock;
    }

    boolean onKey(int code, boolean pressed) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] != code) continue;
            if (down[i] != pressed) {
                down[i] = pressed;
                update(pressed, clock.getAsLong());
            }
            return true;
        }
        return false;
    }

    // Events preserve short taps. Sampling also covers keys unbound in the original game settings.
    void sample(IntPredicate pressed) {
        boolean changed = false;
        boolean pressedNew = false;
        for (int i = 0; i < CODES.length; i++) {
            boolean held = pressed.test(CODES[i]);
            if (held != down[i]) {
                down[i] = held;
                changed = true;
                pressedNew |= held;
            }
        }
        if (changed) update(pressedNew, clock.getAsLong());
    }

    boolean active() {
        return direction != 0 || tap != 0;
    }

    Input next(long repeatMillis) {
        long now = clock.getAsLong();
        finishRelease(now);
        if (direction != 0 && now >= nextAt && (releasedDirection == 0 || fresh)) {
            Input input = input(direction, fresh);
            fresh = false;
            tap = 0;
            nextAt = now + repeatMillis;
            return input;
        }
        if (direction == 0 && tap != 0) {
            Input input = input(tap, true);
            tap = 0;
            return input;
        }
        return null;
    }

    void reset() {
        Arrays.fill(down, false);
        tap = 0;
        select(0, 0, false, false);
    }

    private void update(boolean pressed, long now) {
        finishRelease(now);
        int held = 0;
        int dedicated = 0;
        for (int i = 0; i < down.length; i++) {
            if (!down[i]) continue;
            held |= DIRECTIONS[i];
            if (diagonal(DIRECTIONS[i])) dedicated |= 1 << DIRECTIONS[i];
        }

        int horizontal = held & HORIZONTAL;
        int vertical = held & VERTICAL;
        boolean opposed = horizontal == HORIZONTAL || vertical == VERTICAL;
        int next = (horizontal == HORIZONTAL ? 0 : horizontal) | (vertical == VERTICAL ? 0 : vertical);
        boolean nextComposed = diagonal(next) && (dedicated & (1 << next)) == 0;
        if (next == direction && next != 0) {
            releasedDirection = 0;
            composed = nextComposed;
            return;
        }
        if (next == 0) {
            if (opposed) tap = 0;
            else if (fresh && tapOnRelease) tap = direction;
            select(0, now, false, false);
            return;
        }
        if (releasedDirection == next && !opposed) return;
        if (!pressed && !opposed && composed && (direction & next) == next && !diagonal(next)) {
            releasedDirection = next;
            releaseAt = now + RELEASE_MILLIS;
            return;
        }
        select(next, now + (diagonal(next) ? 0 : CHORD_MILLIS), nextComposed, pressed && !opposed);
    }

    private void finishRelease(long now) {
        if (releasedDirection != 0 && now >= releaseAt) select(releasedDirection, now, false, false);
    }

    private void select(int direction, long nextAt, boolean composed, boolean tapOnRelease) {
        this.direction = direction;
        this.nextAt = nextAt;
        this.composed = composed;
        this.tapOnRelease = tapOnRelease;
        fresh = direction != 0;
        releasedDirection = 0;
    }

    private static boolean diagonal(int direction) {
        return (direction & HORIZONTAL) != 0 && (direction & VERTICAL) != 0;
    }

    private static Input input(int direction, boolean fresh) {
        int dx = (direction & LEFT) != 0 ? -1 : (direction & RIGHT) != 0 ? 1 : 0;
        int dy = (direction & UP) != 0 ? -1 : (direction & DOWN) != 0 ? 1 : 0;
        return new Input(dx, dy, fresh);
    }
}
