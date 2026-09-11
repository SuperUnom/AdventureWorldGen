package io.github.luoyan.adventureworldgen.climate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lazy quart cache: negative coordinates stay exact and each value is computed once. */
class FrozenQuartFieldTest {
    @Test void quartCachePreservesNegativeCoordinatesAndComputesEachFrozenValueOnce() {
        var field = new FrozenQuartField(512);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int repeat = 0; repeat < 3; repeat++) for (int x : new int[]{-130, -2, 2, 130})
            assertEquals(x, field.get(x, 2, () -> { calls.incrementAndGet(); return x; }));
        assertEquals(4, calls.get());
        assertEquals(1.5, field.get(1.5, 2, () -> 1.5));
    }

    @Test void onlyQuartCentersInsideTheExtentAreCacheable() {
        var field = new FrozenQuartField(128);
        // Quart centers are 4k + 2, including negatives.
        for (int x : new int[]{-126, -62, -2, 2, 62, 126}) assertTrue(field.cacheable(x, 2), "x=" + x);
        // Cell boundaries, off-grid positions and anything outside the extent are computed exactly.
        for (int x : new int[]{-128, -64, 0, 4, 3, 1, 130, -130}) assertFalse(field.cacheable(x, 2), "x=" + x);
        assertFalse(field.cacheable(2, 3), "z off the quart grid");
    }

    @Test void anOffGridQueryNeverSeedsACell() {
        var field = new FrozenQuartField(64);
        assertEquals(1.5, field.get(1.5, 2, () -> 1.5));
        // The neighbouring center is untouched by that query, so it still computes its own value.
        assertEquals(9.0, field.get(2, 2, () -> 9.0));
        assertEquals(9.0, field.get(2, 2, () -> { throw new AssertionError("cell recomputed"); }));
    }
}
