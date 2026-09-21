package io.github.luoyan.adventureworldgen.mixin;

import io.github.luoyan.adventureworldgen.worldgen.structure.JigsawParameters;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
import java.util.Optional;

@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin implements JigsawParameters {
    @Accessor("startPool") public abstract Holder<StructureTemplatePool> adventureworldgen$startPool();
    @Accessor("startJigsawName") public abstract Optional<ResourceLocation> adventureworldgen$startJigsawName();
    @Accessor("maxDepth") public abstract int adventureworldgen$maxDepth();
    @Accessor("startHeight") public abstract HeightProvider adventureworldgen$startHeight();
    @Accessor("useExpansionHack") public abstract boolean adventureworldgen$useExpansionHack();
    @Accessor("projectStartToHeightmap") public abstract Optional<Heightmap.Types> adventureworldgen$projectStartToHeightmap();
    @Accessor("maxDistanceFromCenter") public abstract int adventureworldgen$maxDistanceFromCenter();
    @Accessor("poolAliases") public abstract List<PoolAliasBinding> adventureworldgen$poolAliases();
    @Accessor("dimensionPadding") public abstract DimensionPadding adventureworldgen$dimensionPadding();
    @Accessor("liquidSettings") public abstract LiquidSettings adventureworldgen$liquidSettings();
}
