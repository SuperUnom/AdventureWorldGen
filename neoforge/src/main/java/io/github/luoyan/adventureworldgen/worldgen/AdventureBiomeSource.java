package io.github.luoyan.adventureworldgen.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class AdventureBiomeSource extends BiomeSource {
    public static final MapCodec<AdventureBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("profile").forGetter(AdventureBiomeSource::profile),
            RegistryOps.<Biome>retrieveRegistryLookup(Registries.BIOME).forGetter(source -> null)
    ).apply(instance, AdventureBiomeSource::new));

    private final ResourceLocation profile;
    private final HolderLookup.RegistryLookup<Biome> biomes;

    public AdventureBiomeSource(ResourceLocation profile, HolderLookup.RegistryLookup<Biome> biomes) {
        this.profile = profile; this.biomes = biomes;
    }

    public ResourceLocation profile() { return profile; }
    @Override protected MapCodec<? extends BiomeSource> codec() { return ModWorldgen.BIOME_SOURCE.get(); }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return biomes.listElements().map(holder -> holder); }

    @Override public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        ContentId id = RuntimePlanRegistry.await(new ContentId(profile.toString())).biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ));
        ResourceLocation location = ResourceLocation.parse(id.value());
        return biomes.getOrThrow(ResourceKey.create(Registries.BIOME, location));
    }
}
