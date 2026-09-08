package io.github.luoyan.adventureworldgen.worldgen;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Surface-only context: native rules see the composed terrain, not an unrelated noise world. */
final class PlannedSurfaceNoiseChunk extends NoiseChunk {
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES = new DensityFunctions.BeardifierOrMarker() {
        @Override public double compute(DensityFunction.FunctionContext context) { return 0; }
        @Override public void fillArray(double[] values, DensityFunction.ContextProvider context) { Arrays.fill(values, 0); }
        @Override public double minValue() { return 0; }
        @Override public double maxValue() { return 0; }
    };
    private final IntBinaryOperator height;
    private final Map<Long, Integer> heights = new HashMap<>();

    PlannedSurfaceNoiseChunk(ChunkAccess chunk, RandomState random, NoiseGeneratorSettings settings,
                             IntBinaryOperator height) {
        super(16 / settings.noiseSettings().getCellWidth(), random,
                chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                settings.noiseSettings().clampToHeightAccessor(chunk), NO_STRUCTURES, settings,
                (x, y, z) -> new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid()), Blender.empty());
        this.height = height;
    }

    @Override public int preliminarySurfaceLevel(int x, int z) {
        return heights.computeIfAbsent(ChunkPos.asLong(x, z), key -> height.applyAsInt(x, z));
    }
}
