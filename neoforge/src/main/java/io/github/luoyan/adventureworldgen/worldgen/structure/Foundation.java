package io.github.luoyan.adventureworldgen.worldgen.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Immutable support area; surface is the first free block above its foundation. */
public record Foundation(int minX, int minZ, int maxX, int maxZ, int surface) {
    public static final Codec<Foundation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("min_x").forGetter(Foundation::minX),
            Codec.INT.fieldOf("min_z").forGetter(Foundation::minZ),
            Codec.INT.fieldOf("max_x").forGetter(Foundation::maxX),
            Codec.INT.fieldOf("max_z").forGetter(Foundation::maxZ),
            Codec.INT.fieldOf("surface").forGetter(Foundation::surface)
    ).apply(i, Foundation::new));
    public Foundation {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("inverted support area");
    }
    public BoundingBox influence(int margin) {
        return new BoundingBox(minX - margin, surface - 1, minZ - margin,
                maxX + margin, surface, maxZ + margin);
    }
    public double weight(int x, int z, int margin) {
        double dx = Math.max(0, Math.max((double) minX - x, (double) x - maxX));
        double dz = Math.max(0, Math.max((double) minZ - z, (double) z - maxZ));
        if (dx == 0 && dz == 0) return 1;
        if (margin == 0) return 0;
        double t = Math.max(0, 1 - Math.hypot(dx, dz) / margin);
        return t * t * (3 - 2 * t);
    }
}
