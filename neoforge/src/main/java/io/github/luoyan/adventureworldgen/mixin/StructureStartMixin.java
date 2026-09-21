package io.github.luoyan.adventureworldgen.mixin;

import io.github.luoyan.adventureworldgen.worldgen.structure.ExecutionDataHolder;
import io.github.luoyan.adventureworldgen.worldgen.structure.StructureExecutionData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureStart.class)
public abstract class StructureStartMixin implements ExecutionDataHolder {
    @Unique private StructureExecutionData adventureworldgen$executionData;
    @Override public StructureExecutionData adventureworldgen$getExecutionData() { return adventureworldgen$executionData; }
    @Override public void adventureworldgen$setExecutionData(StructureExecutionData data) { adventureworldgen$executionData = data; }

    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void adventureworldgen$includeTerrain(CallbackInfoReturnable<BoundingBox> ci) {
        if (adventureworldgen$executionData != null)
            ci.setReturnValue(adventureworldgen$executionData.includeInfluence(ci.getReturnValue()));
    }

    @Inject(method = "createTag", at = @At("RETURN"))
    private void adventureworldgen$save(StructurePieceSerializationContext context, ChunkPos pos,
                                      CallbackInfoReturnable<CompoundTag> ci) {
        if (adventureworldgen$executionData != null)
            ci.getReturnValue().put(StructureExecutionData.TAG, adventureworldgen$executionData.save());
    }

    @Inject(method = "loadStaticStart", at = @At("RETURN"))
    private static void adventureworldgen$load(StructurePieceSerializationContext context, CompoundTag tag, long seed,
                                             CallbackInfoReturnable<StructureStart> ci) {
        if (tag.contains(StructureExecutionData.TAG)) {
            var start = ci.getReturnValue();
            if (start == null || !start.isValid()) throw new IllegalStateException("could not restore planned structure start");
            ((ExecutionDataHolder) (Object) start).adventureworldgen$setExecutionData(
                    StructureExecutionData.load(tag.getCompound(StructureExecutionData.TAG)));
        }
    }
}
