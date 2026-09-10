package io.github.luoyan.adventureworldgen.noise;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stateless SHA-256 based samples defined by the planner-v2 determinism contract. */
public final class DeterministicRandom {
    private DeterministicRandom() {
    }

    public static byte[] digest(long worldSeed, String algorithmVersion, String stage, String stableId, long operationIndex) {
        MessageDigest digest = sha256();
        addField(digest, Long.toString(worldSeed));
        addField(digest, algorithmVersion);
        addField(digest, stage);
        addField(digest, stableId);
        addField(digest, Long.toString(operationIndex));
        return digest.digest();
    }

    public static double sample(long worldSeed, String algorithmVersion, String stage, String stableId, long operationIndex) {
        byte[] digest = digest(worldSeed, algorithmVersion, stage, stableId, operationIndex);
        long firstEightBytes = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        long top53Bits = firstEightBytes >>> 11;
        return top53Bits * 0x1.0p-53;
    }

    public static long seed(long worldSeed, String algorithmVersion, String stage, String stableId, long operationIndex) {
        return ByteBuffer.wrap(digest(worldSeed, algorithmVersion, stage, stableId, operationIndex), 0, Long.BYTES).getLong();
    }

    /**
     * Stateless 64-bit mixer (SplitMix64 finalizer) over an already-derived key.
     *
     * <p>Used where a cheap deterministic ordering or jitter is needed from a seed and a stable
     * cell/id value rather than a fresh SHA-256 draw. It is a determinism contract: changing the
     * constants or shifts changes every seeded ordering that uses it.
     */
    public static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static void addField(MessageDigest digest, String value) {
        if (value == null) {
            throw new IllegalArgumentException("random key fields must not be null");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
        digest.update(encoded);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
