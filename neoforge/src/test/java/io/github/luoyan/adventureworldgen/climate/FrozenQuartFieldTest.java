package io.github.luoyan.adventureworldgen.climate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
