package io.github.luoyan.adventureworldgen.testcompanion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
/**
 * A structure piece that exists only in the test companion mod.
 *
 * <p>Production restores frozen pieces through the piece registry, so a companion piece proves the
 * extension point without the core ever naming it: the type is registered by this mod, the adapter
 * freezes its NBT, and the shared chunk generator rebuilds it from the registered type alone.
 *
 * <p>Everything that decides the result is frozen in the piece NBT - bounding box, orientation,
 * piece index, material variant, loot table and loot seed. {@code postProcess} draws a fixed test
 * building, so generation is reproducible and the acceptance audit can assert on named blocks and
 * on a container whose loot table and seed survive a save/restart.
 */
public final class WaystationPiece extends StructurePiece {
    public static final String TYPE_ID = "waystation_piece";
    public static final ResourceKey<LootTable> SIMPLE_DUNGEON_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));

    /** Local Y layers. Local 0 is the frozen origin height, which the terrain surface already holds. */
    public static final int FLOOR_Y = 1;
    public static final int WALL_BOTTOM = 2;
    public static final int WALL_TOP = 4;
    public static final int ROOF_Y = 5;
    public static final int MARKER_Y = 6;
    public static final int HEIGHT = 8;

    public static final int VARIANTS = 4;

    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, TestCompanion.ID);
    /** Registered during the mod's registration phase; nothing outside this mod names it. */
    public static final DeferredHolder<StructurePieceType, StructurePieceType> TYPE =
            PIECES.register(TYPE_ID, () -> (StructurePieceType.ContextlessType) WaystationPiece::new);

    private static final Palette[] PALETTES = {
            new Palette(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()),
            new Palette(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(), Blocks.DEEPSLATE_BRICKS.defaultBlockState()),
            new Palette(Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState()),
            new Palette(Blocks.NETHER_BRICKS.defaultBlockState(), Blocks.RED_NETHER_BRICKS.defaultBlockState(), Blocks.POLISHED_BLACKSTONE.defaultBlockState()),
    };

    private final int index;
    private final int variant;
    private final long lootSeed;
    private final ResourceKey<LootTable> lootTable;

    public WaystationPiece(int index, int variant, long lootSeed, ResourceKey<LootTable> lootTable,
                           Direction orientation, BoundingBox box) {
        super(TYPE.get(), 0, box);
        this.index = index;
        this.variant = Math.floorMod(variant, VARIANTS);
        this.lootSeed = lootSeed;
        this.lootTable = lootTable;
        this.setOrientation(orientation);
    }

    public WaystationPiece(CompoundTag tag) {
        super(TYPE.get(), tag);
        this.index = tag.getInt("Index");
        this.variant = Math.floorMod(tag.getInt("Variant"), VARIANTS);
        this.lootSeed = tag.getLong("LootTableSeed");
        this.lootTable = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(tag.getString("LootTable")));
    }

    public static void register(IEventBus modBus) { PIECES.register(modBus); }

    public int index() { return index; }
    public int variant() { return variant; }
    public long lootSeed() { return lootSeed; }
    public ResourceKey<LootTable> lootTable() { return lootTable; }

    /** Local extent along the piece's own X axis; the orientation rotates it into the world. */
    public int localWidth() {
        return this.getOrientation() != null && this.getOrientation().getAxis() == Direction.Axis.X
                ? this.boundingBox.getZSpan() : this.boundingBox.getXSpan();
    }

    /** Local extent along the piece's own Z axis. */
    public int localDepth() {
        return this.getOrientation() != null && this.getOrientation().getAxis() == Direction.Axis.X
                ? this.boundingBox.getXSpan() : this.boundingBox.getZSpan();
    }

    /** Local position of one of the two containers, in the order {@code postProcess} places them. */
    public int[] containerLocalPos(int which) {
        int width = localWidth(), depth = localDepth();
        return which == 0 ? new int[]{2, FLOOR_Y, 2} : new int[]{width - 3, FLOOR_Y, depth - 3};
    }

    /** The direction marker block; the audit looks for exactly these on the roof line. */
    public static BlockState markerBlock() { return Blocks.RED_CONCRETE.defaultBlockState(); }

    /**
     * Local Z of the entrance face. Vanilla's local mapping runs local Z opposite to the piece's
     * facing for NORTH and WEST, so this is the side the orientation points at in every rotation.
     */
    public int entranceLocalZ() { return localDepth() - 1; }

    /** World positions of the roof marker line, in the order {@code postProcess} writes them. */
    public List<BlockPos> markerWorldPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (int z = entranceLocalZ(); z > Math.max(-1, entranceLocalZ() - 4); z--)
            positions.add(this.getWorldPos(localWidth() / 2, MARKER_Y, z).immutable());
        return List.copyOf(positions);
    }

    /** World position of one of the two containers, in the same order {@code postProcess} uses. */
    public BlockPos containerWorldPos(int which) {
        int[] local = containerLocalPos(which);
        return this.getWorldPos(local[0], local[1], local[2]).immutable();
    }

    /** Body positions the normal-world audit asserts on: where the block must be, and which one. */
    public List<BodySample> bodySamples() {
        int width = localWidth(), depth = localDepth();
        return List.of(
                new BodySample(this.getWorldPos(0, FLOOR_Y, 0).immutable(), floorBlock(variant)),
                new BodySample(this.getWorldPos(width - 1, FLOOR_Y, depth - 1).immutable(), floorBlock(variant)),
                new BodySample(this.getWorldPos(0, WALL_BOTTOM, 0).immutable(), wallBlock(variant)),
                new BodySample(this.getWorldPos(width - 1, ROOF_Y, depth - 1).immutable(), roofBlock(variant)));
    }

    public static BlockState floorBlock(int variant) { return PALETTES[Math.floorMod(variant, VARIANTS)].floor(); }
    public static BlockState wallBlock(int variant) { return PALETTES[Math.floorMod(variant, VARIANTS)].wall(); }
    public static BlockState roofBlock(int variant) { return PALETTES[Math.floorMod(variant, VARIANTS)].roof(); }

    /** One asserted body block: the world position and the state {@code postProcess} writes there. */
    public record BodySample(BlockPos pos, BlockState state) {}


    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("Index", index);
        tag.putInt("Variant", variant);
        tag.putString("LootTable", lootTable.location().toString());
        tag.putLong("LootTableSeed", lootSeed);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        // Nothing here consults the random source: every choice was frozen when the plan was built.
        Palette palette = PALETTES[variant];
        int width = localWidth(), depth = localDepth();
        BlockState air = Blocks.AIR.defaultBlockState();

        this.generateBox(level, box, 0, FLOOR_Y, 0, width - 1, FLOOR_Y, depth - 1,
                palette.floor(), palette.floor(), false);
        this.generateBox(level, box, 0, WALL_BOTTOM, 0, width - 1, WALL_TOP, depth - 1,
                palette.wall(), air, false);
        this.generateBox(level, box, 0, ROOF_Y, 0, width - 1, ROOF_Y, depth - 1,
                palette.roof(), palette.roof(), false);

        // Windows every fourth column on both long sides, and a glass door on the entrance face.
        for (int x = 2; x < width - 2; x += 4) {
            this.placeBlock(level, Blocks.GLASS_PANE.defaultBlockState(), x, FLOOR_Y + 1, 0, box);
            this.placeBlock(level, Blocks.GLASS_PANE.defaultBlockState(), x, FLOOR_Y + 1, depth - 1, box);
        }
        this.placeBlock(level, Blocks.GLASS_PANE.defaultBlockState(), width / 2, WALL_BOTTOM, depth - 1, box);

        // Direction marker: a line on the roof pointing at the entrance face, plus its cap block.
        for (int z = entranceLocalZ(); z > Math.max(-1, entranceLocalZ() - 4); z--)
            this.placeBlock(level, markerBlock(), width / 2, MARKER_Y, z, box);

        placeContainer(level, box, 0);
        placeContainer(level, box, 1);
    }

    private void placeContainer(WorldGenLevel level, BoundingBox box, int which) {
        int[] local = containerLocalPos(which);
        BlockPos pos = this.getWorldPos(local[0], local[1], local[2]);
        // Clipped to the box this chunk was handed: a container outside it is placed by the chunk
        // that owns it, and re-entering the same chunk never creates a second one.
        if (!box.isInside(pos) || level.getBlockState(pos).is(Blocks.CHEST)) return;
        level.setBlock(pos, StructurePiece.reorient(level, pos, Blocks.CHEST.defaultBlockState()), 2);
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.setLootTable(lootTable);
            chest.setLootTableSeed(lootSeed);
        }
    }

    private record Palette(BlockState floor, BlockState wall, BlockState roof) {}
}
