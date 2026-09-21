package io.github.luoyan.adventureworldgen.spatial;

import java.util.Random;

/** First vanilla large-feature random draw. Shared by template metadata and execution. */
public final class TemplateRotation {
    private TemplateRotation() {}
    public static int index(long seed,int chunkX,int chunkZ) {
        var random=new Random(seed);
        long x=random.nextLong(),z=random.nextLong();
        random.setSeed((long)chunkX*x ^ (long)chunkZ*z ^ seed);
        return random.nextInt(4);
    }
}
