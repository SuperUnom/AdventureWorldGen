package io.github.luoyan.adventureworldgen.spatial;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnQueryCacheTest {
    @Test void repeatedQueriesReuseValuesAndCollisionsDoNotChangeCoordinates() {
        var cache = new ColumnQueryCache<String>(1);
        var calls = new AtomicInteger();
        java.util.function.BiFunction<Integer, Integer, String> query = (x, z) -> {
            calls.incrementAndGet();
            return x + ":" + z;
        };
        assertEquals("-17:32", cache.get(-17, 32, query));
        assertEquals("-17:32", cache.get(-17, 32, query));
        assertEquals(1, calls.get());
        assertEquals("32:-17", cache.get(32, -17, query));
        assertEquals("-17:32", cache.get(-17, 32, query));
        assertEquals(3, calls.get());
    }

    @Test void parallelEvictionNeverReturnsAnotherColumnsValue() {
        var cache = new ColumnQueryCache<Long>(16);
        IntStream.range(0, 10000).parallel().forEach(i -> {
            int x = i - 5000, z = -i;
            long expected = ((long) x << 32) ^ (z & 0xffffffffL);
            assertEquals(expected, cache.get(x, z, (cx, cz) -> ((long) cx << 32) ^ (cz & 0xffffffffL)));
        });
    }
}
