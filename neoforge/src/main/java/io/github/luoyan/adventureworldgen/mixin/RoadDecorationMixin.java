package io.github.luoyan.adventureworldgen.mixin;

import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Protects roads from both local and neighbouring feature writes during world generation only. */
@Mixin(WorldGenRegion.class)
public abstract class RoadDecorationMixin {
    @Inject(method="setBlock",at=@At("HEAD"),cancellable=true)
    private void adventureworldgen$protectRoad(BlockPos pos,BlockState state,int flags,int recursion,CallbackInfoReturnable<Boolean> ci) {
        var region=(WorldGenRegion)(Object)this;
        if(region.getLevel().getChunkSource().getGenerator() instanceof AdventureChunkGenerator generator
                &&generator.protectsRoad(pos))ci.setReturnValue(false);
    }
}
