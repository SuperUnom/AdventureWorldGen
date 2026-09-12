package io.github.luoyan.adventureworldgen.spatial;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;

/** Immutable sparse ownership on the final world-aligned quart grid. */
public final class CellMask {
    private final long[] cells;
    public CellMask(long[] cells) {
        this.cells = cells.clone();
        Arrays.sort(this.cells);
        for (int i = 1; i < cells.length; i++)
            if (this.cells[i] == this.cells[i - 1]) throw new IllegalArgumentException("duplicate ownership cell");
        if (cells.length == 0) throw new IllegalArgumentException("empty ownership mask");
    }
    public CellMask(Collection<Long> cells) { this(cells.stream().mapToLong(Long::longValue).toArray()); }
    public static long key(int blockX, int blockZ) {
        return ((long) Math.floorDiv(blockX, 4) << 32) ^ (Math.floorDiv(blockZ, 4) & 0xffffffffL);
    }
    public static int x(long cell) { return Math.toIntExact((cell >> 32) * 4); }
    public static int z(long cell) { return Math.toIntExact((long) (int) cell * 4); }
    public boolean contains(int x, int z) { return Arrays.binarySearch(cells, key(x,z)) >= 0; }
    public int size() { return cells.length; }
    public long[] cells() { return cells.clone(); }
    public String encode() {
        var bytes = ByteBuffer.allocate(Math.multiplyExact(cells.length, Long.BYTES));
        for (long cell : cells) bytes.putLong(cell);
        return Base64.getEncoder().encodeToString(bytes.array());
    }
    public static CellMask decode(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length % Long.BYTES != 0 || bytes.length > 64 * 1024 * 1024)
            throw new IllegalArgumentException("invalid ownership byte count");
        var input = ByteBuffer.wrap(bytes);
        long[] cells = new long[bytes.length / Long.BYTES];
        for (int i = 0; i < cells.length; i++) cells[i] = input.getLong();
        return new CellMask(cells);
    }
    @Override public boolean equals(Object other) { return other instanceof CellMask mask && Arrays.equals(cells, mask.cells); }
    @Override public int hashCode() { return Arrays.hashCode(cells); }
    @Override public String toString() { return "CellMask[cells=" + cells.length + "]"; }
}
