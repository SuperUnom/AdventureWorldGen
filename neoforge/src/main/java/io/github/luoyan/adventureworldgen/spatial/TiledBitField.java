package io.github.luoyan.adventureworldgen.spatial;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Sparse 256-block tiles; unknown is distinct from false, including at negative coordinates. */
public final class TiledBitField {
    private record Tile(BitSet known, BitSet allowed) {
        Tile() { this(new BitSet(4096), new BitSet(4096)); }
    }
    private final Map<Long, Tile> tiles = new HashMap<>();
    public boolean get(int x, int z, BooleanSupplier calculate) {
        long key = ((long)Math.floorDiv(x,256)<<32) | (Math.floorDiv(z,256)&0xffffffffL);
        Tile tile = tiles.computeIfAbsent(key, ignored -> new Tile());
        int index = Math.floorMod(z,256)/4*64 + Math.floorMod(x,256)/4;
        if (!tile.known.get(index)) {
            tile.allowed.set(index, calculate.getAsBoolean());
            tile.known.set(index);
        }
        return tile.allowed.get(index);
    }
}
