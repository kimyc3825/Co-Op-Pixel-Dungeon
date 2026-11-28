// SPDX-License-Identifier: GPL-3.0-or-later
package com.shatteredpixel.shatteredpixeldungeon.scenes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.watabou.utils.PathFinder;

import org.junit.Before;
import org.junit.Test;

public class InnGuestWalkTest {

	private static final int WIDTH = 7;
	private static final int HEIGHT = 5;
	private boolean[] passable;

	@Before
	public void openRoom() {
		PathFinder.setMapSize(WIDTH, HEIGHT);
		passable = new boolean[WIDTH * HEIGHT];
		// A wall border around a 5x3 floor.
		for (int y = 1; y < HEIGHT - 1; y++) {
			for (int x = 1; x < WIDTH - 1; x++) passable[cell(x, y)] = true;
		}
	}

	private static int cell(int x, int y) {
		return x + y * WIDTH;
	}

	@Test
	public void sameCellNeedsNoStep() {
		assertEquals(0, InnGuestWalk.steps(cell(2, 2), cell(2, 2), passable, WIDTH, 8).length);
	}

	@Test
	public void neighbourIsOneStep() {
		assertArrayEquals(new int[]{cell(3, 3)}, InnGuestWalk.steps(cell(2, 2), cell(3, 3), passable, WIDTH, 8));
	}

	@Test
	public void gapIsWalkedCellByCellEndingOnTheDestination() {
		int[] steps = InnGuestWalk.steps(cell(1, 2), cell(5, 2), passable, WIDTH, 8);
		assertArrayEquals(new int[]{cell(2, 2), cell(3, 2), cell(4, 2), cell(5, 2)}, steps);
	}

	@Test
	public void walkGoesAroundWalls() {
		passable[cell(3, 1)] = false;
		passable[cell(3, 2)] = false;
		int[] steps = InnGuestWalk.steps(cell(2, 1), cell(4, 1), passable, WIDTH, 8);
		assertEquals(cell(4, 1), steps[steps.length - 1]);
		int from = cell(2, 1);
		for (int step : steps) {
			assertEquals("steps must be neighbours", true, adjacent(from, step));
			assertEquals("steps must be on floor", true, passable[step]);
			from = step;
		}
	}

	@Test
	public void tooLongOrBlockedWalkIsAJump() {
		assertNull(InnGuestWalk.steps(cell(1, 2), cell(5, 2), passable, WIDTH, 3));
		for (int y = 1; y < HEIGHT - 1; y++) passable[cell(3, y)] = false;
		assertNull(InnGuestWalk.steps(cell(1, 2), cell(5, 2), passable, WIDTH, 8));
	}

	private static boolean adjacent(int a, int b) {
		return Math.abs(a % WIDTH - b % WIDTH) <= 1 && Math.abs(a / WIDTH - b / WIDTH) <= 1;
	}
}
