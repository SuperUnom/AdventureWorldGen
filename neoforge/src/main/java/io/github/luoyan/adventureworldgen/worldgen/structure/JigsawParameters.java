package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import java.util.List;
import java.util.Optional;

/** Read-only view of the registered JigsawStructure definition; no duplicated settings. */
public interface JigsawParameters {
    Holder<StructureTemplatePool> adventureworldgen$startPool();
    Optional<ResourceLocation> adventureworldgen$startJigsawName();
    int adventureworldgen$maxDepth();
    HeightProvider adventureworldgen$startHeight();
    boolean adventureworldgen$useExpansionHack();
    Optional<Heightmap.Types> adventureworldgen$projectStartToHeightmap();
    int adventureworldgen$maxDistanceFromCenter();
    List<PoolAliasBinding> adventureworldgen$poolAliases();
    DimensionPadding adventureworldgen$dimensionPadding();
    LiquidSettings adventureworldgen$liquidSettings();
}
