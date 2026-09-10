package io.github.luoyan.adventureworldgen.plan;


/** Canonical identifiers used by search state and persisted plans. */
public final class StableIds {
    private StableIds() {
    }

    public static String requiredRequest(long inputIndex) {
        requireNonNegative(inputIndex, "inputIndex");
        return "required/" + inputIndex;
    }

    public static String requiredPatch(long inputIndex) {
        return "patch/" + requiredRequest(inputIndex);
    }

    public static String structureInstance(ContentId structureId, long sequence) {
        requireNonNegative(sequence, "sequence");
        return "instance/" + structureId + "/" + sequence;
    }

    public static String carrierPatch(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        return "patch/carrier/" + instanceId;
    }

    public static String implicitSpawnPatch() {
        return "patch/spawn";
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
