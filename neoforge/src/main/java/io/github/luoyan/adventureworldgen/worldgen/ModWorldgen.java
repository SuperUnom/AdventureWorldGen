package io.github.luoyan.adventureworldgen.worldgen;

import com.mojang.serialization.MapCodec;
import io.github.luoyan.adventureworldgen.AdventureWorldGen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModWorldgen {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, AdventureWorldGen.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, AdventureWorldGen.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<AdventureChunkGenerator>> CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("adventure", () -> AdventureChunkGenerator.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<AdventureBiomeSource>> BIOME_SOURCE =
            BIOME_SOURCES.register("adventure", () -> AdventureBiomeSource.CODEC);

    private ModWorldgen() {}
    public static void register(IEventBus bus) { CHUNK_GENERATORS.register(bus); BIOME_SOURCES.register(bus); }
}
