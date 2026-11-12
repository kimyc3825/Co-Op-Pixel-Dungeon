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

/*
 * WEB-PORT: shadows org.teavm.classlib.java.util.TRandom (TeaVM's java.util.Random) from teavm-classlib 0.15.0.
 *
 * TeaVM's version has the same 48-bit LCG, but nextInt(bound) and nextBoolean() fall back to the generic
 * RandomGenerator defaults, which consume and map random bits differently from java.util.Random.
 * Shattered's com.watabou.utils.Random wraps java.util.Random, so with TeaVM's version every seed
 * produced different dungeons than on desktop/Android. This implementation follows the algorithms that
 * the java.util.Random specification (its javadoc) mandates for every method, so a seeded generator
 * returns exactly the same sequence as on the JVM.
 */
package org.teavm.classlib.java.util;

import org.teavm.classlib.java.io.TSerializable;
import org.teavm.classlib.java.lang.TObject;
import org.teavm.classlib.java.util.random.TRandomGenerator;

public class TRandom extends TObject implements TRandomGenerator, TSerializable {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;
    private static final float FLOAT_UNIT = 1.0f / (1 << 24);
    private static final double DOUBLE_UNIT = 1.0 / (1L << 53);

    private long seed;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public TRandom() {
        //unseeded generators only need to be unpredictable, not reproducible
        this((long) (Math.random() * 0x1p48) ^ (long) (Math.random() * 0x1p16) << 32);
    }

    public TRandom(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
        haveNextNextGaussian = false;
    }

    protected int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    @Override
    public void nextBytes(byte[] bytes) {
        for (int i = 0, len = bytes.length; i < len;) {
            for (int rnd = nextInt(), n = Math.min(len - i, 4); n-- > 0; rnd >>= 8) {
                bytes[i++] = (byte) rnd;
            }
        }
    }

    @Override
    public int nextInt() {
        return next(32);
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0) {
            //bound is a power of 2
            r = (int) ((bound * (long) r) >> 31);
        } else {
            //rejects values from the incomplete last range to avoid bias
            for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) {
                //retry
            }
        }
        return r;
    }

    @Override
    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    @Override
    public boolean nextBoolean() {
        return next(1) != 0;
    }

    @Override
    public float nextFloat() {
        return next(24) * FLOAT_UNIT;
    }

    @Override
    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * DOUBLE_UNIT;
    }

    @Override
    public double nextGaussian() {
        //polar method of G. E. P. Box, M. E. Muller and G. Marsaglia, as specified by java.util.Random
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double v1;
        double v2;
        double s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1 || s == 0);
        double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
        nextNextGaussian = v2 * multiplier;
        haveNextNextGaussian = true;
        return v1 * multiplier;
    }
}
