package io.github.luoyan.adventureworldgen.worldgen.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.List;
import java.util.Optional;

/** Explicit standalone-template definition. Template-backed Java structures keep their Java callbacks. */
public final class TemplateStructure extends Structure implements TerrainSupportProvider {
    public static final MapCodec<TemplateStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            ResourceLocation.CODEC.fieldOf("template").forGetter(s -> s.template),
            StructureProcessorType.LIST_CODEC.fieldOf("processors").forGetter(s -> s.processors),
            Foundation.CODEC.fieldOf("support").forGetter(s -> s.support),
            Heightmap.Types.CODEC.optionalFieldOf("heightmap", Heightmap.Types.WORLD_SURFACE_WG).forGetter(s -> s.heightmap),
            Codec.intRange(-384, 384).optionalFieldOf("height_offset", 0).forGetter(s -> s.heightOffset),
            Rotation.CODEC.optionalFieldOf("rotation").forGetter(s -> s.rotation),
            TerrainSettings.CODEC.optionalFieldOf("foundation", new TerrainSettings(TerrainSettings.Mode.FLATTEN, 12)).forGetter(s -> s.terrain)
    ).apply(i, TemplateStructure::new));

    private final ResourceLocation template;
    private final Holder<StructureProcessorList> processors;
    private final Foundation support;
    private final Heightmap.Types heightmap;
    private final int heightOffset;
    private final Optional<Rotation> rotation;
    private final TerrainSettings terrain;

    public TemplateStructure(StructureSettings settings, ResourceLocation template, Holder<StructureProcessorList> processors,
                             Foundation support, Heightmap.Types heightmap, int heightOffset,
                             Optional<Rotation> rotation, TerrainSettings terrain) {
        super(settings);
        this.template = template;
        this.processors = processors;
        this.support = support;
        this.heightmap = heightmap;
        this.heightOffset = heightOffset;
        this.rotation = rotation;
        this.terrain = terrain;
    }

    public TerrainSettings foundationSettings() { return terrain; }

    public record PlanningBounds(BoundingBox geometry,BoundingBox exclusion,int rotation) {}
    /** No height/biome query or piece construction: horizontal geometry is resource-defined. */
    public List<PlanningBounds> planningBounds(StructureTemplateManager manager,TerrainSettings effectiveTerrain) {
        validateTemplate(manager);
        var value=manager.get(template).orElseThrow();
        var rotations=rotation.map(List::of).orElseGet(()->List.of(Rotation.values()));
        return rotations.stream().map(selected->{
            var geometry=value.getBoundingBox(new StructurePlaceSettings().setRotation(selected),BlockPos.ZERO);
            var exclusion=adjustBoundingBox(geometry);
            if(effectiveTerrain.mode()==TerrainSettings.Mode.FILL||effectiveTerrain.mode()==TerrainSettings.Mode.FLATTEN) {
                var a=StructureTemplate.transform(new BlockPos(support.minX(),0,support.minZ()),Mirror.NONE,selected,BlockPos.ZERO);
                var b=StructureTemplate.transform(new BlockPos(support.maxX(),0,support.maxZ()),Mirror.NONE,selected,BlockPos.ZERO);
                var area=new Foundation(Math.min(a.getX(),b.getX()),Math.min(a.getZ(),b.getZ()),
                        Math.max(a.getX(),b.getX()),Math.max(a.getZ(),b.getZ()),0);
                exclusion=BoundingBox.encapsulatingBoxes(List.of(exclusion,area.influence(effectiveTerrain.margin()))).orElseThrow();
            }
            return new PlanningBounds(geometry,exclusion,selected.ordinal());
        }).toList();
    }

    public void validateTemplate(StructureTemplateManager manager) {
        var value = manager.get(template).orElseThrow(() -> new IllegalStateException("missing template " + template));
        var size = value.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0)
            throw new IllegalStateException("empty template " + template);
        if (support.minX() < 0 || support.minZ() < 0 || support.maxX() >= size.getX()
                || support.maxZ() >= size.getZ() || support.surface() < 0 || support.surface() > size.getY())
            throw new IllegalStateException("support outside template " + template);
        var settings = new StructurePlaceSettings();
        if (!value.filterBlocks(BlockPos.ZERO, settings, Blocks.STRUCTURE_BLOCK).isEmpty()
                || !value.filterBlocks(BlockPos.ZERO, settings, Blocks.JIGSAW).isEmpty())
            throw new IllegalStateException("standalone template contains markers/connectors; use a Java or jigsaw structure: " + template);
        // A rotated rectangle can extend in either direction from the macro anchor.
        if (Math.max(size.getX(), size.getZ()) - 1 + terrain.margin() > 112)
            throw new IllegalStateException("template exceeds native reference envelope: " + template);
    }

    public GenerationStub assemble(GenerationContext context, BlockPos anchor) {
        validateTemplate(context.structureTemplateManager());
        Rotation selected = rotation.orElseGet(() -> Rotation.values()[
                io.github.luoyan.adventureworldgen.spatial.TemplateRotation.index(context.seed(),context.chunkPos().x,context.chunkPos().z)]);
        int surface = context.chunkGenerator().getFirstFreeHeight(anchor.getX(), anchor.getZ(), heightmap,
                context.heightAccessor(), context.randomState()) + heightOffset;
        BlockPos origin = new BlockPos(anchor.getX(), surface - support.surface(), anchor.getZ());
        BlockPos a = StructureTemplate.transform(new BlockPos(support.minX(), 0, support.minZ()), Mirror.NONE, selected, BlockPos.ZERO).offset(origin);
        BlockPos b = StructureTemplate.transform(new BlockPos(support.maxX(), 0, support.maxZ()), Mirror.NONE, selected, BlockPos.ZERO).offset(origin);
        var area = new Foundation(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), surface);
        return new GenerationStub(origin, builder -> builder.addPiece(
                new Piece(context.structureTemplateManager(), template, origin, selected, processors, area)));
    }

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        return Optional.of(assemble(context, context.chunkPos().getWorldPosition()));
    }
    @Override public StructureType<?> type() { return StructureTypes.TEMPLATE.get(); }
    @Override public List<Foundation> terrainSupports(StructureStart start) {
        return start.getPieces().stream().map(p -> ((Piece) p).support).toList();
    }

    public static final class Piece extends TemplateStructurePiece {
        private final Rotation rotation;
        private final Holder<StructureProcessorList> processors;
        private final Foundation support;
        public Piece(StructureTemplateManager manager, ResourceLocation template, BlockPos origin, Rotation rotation,
                     Holder<StructureProcessorList> processors, Foundation support) {
            super(StructureTypes.TEMPLATE_PIECE.get(), 0, manager, template, template.toString(), settings(rotation, processors), origin);
            this.rotation = rotation; this.processors = processors; this.support = support;
        }
        public Piece(StructurePieceSerializationContext context, CompoundTag tag) {
            super(StructureTypes.TEMPLATE_PIECE.get(), tag, context.structureTemplateManager(), ignored -> settings(
                    Rotation.valueOf(tag.getString("Rotation")), readProcessors(context, tag)));
            rotation = Rotation.valueOf(tag.getString("Rotation"));
            processors = readProcessors(context, tag);
            support = Foundation.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("Support")).getOrThrow();
            if (context.structureTemplateManager().get(makeTemplateLocation()).isEmpty())
                throw new IllegalStateException("missing saved structure template " + templateName);
        }
        private static Holder<StructureProcessorList> readProcessors(StructurePieceSerializationContext context, CompoundTag tag) {
            return StructureProcessorType.LIST_CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, context.registryAccess()),
                    tag.get("Processors")).getOrThrow();
        }
        private static StructurePlaceSettings settings(Rotation rotation, Holder<StructureProcessorList> processors) {
            var settings = new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(false).setFinalizeEntities(true);
            processors.value().list().forEach(settings::addProcessor);
            return settings;
        }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
            super.addAdditionalSaveData(context, tag);
            tag.putString("Rotation", rotation.name());
            tag.put("Processors", StructureProcessorType.LIST_CODEC.encodeStart(
                    RegistryOps.create(NbtOps.INSTANCE, context.registryAccess()), processors).getOrThrow());
            tag.put("Support", Foundation.CODEC.encodeStart(NbtOps.INSTANCE, support).getOrThrow());
        }
        @Override public void postProcess(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                                          RandomSource random, BoundingBox writable, ChunkPos chunk, BlockPos pivot) {
            // Per-call settings prevent neighbouring chunk workers sharing a mutable clip box.
            var settings = settings(rotation, processors).setBoundingBox(writable);
            template.placeInWorld(level, templatePosition, pivot, settings, random, 2);
        }
        @Override protected void handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level,
                                                   RandomSource random, BoundingBox box) {
            throw new IllegalStateException("standalone templates do not define Java data markers");
        }
    }
}
