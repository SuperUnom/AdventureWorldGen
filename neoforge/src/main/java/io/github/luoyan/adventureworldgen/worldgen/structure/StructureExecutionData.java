package io.github.luoyan.adventureworldgen.worldgen.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.ArrayList;
import java.util.List;

/** Stored in the native start, never in the planning snapshot. */
public record StructureExecutionData(String instanceId, TerrainSettings terrain, List<Foundation> foundations) {
    public static final String TAG = "adventureworldgen_execution";
    private static final Codec<StructureExecutionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("instance_id").forGetter(StructureExecutionData::instanceId),
            TerrainSettings.CODEC.fieldOf("terrain").forGetter(StructureExecutionData::terrain),
            Foundation.CODEC.listOf().fieldOf("foundations").forGetter(StructureExecutionData::foundations)
    ).apply(i, StructureExecutionData::new));
    public StructureExecutionData { foundations = List.copyOf(foundations); }
    public CompoundTag save() {
        var result = (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).getOrThrow();
        result.putInt("version", 1);
        return result;
    }
    public static StructureExecutionData load(CompoundTag tag) {
        if (tag.getInt("version") != 1) throw new IllegalStateException("unsupported structure execution data version");
        return CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
    public BoundingBox includeInfluence(BoundingBox original) {
        var boxes = new ArrayList<BoundingBox>();
        boxes.add(original);
        for (var foundation : foundations) boxes.add(foundation.influence(terrain.margin()));
        return BoundingBox.encapsulatingBoxes(boxes).orElseThrow();
    }
}
