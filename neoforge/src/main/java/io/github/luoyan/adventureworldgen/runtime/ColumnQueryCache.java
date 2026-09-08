package io.github.luoyan.adventureworldgen.runtime;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;

/** Bounded, lock-free memoization for immutable horizontal queries. Collisions only evict values. */
public final class ColumnQueryCache<T> {
    private record Entry<T>(long key, T value) {}
    private final AtomicReferenceArray<Entry<T>> entries;

    public ColumnQueryCache(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a positive power of two");
        entries = new AtomicReferenceArray<>(capacity);
    }

    public T get(int x, int z, BiFunction<Integer, Integer, T> query) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        long hash = key;
        hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
        int slot = (int) (hash ^ (hash >>> 31)) & (entries.length() - 1);
        Entry<T> entry = entries.get(slot);
        if (entry != null && entry.key == key) return entry.value;
        T value = query.apply(x, z);
        // Duplicate computation during concurrent misses is safe; no terrain work runs under a lock.
        entries.set(slot, new Entry<>(key, value));
        return value;
    }
}
