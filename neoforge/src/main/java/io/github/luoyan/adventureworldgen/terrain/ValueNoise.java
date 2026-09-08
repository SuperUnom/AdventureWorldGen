package io.github.luoyan.adventureworldgen.terrain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Strict, world-aligned two-dimensional seeded value noise. */
public final class ValueNoise {
    private final long key;
    private final double scale;

    public ValueNoise(long worldSeed, String fieldId, double scale) {
        if (!(scale > 0.0) || !Double.isFinite(scale)) throw new IllegalArgumentException("scale must be finite and positive");
        this.scale = scale;
        this.key = initialKey(worldSeed, fieldId);
    }

    public double sample(double x, double z) {
        double gx = x / scale, gz = z / scale;
        long x0 = fastFloor(gx), z0 = fastFloor(gz);
        double tx = smooth(gx - x0), tz = smooth(gz - z0);
        double a = lerp(lattice(x0, z0), lattice(x0 + 1, z0), tx);
        double b = lerp(lattice(x0, z0 + 1), lattice(x0 + 1, z0 + 1), tx);
        return lerp(a, b, tz);
    }

    private double lattice(long x, long z) {
        long mixed = mix64(key ^ mix64(x + 0x9E3779B97F4A7C15L) ^ Long.rotateLeft(mix64(z), 29));
        return ((mixed >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static long initialKey(long worldSeed, String fieldId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(worldSeed).array());
            digest.update(fieldId.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static long fastFloor(double value) {
        long integer = (long) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
